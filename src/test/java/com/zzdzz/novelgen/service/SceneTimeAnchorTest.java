package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景级时间锚（V42）契约：
 * ① sceneTime 解析——模型漏字段/空白/超长一律 null（不落列，消费方回退章级 time_note）；
 * ② 取值链——场景锚 → 章级 time_note → 兜底文案，提示词永远有这一行；
 * ③ 模板——OUTLINE/换皮要求输出 time、SCENE_DRAFT 注入时间锚行（arity 20，formatSafe fail-open 需锁）；
 * ④ beatsToScenes——换皮 beats 的 time 透传、迁移链无 time 天然 null。
 */
class SceneTimeAnchorTest {

    private static final ObjectMapper M = new ObjectMapper();

    // ===== ① 解析 =====

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

    // ===== ② 取值链 =====

    @Test
    void anchorFallsBackToChapterTimeNoteThenPlaceholder() {
        // 场景锚优先
        assertThat(ContextPackerService.sceneTimeAnchor("封港半小时后", "紧接上一章"))
                .isEqualTo("封港半小时后");
        // 场景锚空 → 章级 time_note（换皮/迁移书 beats 无 time 时的主路径）
        assertThat(ContextPackerService.sceneTimeAnchor(null, "入城后第三年"))
                .isEqualTo("入城后第三年（本场景锚未单独给出，按章级时间跨度推进）");
        assertThat(ContextPackerService.sceneTimeAnchor("  ", "紧接上一章"))
                .contains("紧接上一章");
        // 两级全空 → 兜底（提示词仍有这一行，模型不自由发挥时间线）
        assertThat(ContextPackerService.sceneTimeAnchor(null, null))
                .contains("禁止时间回退");
        assertThat(ContextPackerService.sceneTimeAnchor("", " "))
                .contains("禁止时间回退");
    }

    // ===== ③ 模板 =====

    @Test
    void outlineTemplateAsksForSceneTime() {
        String content = PromptCatalog.contentOf(LlmNode.OUTLINE, "user");
        assertThat(content).contains("\"time\"");
        assertThat(content).contains("禁止回退");
    }

    @Test
    void reskinChapterTemplateAsksForBeatTime() {
        // 换皮 beats 加 time 不加 %s——ReskinPromptArityTest 的 5 参锁不能被打破
        String content = PromptCatalog.contentOf(LlmNode.DERIVE_RESKIN, "chapter");
        assertThat(content).contains("\"time\"");
        List<String> specs = PromptTemplateService.specs(content);
        assertThat(specs).hasSize(5);
    }

    @Test
    void sceneDraftTemplateHasAnchorLineAndArityTwenty() {
        String content = PromptCatalog.contentOf(LlmNode.SCENE_DRAFT, "user");
        assertThat(content).contains("本场景时间锚：%s");
        // arity 锁：19→20 个实参（新增时间锚行）；specs 含「±15%%」的转义 `%%` 要滤掉——
        // 调用点在 ContextPackerService.packScene，formatSafe 对个数不匹配会静默回退（AGENTS 坑 14 同族）
        List<String> args = PromptTemplateService.specs(content).stream()
                .filter(s -> !s.equals("%%")).toList();
        assertThat(args).hasSize(20);
    }

    @Test
    void aiReviewTemplatesCarrySceneTimes() {
        String user = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "user");
        assertThat(user).contains("{scene_times}");
        String system = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "system");
        assertThat(system).contains("场景时间线");
        assertThat(system).contains("时序颠倒/回退属硬伤");
    }

    // ===== ④ beatsToScenes 透传 =====

    @Test
    void beatsToScenesCarriesTimeAndToleratesMissing() throws Exception {
        String withTime = "[{\"goal\":\"a\",\"outcome\":\"o\",\"conflict\":\"c\",\"time\":\"当夜\"},"
                + "{\"goal\":\"b\",\"outcome\":\"o2\",\"conflict\":\"c2\"}]";
        List<OutlineService.SceneSpec> specs = NovelService.beatsToScenes(withTime, 1200);
        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).timeAnchor()).isEqualTo("当夜");
        // 迁移链样本 beats 无 time 字段：天然 null（回退章级 time_note）
        assertThat(specs.get(1).timeAnchor()).isNull();

        // 超长 time 拒收
        String overlong = "[{\"goal\":\"a\",\"outcome\":\"o\",\"time\":\"" + "x".repeat(65) + "\"}]";
        assertThat(NovelService.beatsToScenes(overlong, 1200).get(0).timeAnchor()).isNull();
        // 坏 JSON / 空数组照旧空列表
        assertThat(NovelService.beatsToScenes("not-json", 1200)).isEmpty();
        assertThat(NovelService.beatsToScenes(null, 1200)).isEmpty();
    }
}
