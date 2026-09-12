package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LlmJson 容错解析行为基线：重构期 1（三份内联拷贝迁入）前先锁行为。 */
class LlmJsonTest {

    private final LlmJson llmJson = new LlmJson(null); // read/repair 不触 LlmPort

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
}
