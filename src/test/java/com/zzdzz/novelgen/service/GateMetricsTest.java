package com.zzdzz.novelgen.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GateService 静态纯函数行为基线：门禁重构（期 4 Tuning 收编）前先锁指标口径。 */
class GateMetricsTest {

    @Test
    void emptyTextYieldsZeroCjkOnly() {
        Map<String, Object> m = GateService.computeMetrics("   \n  \n");
        assertThat(m.get("cjk")).isEqualTo(0);
        assertThat(m.size()).isEqualTo(1); // 空文本只有 cjk 键，无除零指标
    }

    @Test
    void metricsHandComputed() {
        // 两行：8 个 CJK 字；「×1、像×1 → per1k = 1000/8 = 125
        String text = "「喵。」咱家说。\n像风一样。";
        Map<String, Object> m = GateService.computeMetrics(text);
        assertThat(m.get("cjk")).isEqualTo(8);
        assertThat(m.get("dialogue_density_per1k")).isEqualTo(125.0);
        assertThat(m.get("simile_per1k")).isEqualTo(125.0);
        assertThat(m.get("line_avg_len")).isEqualTo(6.5); // (8+5)/2
    }

    @Test
    void simileCountsAllFourMarkers() {
        String text = "像风仿佛雨如同雷好似电"; // 11 个 CJK
        double per1k = 1000.0 / 11;
        Map<String, Object> m = GateService.computeMetrics(text);
        assertThat(m.get("simile_per1k")).isEqualTo(Math.round(4 * per1k * 100.0) / 100.0);
    }

    @Test
    void dashAndEllipsisCountedAsPairs() {
        // ——（两个 U+2014）与 ……（两个 U+2026）各出现一次且不计入 CJK；8 个汉字 → per1k = 125
        String text = "咱家走了——好远……的路";
        Map<String, Object> m = GateService.computeMetrics(text);
        assertThat(m.get("cjk")).isEqualTo(8);
        assertThat(m.get("dash_per1k")).isEqualTo(125.0);
        assertThat(m.get("ellipsis_per1k")).isEqualTo(125.0);
    }

    @Test
    void dialogueEndPunctRatioFullWhenAllPunctuated() {
        String text = "「喵。」\n「喵。」";
        Map<String, Object> m = GateService.computeMetrics(text);
        assertThat(m.get("dialogue_end_punct_ratio")).isEqualTo(1.0);
    }

    @Test
    void dialogueEndPunctRatioZeroWhenNoClosingLine() {
        String text = "「喵。」咱家说。\n叙述一行";
        Map<String, Object> m = GateService.computeMetrics(text);
        assertThat(m.get("dialogue_end_punct_ratio")).isEqualTo(0.0);
    }

    @Test
    void openingOverlapZeroWhenDifferent() {
        int n = GateService.openingOverlap("新的一行\n另一行\n第三行", "旧的结尾\n倒数第二\n倒数第三");
        assertThat(n).isEqualTo(0);
    }

    @Test
    void openingOverlapCountsOnlyPrevTailThree() {
        // prev 5 行只取末 3 行（C/D/E）做窗口；cur 前 3 行命中 C、D 两行
        String prev = "A\nB\nC\nD\nE";
        String cur = "C\nD\nF";
        assertThat(GateService.openingOverlap(cur, prev)).isEqualTo(2);
    }

    @Test
    void openingOverlapFullCopyDetectsThree() {
        String prev = "风从巷口来。\n雪没有停。\n咱家蹲着。";
        assertThat(GateService.openingOverlap(prev + "\n新的内容", prev)).isEqualTo(3);
    }

    @Test
    void openingOverlapIgnoresBlankLines() {
        String prev = "A\n\nB\nC\n";
        String cur = "B\nC\nD";
        assertThat(GateService.openingOverlap(cur, prev)).isEqualTo(2);
    }

    @Test
    void openingOverlapEmptyPrevious() {
        assertThat(GateService.openingOverlap("任意\n内容", "")).isEqualTo(0);
    }
}
