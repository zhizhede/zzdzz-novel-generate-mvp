package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章纲步 pov 字段契约：
 * ① chapterPov 解析——模型漏字段/空白/超长一律 null（不写列，走书级回退），防止垃圾值污染读取链；
 * ② OUTLINE 模板 arity——formatSafe 是 fail-open 的，%s 个数与调用点不匹配会静默回退（AGENTS 坑 14 同族）。
 */
class OutlineChapterPovTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void chapterPovReadsRootField() throws Exception {
        JsonNode root = M.readTree("{\"pov\":\"第一人称\",\"scenes\":[{\"goal\":\"a\"},{\"goal\":\"b\"}]}");
        assertThat(OutlineService.chapterPov(root)).isEqualTo("第一人称");
    }

    @Test
    void chapterPovMissingNullOrOverlongReturnsNull() throws Exception {
        // 模型漏字段：最常见的形态（老模板没有 pov 键）
        assertThat(OutlineService.chapterPov(M.readTree("{\"scenes\":[]}"))).isNull();
        // 非文本（模型给了对象/数字）
        assertThat(OutlineService.chapterPov(M.readTree("{\"pov\":123}"))).isNull();
        // 空白
        assertThat(OutlineService.chapterPov(M.readTree("{\"pov\":\"  \"}"))).isNull();
        // 超长（>32 字）＝模型跑飞写长文，拒绝入库
        String longPov = "x".repeat(33);
        assertThat(OutlineService.chapterPov(M.readTree(M.createObjectNode().put("pov", longPov).toString()))).isNull();
        // null 根节点
        assertThat(OutlineService.chapterPov(null)).isNull();
    }

    @Test
    void chapterPovStripsAndKeepsShortValue() throws Exception {
        assertThat(OutlineService.chapterPov(M.readTree("{\"pov\":\" 第三人称限知 \"}")))
                .isEqualTo("第三人称限知");
    }

    /** OUTLINE/user 的 %s 个数：15 个（原 14 + 新增「本书叙事人称」一行）。调用点在 OutlineService.generate。 */
    @Test
    void outlineUserTemplateArityMatchesCallSite() {
        List<String> specs = PromptCatalog.ALL.stream()
                .filter(t -> LlmNode.OUTLINE.equals(t.node()) && "user".equals(t.phase()))
                .findFirst()
                .map(t -> PromptTemplateService.specs(t.content()))
                .orElseThrow(() -> new AssertionError("OUTLINE/user 模板缺失"));
        assertThat(specs).hasSize(15);
        String content = PromptCatalog.contentOf(LlmNode.OUTLINE, "user");
        assertThat(content).contains("本书叙事人称");
        assertThat(content).contains("\"pov\"");
    }

    /** 章纲反推模板也要求输出 pov（导入成稿章的人称从事实反推）。 */
    @Test
    void bookChapterOutlineTemplateAsksForPov() {
        String content = PromptCatalog.contentOf(LlmNode.BOOK_CHAPTER_OUTLINE, "user");
        assertThat(content).contains("\"pov\"");
    }

    /** AI 审校第 5 查：system 列了 pov 类型、user 注入了 {pov} 段。 */
    @Test
    void aiReviewTemplatesCarryPovDimension() {
        String system = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "system");
        assertThat(system).contains("pov 人称漂移");
        assertThat(system).contains("continuity|logic|typo|format|pov");
        String user = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "user");
        assertThat(user).contains("{pov}");
    }

    /** 场景侧 derive_pov 段带「禁止章内切换」的强约束（原模板只有一句，人称漂移拦不住）。 */
    @Test
    void derivePovSectionForbidsMidChapterSwitch() {
        String content = PromptCatalog.contentOf(LlmNode.SCENE_DRAFT, "derive_pov");
        assertThat(content).contains("{pov}");
        assertThat(content).contains("禁止章内中途切换人称");
    }
}
