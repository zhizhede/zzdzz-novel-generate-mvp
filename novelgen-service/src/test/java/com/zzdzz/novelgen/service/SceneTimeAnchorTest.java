package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 场景级时间锚契约：解析、取值链与场景提示词模板。 */
class SceneTimeAnchorTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void sceneTimeReadsTextField() throws Exception {
        var scene = M.readTree("{\"goal\":\"a\",\"time\":\"当夜\"}");
        assertThat(OutlineService.sceneTime(scene)).isEqualTo("当夜");
    }

    @Test
    void sceneTimeMissingNullOrOverlongReturnsNull() throws Exception {
        assertThat(OutlineService.sceneTime(M.readTree("{\"goal\":\"a\"}"))).isNull();
        assertThat(OutlineService.sceneTime(M.readTree("{\"time\":123}"))).isNull();
        assertThat(OutlineService.sceneTime(M.readTree("{\"time\":\"  \"}"))).isNull();
        assertThat(OutlineService.sceneTime(M.readTree("{\"time\":\"" + "x".repeat(65) + "\"}"))).isNull();
        assertThat(OutlineService.sceneTime(null)).isNull();
    }

    @Test
    void anchorFallsBackToChapterTimeNoteThenPlaceholder() {
        assertThat(ContextPackerService.sceneTimeAnchor("封港半小时后", "紧接上一章"))
                .isEqualTo("封港半小时后");
        assertThat(ContextPackerService.sceneTimeAnchor(null, "入城后第三年"))
                .isEqualTo("入城后第三年（本场景锚未单独给出，按章级时间跨度推进）");
        assertThat(ContextPackerService.sceneTimeAnchor("  ", "紧接上一章"))
                .contains("紧接上一章");
        assertThat(ContextPackerService.sceneTimeAnchor(null, null))
                .contains("禁止时间回退");
        assertThat(ContextPackerService.sceneTimeAnchor("", " "))
                .contains("禁止时间回退");
    }

    @Test
    void outlineTemplateAsksForSceneTime() {
        String content = PromptCatalog.contentOf(LlmNode.OUTLINE, "user");
        assertThat(content).contains("\"time\"");
        assertThat(content).contains("禁止回退");
    }

    @Test
    void sceneDraftTemplateHasAnchorLineAndArityTwenty() {
        String content = PromptCatalog.contentOf(LlmNode.SCENE_DRAFT, "user");
        assertThat(content).contains("本场景时间锚：%s");
        List<String> args = PromptTemplateService.specs(content).stream()
                .filter(s -> !s.equals("%%")).toList();
        assertThat(args).hasSize(22);
    }

    @Test
    void aiReviewTemplatesCarrySceneTimes() {
        String user = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "user");
        assertThat(user).contains("{scene_times}");
        String system = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "system");
        assertThat(system).contains("场景时间线");
        assertThat(system).contains("时序颠倒/回退属硬伤");
    }
}
