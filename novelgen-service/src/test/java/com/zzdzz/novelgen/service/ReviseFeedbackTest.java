package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.PromptCatalog;
import com.zzdzz.novelgen.service.GateService.GateCheck;
import com.zzdzz.novelgen.service.GateService.GateVerdict;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 卡边反馈契约（2026-10-07 实弹）：修订轮旧反馈只打 baseline 不打 abs_max——
 * 对白密度显示「基线null」（模型不知道 30.2 上限，两次收敛到 30.44 卡死）；
 * 行均长只显示中位数 23.68（模型朝中位数压，过冲到 15.65 跌破真实下限 20.13）。
 * 契约：① failedChecksText 打全目标区间 ② 修订模板带指标修复方向（纯文本行不改 arity）
 * ③ 密度主判据在章级（30.2，gate_config 键 dialogue_density_max 可覆盖）、场景级仅 1.5× 极端护栏。
 */
class ReviseFeedbackTest {

    // ===== ① failedChecksText 区间格式 =====

    @Test
    void bothBoundsShownAsRange() {
        // 章长：baseline=下限、abs_max=上限（实弹 2383 vs 2385 那类）
        String json = """
                {"checks":[{"check":"chapter_length","value":2383,"baseline":2385,"abs_max":3025,"ok":false}]}""";
        assertThat(GateService.failedChecksText(json))
                .isEqualTo("chapter_length=2383（2385~3025）");
    }

    @Test
    void absOnlyShownAsCap() {
        // 对白密度：baseline=null、abs_max=30.2（实弹「基线null」那类——模型必须看到上限）
        String json = """
                {"checks":[{"check":"dialogue_density_per1k","value":33.41,"baseline":null,"abs_max":30.2,"ok":false}]}""";
        assertThat(GateService.failedChecksText(json))
                .isEqualTo("dialogue_density_per1k=33.41（上限30.2）");
    }

    @Test
    void baselineOnlyShownAsBaseline() {
        String json = """
                {"checks":[{"check":"pov_consistent","value":0,"baseline":1,"ok":false}]}""";
        assertThat(GateService.failedChecksText(json))
                .isEqualTo("pov_consistent=0（基线1）");
    }

    @Test
    void okRowsSkippedAndEmptySafe() {
        String json = """
                {"checks":[{"check":"a","value":1,"baseline":0,"abs_max":9,"ok":true},
                {"check":"b","value":2,"baseline":3,"abs_max":8,"ok":false}]}""";
        assertThat(GateService.failedChecksText(json)).isEqualTo("b=2（3~8）");
        assertThat(GateService.failedChecksText(null)).isEmpty();
        assertThat(GateService.failedChecksText("not-json")).isEmpty();
    }

    // ===== ② 修订模板的修复方向 =====

    @Test
    void chapterReviseCarriesMetricFixDirections() {
        String content = PromptCatalog.contentOf(LlmNode.CHAPTER_REVISE, "user");
        assertThat(content).contains("指标修复方向");
        assertThat(content).contains("只增不删");       // 字数不足的方向
        assertThat(content).contains("拆分长句");       // 行均超上限的方向
        assertThat(content).contains("连续对白");       // 密度超限的方向（合并连续对白）
        assertThat(content).contains("禁止只顾一项");   // 多指标组合约束
        // 方向块是纯文本行：占位符个数不得变（formatSafe fail-open 防线）
        assertThat(PromptTemplateService.specs(content)).hasSize(5);
    }

    @Test
    void sceneReviseCarriesDensityFixDirection() {
        String content = PromptCatalog.contentOf(LlmNode.SCENE_REVISE, "user");
        assertThat(content).contains("{gate_feedback}");  // 反馈段仍在（区间格式经 F1 生效）
        assertThat(content).contains("修复方向");
        assertThat(content).contains("合并连续对白");
    }

    // ===== ③ 密度三层回退：gate_config dialogue_density_max → 指纹 → 30.2 =====
    // 章级=主判据（30.2），场景级=1.5×极端护栏（45.3）——2026-10-07 密度实弹上移：
    // 450 字场景单个「≈2.2/千字方差过大，同轮 1 章 5 次卡边；章级 12 个已成稿章 20.7–33.8 稳定。

    /** 密度约 46/千字的对白密集文本（cjk=87、4 个「→ 4*1000/87≈46）。 */
    private static String denseDialogueScene() {
        return "「嗯。」「走。」「来。」「上。」\n"
                + "他点了点头然后转身走向走廊尽头灯光渐渐暗了下去风从舷窗缝隙里挤进来带着咸腥的气味"
                + "舷梯上没有人值班广播里传来一声短促的提示音他放慢脚步确认身后没有跟着任何人才继续往前走";
    }

    private GateService gateWith(String gateConfigJson) {
        StylePackDataService sp = mock(StylePackDataService.class);
        when(sp.findGateConfigByNovel(anyLong())).thenReturn(gateConfigJson);
        when(sp.findFingerprintByNovel(anyLong())).thenReturn(null);
        ChapterDataService chapterData = mock(ChapterDataService.class);
        when(chapterData.findFullText(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);
        TuningService tuning = mock(TuningService.class);
        when(tuning.d(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        when(tuning.i(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        return new GateService(sp, mock(GateReportDataService.class), chapterData, tuning,
                mock(NovelDataService.class));
    }

    private static Double densityAbsMax(GateVerdict v) {
        return v.checks().stream()
                .filter(c -> c.check().equals("dialogue_density_per1k"))
                .map(GateCheck::absMax)
                .map(n -> ((Number) n).doubleValue())
                .findFirst().orElse(null);
    }

    @Test
    void chapterDensityCapFallsBackTo302() {
        // 章级主判据：默认 30.2
        GateVerdict v = gateWith(null).evaluateChapter(7L, 1, denseDialogueScene(), 50, 300);
        assertThat(densityAbsMax(v)).isEqualTo(30.2);
    }

    @Test
    void chapterDensityCapOverridablePerBook() {
        GateVerdict v = gateWith("{\"dialogue_density_max\":50}")
                .evaluateChapter(7L, 1, denseDialogueScene(), 50, 300);
        assertThat(densityAbsMax(v)).isEqualTo(50.0);
        GateCheck density = v.checks().stream()
                .filter(c -> c.check().equals("dialogue_density_per1k")).findFirst().orElseThrow();
        assertThat(density.ok()).isTrue(); // 46 ≤ 50 覆盖后通过
    }

    @Test
    void sceneDensityKeepsLooseTailGuard() {
        // 场景级 = 章级上限 × 1.5：默认 45.3；覆盖 50 → 75
        GateVerdict v = gateWith(null).checkScene(7L, 1L, 1L, 1, denseDialogueScene(), 100);
        assertThat(densityAbsMax(v)).isEqualTo(45.3);
        GateVerdict v2 = gateWith("{\"dialogue_density_max\":50}")
                .checkScene(7L, 1L, 1L, 1, denseDialogueScene(), 100);
        assertThat(densityAbsMax(v2)).isEqualTo(75.0);
    }
}
