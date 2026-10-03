package com.zzdzz.novelgen.model.vo;

import java.util.List;

/**
 * 「按本书正文提指纹」草稿（纯机械指标、零 LLM、不落库）：概览 + 指纹 JSON + 章长带 + 提示。
 * fingerprintJson 由前端在采纳时原样回传（所见即所得，避免草稿与落库两次计算出现差异）。
 */
public record BookFingerprintDraftVO(
        long novelId,
        String title,
        /** 统计用到的章数（有正文的章）。 */
        int chapterCount,
        /** 统计用到的正文总汉字数。 */
        long totalChars,
        /** 章数 <10 即低置信（与品类语料提取同一口径）。 */
        boolean lowConfidence,
        int budgetMin,
        int budgetMax,
        double chapterLengthTolerance,
        int metricCount,
        List<FingerprintMetricVO> metrics,
        String fingerprintJson,
        List<String> notes) {
}
