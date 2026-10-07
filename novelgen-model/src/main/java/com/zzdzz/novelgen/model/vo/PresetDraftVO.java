package com.zzdzz.novelgen.model.vo;

import java.util.List;

/** 特征提取草稿（纯机械指标，零 LLM 成本）：指纹带宽 + 章长预算带，人工过目后采纳为预设。
 *  规模自适应：n&lt;10 标低置信（不拒绝——量级用户定，导入多少算多少）。 */
public record PresetDraftVO(String genre, int chapters, boolean lowConfidence,
                            int budgetMin, int budgetMax, double chapterLengthTolerance,
                            int metricCount, String fingerprintJson, List<String> notes) {
}
