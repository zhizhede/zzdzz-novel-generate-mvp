package com.zzdzz.novelgen.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 特征提取纯函数基线：分位/带宽/夹紧口径（阶段三提取管线的数学地基）。 */
class GenrePresetServiceTest {

    @Test
    void percentileInterpolates() {
        assertThat(GenrePresetService.percentile(List.of(1.0, 2.0, 3.0, 4.0), 0.5)).isEqualTo(2.5);
        assertThat(GenrePresetService.percentile(List.of(10.0), 0.9)).isEqualTo(10.0);
        assertThat(GenrePresetService.percentile(List.of(0.0, 10.0), 0.25)).isEqualTo(2.5);
    }

    @Test
    void budgetBandClampedAndRounded() {
        // 稳定样本：带宽按 p15/p85 取整 50，容差夹紧在 0.10-0.35
        double[] band = GenrePresetService.budgetBand(
                List.of(2400.0, 2500.0, 2600.0, 2700.0, 2800.0, 2900.0, 3000.0, 3100.0, 3200.0, 3300.0));
        assertThat(band[0]).isBetween(2000.0, 2600.0);
        assertThat(band[1]).isBetween(2900.0, 3500.0);
        assertThat(band[2]).isBetween(0.10, 0.35);
    }

    @Test
    void smallSampleUsesMinMax() {
        double[] band = GenrePresetService.budgetBand(List.of(1800.0, 2200.0));
        assertThat(band[0]).isEqualTo(1800.0);
        assertThat(band[1]).isEqualTo(2200.0);
    }

    @Test
    void baselineSkipsDeadMetricsAndClampsTolerance() {
        Map<String, Object> baseline = GenrePresetService.buildBaseline(Map.of(
                "dead", List.of(0.0, 0.0, 0.0),
                "flat", List.of(5.0, 5.0, 5.0),
                "wide", List.of(1.0, 2.0, 20.0)));
        assertThat(baseline).containsKeys("flat", "wide").doesNotContainKey("dead");
        @SuppressWarnings("unchecked")
        Map<String, Object> flat = (Map<String, Object>) baseline.get("flat");
        assertThat(flat.get("value")).isEqualTo(5.0);
        assertThat(((Number) flat.get("tolerance")).doubleValue()).isEqualTo(0.15); // 下夹紧
        @SuppressWarnings("unchecked")
        Map<String, Object> wide = (Map<String, Object>) baseline.get("wide");
        assertThat(((Number) wide.get("tolerance")).doubleValue()).isLessThanOrEqualTo(1.5); // 上夹紧
        assertThat(((Number) wide.get("abs_max")).doubleValue()).isGreaterThan(15.0);
    }

    // ===== 开书向导·导入小说分析 =====

    @Test
    void chunkNovelSplitsAtTargetAndStripsNumberLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 900; i++) {
            sb.append("12\n"); // 纯数字行：章号/页码，必须被剔除
            sb.append("这是一段用来凑字数的正文，猫在窗台看雨。").append(i).append("\n");
        }
        List<String> chunks = GenrePresetService.chunkNovel(sb.toString());
        assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
        for (String c : chunks) {
            assertThat(c.length()).isGreaterThanOrEqualTo(500);
            assertThat(c).doesNotContain("\n12\n"); // 数字行没混进去
        }
        long total = chunks.stream().mapToLong(String::length).sum();
        assertThat(total).isGreaterThan(900 * 15L); // 正文都在
    }

    @Test
    void chunkNovelTailMerge() {
        // 长正文 + 短尾巴：尾块并入前块而不是留碎块
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("正文段落线，凑字数用，猫继续看雨第").append(i).append("行\n");
        }
        sb.append("短尾巴\n");
        List<String> chunks = GenrePresetService.chunkNovel(sb.toString());
        assertThat(chunks.get(chunks.size() - 1)).contains("短尾巴");
        assertThat(chunks.get(chunks.size() - 1).length()).isGreaterThan(500);
    }

    private static String fp(String body) {
        return "{\"baseline\":" + body + "}";
    }

    @Test
    void fingerprintSimilarityIdenticalIsOne() {
        String a = fp("{\"m1\":{\"value\":3.0,\"tolerance\":0.5,\"abs_max\":5.0},\"m2\":{\"value\":1.0,\"tolerance\":0.3,\"abs_max\":2.0},\"m3\":{\"value\":8.0,\"tolerance\":0.4,\"abs_max\":12.0}}");
        assertThat(GenrePresetService.fingerprintSimilarity(a, a)).isEqualTo(1.0);
    }

    @Test
    void fingerprintSimilarityDistantIsLow() {
        String a = fp("{\"m1\":{\"value\":3.0,\"tolerance\":0.5,\"abs_max\":5.0},\"m2\":{\"value\":1.0,\"tolerance\":0.3,\"abs_max\":2.0},\"m3\":{\"value\":8.0,\"tolerance\":0.4,\"abs_max\":12.0}}");
        String b = fp("{\"m1\":{\"value\":0.2,\"tolerance\":0.5,\"abs_max\":1.0},\"m2\":{\"value\":6.0,\"tolerance\":0.3,\"abs_max\":9.0},\"m3\":{\"value\":1.0,\"tolerance\":0.4,\"abs_max\":2.0}}");
        assertThat(GenrePresetService.fingerprintSimilarity(a, b)).isLessThan(GenrePresetService.NEW_THRESHOLD);
    }

    @Test
    void fingerprintSimilarityNotComparableWithTooFewCommonMetrics() {
        String a = fp("{\"m1\":{\"value\":3.0,\"tolerance\":0.5,\"abs_max\":5.0},\"m2\":{\"value\":1.0,\"tolerance\":0.3,\"abs_max\":2.0},\"m3\":{\"value\":8.0,\"tolerance\":0.4,\"abs_max\":12.0}}");
        String b = fp("{\"x1\":{\"value\":3.0,\"tolerance\":0.5,\"abs_max\":5.0},\"x2\":{\"value\":1.0,\"tolerance\":0.3,\"abs_max\":2.0},\"x3\":{\"value\":8.0,\"tolerance\":0.4,\"abs_max\":12.0}}");
        assertThat(GenrePresetService.fingerprintSimilarity(a, b)).isEqualTo(-1.0);
    }

    @Test
    void fingerprintSimilarityDeadMetricAsymmetryDiscounts() {
        // 5 指标全同 vs 只有 3 个共有（另外 2 个单侧缺失）：共有分满也要打折
        String full = fp("{\"m1\":{\"value\":3.0,\"tolerance\":0.5,\"abs_max\":5.0},\"m2\":{\"value\":1.0,\"tolerance\":0.3,\"abs_max\":2.0},\"m3\":{\"value\":8.0,\"tolerance\":0.4,\"abs_max\":12.0},\"m4\":{\"value\":2.0,\"tolerance\":0.3,\"abs_max\":3.0},\"m5\":{\"value\":5.0,\"tolerance\":0.2,\"abs_max\":7.0}}");
        String partial = fp("{\"m1\":{\"value\":3.0,\"tolerance\":0.5,\"abs_max\":5.0},\"m2\":{\"value\":1.0,\"tolerance\":0.3,\"abs_max\":2.0},\"m3\":{\"value\":8.0,\"tolerance\":0.4,\"abs_max\":12.0}}");
        assertThat(GenrePresetService.fingerprintSimilarity(full, partial)).isLessThan(1.0);
        assertThat(GenrePresetService.fingerprintSimilarity(full, partial)).isGreaterThan(0.5);
    }

    @Test
    void uniqueGenreNameSuffixesWithoutCollision() {
        java.util.Set<String> taken = new java.util.HashSet<>(List.of("刀剑神域", "刀剑神域·2"));
        assertThat(GenrePresetService.uniqueGenreName("刀剑神域", taken)).isEqualTo("刀剑神域·3");
        assertThat(GenrePresetService.uniqueGenreName("全新品类", taken)).isEqualTo("全新品类");
        // 长名超 64 字时限长再编号
        String longName = "很".repeat(70);
        String got = GenrePresetService.uniqueGenreName(longName, new java.util.HashSet<>());
        assertThat(got.length()).isLessThanOrEqualTo(64);
    }
}
