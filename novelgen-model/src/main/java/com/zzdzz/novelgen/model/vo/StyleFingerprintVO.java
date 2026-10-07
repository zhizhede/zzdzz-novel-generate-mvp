package com.zzdzz.novelgen.model.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 文风指纹库一行（文风指纹页列表）：三个来源统一成一种形状——
 * SAMPLE=导入样本（导入即提取的分析快照）、PRESET=品类预设（语料机械提取采纳）、BOOK=书籍风格包（开书克隆/导入）。
 * metrics 已按指纹 baseline 展开并附中文名（口径与门禁同源，见 MetricLabels）。
 */
public record StyleFingerprintVO(
        String source,
        long refId,
        String name,
        String stylePackName,
        String genre,
        String description,
        Integer chunks,
        Long totalChars,
        Integer metricCount,
        Integer budgetMin,
        Integer budgetMax,
        Double lengthTolerance,
        Boolean lowConfidence,
        String recommendation,
        Long sampleId,
        Long presetId,
        String presetName,
        Long novelId,
        OffsetDateTime createTime,
        OffsetDateTime updateTime,
        boolean hasFingerprint,
        List<FingerprintMetricVO> metrics,
        String fingerprintJson,
        List<String> notes,
        List<SimilarityVO> similarities,
        List<String> tags) {

    /** 样本与现有预设的相似度（提取时的建议依据）。 */
    public record SimilarityVO(long presetId, String name, double score, boolean comparable) {
    }
}
