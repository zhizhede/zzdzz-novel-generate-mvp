package com.zzdzz.novelgen.model.vo;

/** 章详情聚合：正文 + 场景明细 + 步骤状态 + 最新章级门禁 + AI 审校 + 本章 LLM 用量汇总 + 生成时评审标准快照
 * + 打回意见/失败摘要/digest 进行中（流 A/流 B 一屏答案）。 */
public record ChapterDetailVO(Long id, int chapterNo, String title, String status, int round,
                              int budgetMin, int budgetMax, String fullText,
                              java.util.List<SceneVO> scenes,
                              GateReportVO gateReport,
                              ReviewVO review,
                              LlmTotalsVO llmTotals,
                              String reviewConfig,
                              java.util.List<ChapterStepVO> steps,
                              String rejectReason,
                              String failureBrief,
                              boolean digestRunning) {
}
