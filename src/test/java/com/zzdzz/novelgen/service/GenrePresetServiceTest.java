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
}
