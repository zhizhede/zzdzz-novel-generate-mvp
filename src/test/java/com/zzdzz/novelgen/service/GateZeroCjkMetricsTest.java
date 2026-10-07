package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.GateService.GateCheck;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 零中文稿的指标兜底（2026-10-07 实弹）：MiniMax 内容审核拒答返回 60 字符英文，
 * computeMetrics 旧实现早退只给 {"cjk":0}，checkScene 取 dialogue_end_punct_ratio 拆箱 NPE → 整章 FAILED。
 * 契约：零中文/空文本返回**全量键零值**——消费方按正常键集取值不炸，
 * 且长度/对白指标全不过 → 走「门禁不过带意见重写」自愈而不是崩。
 */
class GateZeroCjkMetricsTest {

    /** 供应商风控拒答原文（实弹 fixture）。 */
    private static final String REJECTION = "The request was rejected because it was considered high risk";

    private GateService gate;

    @BeforeEach
    void setUp() {
        StylePackDataService stylePackData = mock(StylePackDataService.class);
        when(stylePackData.findFingerprintByNovel(anyLong())).thenReturn(null);
        when(stylePackData.findGateConfigByNovel(anyLong())).thenReturn(null);
        ChapterDataService chapterData = mock(ChapterDataService.class);
        when(chapterData.findFullText(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);
        TuningService tuning = mock(TuningService.class);
        when(tuning.d(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        gate = new GateService(stylePackData, mock(GateReportDataService.class), chapterData, tuning,
                mock(NovelDataService.class));
    }

    @Test
    void zeroCjkReturnsFullKeySet() {
        Map<String, Object> m = GateService.computeMetrics(REJECTION);
        Map<String, Object> normal = GateService.computeMetrics("他推开门，走了进去。外面下着雨。");
        // 键集必须与正常路径一致——少一个键就是一处潜在 NPE
        assertThat(m.keySet()).containsExactlyInAnyOrderElementsOf(normal.keySet());
        assertThat(((Number) m.get("cjk")).intValue()).isZero();
        assertThat(((Number) m.get("dialogue_end_punct_ratio")).doubleValue()).isZero();
        assertThat(((Number) m.get("simile_per1k")).doubleValue()).isZero();
    }

    @Test
    void emptyTextReturnsFullKeySet() {
        Map<String, Object> m = GateService.computeMetrics("");
        assertThat(m).containsKey("dialogue_end_punct_ratio");
        assertThat(((Number) m.get("cjk")).intValue()).isZero();
    }

    @Test
    void sceneGateOnRejectionTextFailsGracefullyNotNpe() {
        // 拒答稿必须「优雅不过」（触发重写），而不是 NPE 炸章
        assertThatCode(() -> gate.checkScene(1L, 1L, 1L, 1, REJECTION, 900))
                .doesNotThrowAnyException();
        GateService.GateVerdict v = gate.checkScene(1L, 1L, 1L, 1, REJECTION, 900);
        assertThat(v.passed()).isFalse(); // 长度/对白不过 → 自愈入口
        assertThat(v.failedChecks()).isNotEmpty();
    }

    @Test
    void chapterLengthNeverPassesOnZeroCjk() {
        // 零稿的长度必然不过 = 章级门禁「自愈入口」的前提
        Map<String, Object> m = GateService.computeMetrics(REJECTION);
        int cjk = ((Number) m.get("cjk")).intValue();
        assertThat(cjk).isLessThan(1);
    }

    @Test
    void sceneGateNeededKeysAllPresent() {
        Map<String, Object> m = GateService.computeMetrics(REJECTION);
        List<String> needBySceneGate = List.of("cjk", "line_avg_len", "dunhao_per1k",
                "exclam_per1k", "digit_per1k", "dialogue_end_punct_ratio", "dialogue_density_per1k");
        for (String k : needBySceneGate) {
            assertThat(m).containsKey(k);
            assertThat(((Number) m.get(k)).doubleValue()).isNotNaN();
        }
    }
}
