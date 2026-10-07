package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LlmJson 容错解析行为基线：重构期 1（三份内联拷贝迁入）前先锁行为。 */
class LlmJsonTest {

    private final LlmJson llmJson = new LlmJson(null, null); // read/repair 不触 LlmPort 与提示词注册表

    @Test
    void plainObjectParses() throws Exception {
        JsonNode n = llmJson.read("{\"a\":1,\"b\":\"x\"}");
        assertThat(n.get("a").asInt()).isEqualTo(1);
        assertThat(n.get("b").asText()).isEqualTo("x");
    }

    @Test
    void extractsBracesFromProse() throws Exception {
        JsonNode n = llmJson.read("好的，以下是结果：\n{\"title\":\"第一章\",\"no\":1}\n以上。");
        assertThat(n.get("title").asText()).isEqualTo("第一章");
    }

    @Test
    void noObjectThrowsBad() {
        assertThatThrownBy(() -> llmJson.read("完全没有大括号的输出"))
                .isInstanceOf(LlmJson.Bad.class)
                .hasMessageContaining("没有 JSON 对象");
    }

    @Test
    void bareControlCharInsideStringTolerated() throws Exception {
        String raw = "{\"a\":\"第一行\n第二行\"}";
        assertThat(llmJson.read(raw).get("a").asText()).isEqualTo("第一行\n第二行");
    }

    @Test
    void strayStraightQuotesRepairedToCornerBrackets() throws Exception {
        // 现行口径：引号后继非结构符（,}]:）一律换「——包括语义上的闭引号；目标只是让解析过
        JsonNode n = llmJson.read("{\"quote\":\"他说\"你好\"了\"}");
        assertThat(n.get("quote").asText()).isEqualTo("他说「你好「了");
    }

    @Test
    void structuralQuotesUntouched() throws Exception {
        JsonNode n = llmJson.read("{\"a\":\"x\",\"b\":[1,2]}");
        assertThat(n.get("b").size()).isEqualTo(2);
    }

    @Test
    void escapedQuotesPreserved() throws Exception {
        JsonNode n = llmJson.read("{\"a\":\"say \\\"hi\\\" ok\"}");
        assertThat(n.get("a").asText()).isEqualTo("say \"hi\" ok");
    }

    @Test
    void repairKeepsClosingQuoteBeforeStructuralChar() {
        assertThat(LlmJson.repairStraightQuotes("{\"a\":\"x\",\"b\":1}"))
                .isEqualTo("{\"a\":\"x\",\"b\":1}");
    }

    @Test
    void repairReplacesInteriorQuote() {
        assertThat(LlmJson.repairStraightQuotes("{\"a\":\"他说\"好\"了\"}"))
                .isEqualTo("{\"a\":\"他说「好「了\"}");
    }

    @Test
    void repairHandlesEscapeThenClose() {
        // \" 之后的真收口引号不得被误伤
        assertThat(LlmJson.repairStraightQuotes("{\"a\":\"say \\\"hi\\\"\",\"b\":1}"))
                .isEqualTo("{\"a\":\"say \\\"hi\\\"\",\"b\":1}");
    }

    // ===== 漏写收口括号（2026-09-30 探针书第 3 章 digest 实测畸形，原文见 fixtures）=====

    @Test
    void missingClosingBraceCompleted() throws Exception {
        // 模型漏写末尾一个 }：报错落在输出末尾，正文完好
        JsonNode n = llmJson.read("{\"a\":1,\"state\":{\"time\":\"夜\"}");
        assertThat(n.get("a").asInt()).isEqualTo(1);
        assertThat(n.path("state").path("time").asText()).isEqualTo("夜");
    }

    @Test
    void multipleMissingClosersCompleted() throws Exception {
        // 内层已收口、外层连着漏几个：按未闭合栈逆序补齐
        JsonNode n = llmJson.read("{\"facts\":[\"一\",\"二\"],\"state\":{\"time\":\"夜\",\"locations\":{\"灯塔\":\"岸边\"}}");
        assertThat(n.path("facts").size()).isEqualTo(2);
        assertThat(n.path("state").path("locations").path("灯塔").asText()).isEqualTo("岸边");
    }

    @Test
    void noClosingBraceAtAllStaysLoud() {
        // 通篇没有收口括号 = 更可能是截断：不猜，照旧报错（read 只能截到末个 }，够不着就够不着）
        assertThatThrownBy(() -> llmJson.read("{\"facts\":[\"一\",\"二\"],\"state\":{\"time\":\"夜\""))
                .isInstanceOf(LlmJson.Bad.class);
    }

    @Test
    void realDigestSlipParses() throws Exception {
        // 实测原文：new_threads 被写进 state 内部 + 末尾少一个收口 }（finish_reason=stop，非截断）
        String raw = new String(getClass().getResourceAsStream(
                "/fixtures/digest_new_threads_nested_in_state.txt").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        JsonNode n = llmJson.read(raw);
        assertThat(n.path("summary_md").asText()).startsWith("第三夜");
        assertThat(n.fieldNames()).toIterable().containsExactly("summary_md", "facts", "state");
        assertThat(n.path("facts").size()).isEqualTo(9);
        assertThat(n.path("state").path("unresolved").size()).isEqualTo(5);
        // 畸形照原样保留：new_threads 确实在 state 里（语义纠正归 DigestService）
        assertThat(n.path("state").path("new_threads").size()).isEqualTo(2);
        assertThat(n.path("state").path("new_threads").get(0).path("name").asText()).isEqualTo("黑潮递补");
    }

    @Test
    void truncatedStringNotRepaired() {
        // 截断（字符串未收口）必须显性失败：不许补括号后把半截事实账当成功放行
        assertThatThrownBy(() -> llmJson.read("{\"a\":1,\"state\":{\"time\":\"天还没"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void balancedJsonUntouchedByCloseUnclosed() {
        assertThat(LlmJson.closeUnclosed("{\"a\":[1,2]}")).isEqualTo("{\"a\":[1,2]}");
    }

    @Test
    void mismatchedCloserUntouched() {
        // 种类都对不上（多写的 }、结构错乱）：不猜，原样返回让上层报错
        assertThat(LlmJson.closeUnclosed("{\"a\":1}}")).isEqualTo("{\"a\":1}}");
    }

    @Test
    void bracesInsideStringIgnored() {
        assertThat(LlmJson.closeUnclosed("{\"a\":\"含 } 和 ] 的正文\"}"))
                .isEqualTo("{\"a\":\"含 } 和 ] 的正文\"}");
    }
}
