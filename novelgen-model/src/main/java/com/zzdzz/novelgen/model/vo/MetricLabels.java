package com.zzdzz.novelgen.model.vo;

import java.util.Map;

/**
 * 指纹指标中文名（未收录的键回退键名本身）：写作提示与文风指纹页共用这一份口径，勿在他处另建映射。
 * 放 model 层是因为 VO（FingerprintMetricVO）与门禁（GateService）要共用，两边都不能引对面。
 */
public final class MetricLabels {

    private static final Map<String, String> METRIC_LABELS = Map.ofEntries(
            Map.entry("line_avg_len", "每行平均长度（字）"),
            Map.entry("dash_per1k", "破折号"),
            Map.entry("ellipsis_per1k", "省略号"),
            Map.entry("exclam_per1k", "感叹号"),
            Map.entry("question_per1k", "问号"),
            Map.entry("dunhao_per1k", "顿号"),
            Map.entry("digit_per1k", "阿拉伯数字"),
            Map.entry("simile_per1k", "比喻"),
            Map.entry("dialogue_density_per1k", "对白行"),
            Map.entry("dialogue_end_punct_ratio", "对白句末标点占比"),
            // 口头禅类指标按「口头禅『X』」命名：这三项是现有风格包里实际出现的全部（少了会以键名裸奔到界面上）
            Map.entry("tic_haiyou_per1k", "口头禅「还有」"),
            Map.entry("tic_laizhe_per1k", "口头禅「来着」"),
            Map.entry("tic_shunbian_per1k", "口头禅「顺便」"));

    private MetricLabels() {
    }

    public static String metricLabel(String key) {
        return METRIC_LABELS.getOrDefault(key, key);
    }
}
