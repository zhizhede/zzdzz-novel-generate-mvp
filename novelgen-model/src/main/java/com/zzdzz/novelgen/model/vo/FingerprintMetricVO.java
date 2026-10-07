package com.zzdzz.novelgen.model.vo;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个指纹指标（web 出入参共用形状）：value=基线值，tolerance=容差，abs_min/abs_max 为显式硬边界（可缺省）。
 * 中文名来自 MetricLabels——指纹相关页面与写作提示共用同一份映射，别在前端再建一份。
 */
public record FingerprintMetricVO(String key, String label, Double value, Double tolerance,
                                  Double absMin, Double absMax) {

    /** 指纹 baseline 节点 → 指标行（保持 JSON 里的键序；非数字字段按 null）。 */
    public static List<FingerprintMetricVO> parse(JsonNode baseline) {
        List<FingerprintMetricVO> out = new ArrayList<>();
        if (baseline == null || !baseline.isObject()) {
            return out;
        }
        baseline.fields().forEachRemaining(e -> {
            JsonNode rule = e.getValue();
            out.add(new FingerprintMetricVO(e.getKey(), MetricLabels.metricLabel(e.getKey()),
                    doubleOrNull(rule, "value"), doubleOrNull(rule, "tolerance"),
                    doubleOrNull(rule, "abs_min"), doubleOrNull(rule, "abs_max")));
        });
        return out;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }
}
