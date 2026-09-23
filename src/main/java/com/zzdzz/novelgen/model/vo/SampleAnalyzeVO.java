package com.zzdzz.novelgen.model.vo;

import java.util.List;

/**
 * 开书向导·导入小说分析结果（纯机械，零 LLM）：
 * 切块即落库（preset_corpus，品类名唯一化——语料是可复用资产，之后随时补料/重提/采纳）
 * → 指纹基线 + 章长预算带 → 与现有品类逐个算相似度 → 复用/新建建议。
 */
public record SampleAnalyzeVO(
        int chunks,
        long totalChars,
        boolean lowConfidence,
        int budgetMin,
        int budgetMax,
        int metricCount,
        String fingerprintJson,
        String genre,
        String recommendation,
        List<PresetSimilarityVO> similarities,
        List<String> notes) {

    /** recommendation 取值：match=与某品类相近可直接用 / new=差异大建议建新品类 / choice=两可。 */
    public record PresetSimilarityVO(long presetId, String name, double score, boolean comparable) {
    }
}
