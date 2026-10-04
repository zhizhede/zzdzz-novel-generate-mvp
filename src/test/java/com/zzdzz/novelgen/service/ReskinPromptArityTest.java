package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换皮提示词的**占位符个数**锁：formatSafe 是 fail-open 的（个数不匹配会静默回退代码模板），
 * 一旦调用方少传/多传参数，库内编辑过的版本会被悄悄忽略、且不报错。
 * 所以把「模板 %s 个数 == 调用点实参数」这条契约钉成测试。
 */
class ReskinPromptArityTest {

    private static List<String> specs(String phase) {
        PromptCatalog.TemplateDef def = PromptCatalog.ALL.stream()
                .filter(t -> LlmNode.DERIVE_RESKIN.equals(t.node()) && phase.equals(t.phase()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("换皮模板缺失：" + phase));
        assertTrue(def.exact(), "换皮模板应为 exact=true（位置参数）");
        return PromptTemplateService.specs(def.content());
    }

    /** chapter 调用点：skin, chapterNo, SAMPLE_TIME_HINT, summary, beats = 5 个位置参数。 */
    @Test
    void chapterTemplateTakesFiveArgs() {
        assertEquals(5, specs("chapter").size(), "derive_reskin/chapter 的 %s 个数变了，调用点没跟着改就是静默回退");
        assertEquals(List.of("%s", "%s", "%s", "%s", "%s"), specs("chapter"));
    }

    /** skin 调用点：seed, 样本标题, 样本梗概 = 3 个位置参数。 */
    @Test
    void skinTemplateTakesThreeArgs() {
        assertEquals(3, specs("skin").size());
    }

    /** 换皮章必须问出时间跨度，否则样本的跨年章格会被压成「次日」。 */
    @Test
    void chapterTemplateAsksForTimeNote() {
        String content = PromptCatalog.contentOf(LlmNode.DERIVE_RESKIN, "chapter");
        assertTrue(content.contains("time_note"), "换皮章输出结构里必须含 time_note");
        assertTrue(content.contains("时间跨度"), "换皮章必须显式声明距上一章的时间跨度");
    }
}
