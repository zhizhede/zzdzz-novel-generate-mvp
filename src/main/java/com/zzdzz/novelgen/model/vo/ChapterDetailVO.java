package com.zzdzz.novelgen.model.vo;

/** 章详情聚合：正文 + 场景明细 + 最新章级门禁 + AI 审校 + 本章 LLM 用量汇总。 */
public record ChapterDetailVO(Long id, int chapterNo, String title, String status, int round,
                              int budgetMin, int budgetMax, String fullText,
                              java.util.List<SceneVO> scenes,
                              GateReportVO gateReport,
                              ReviewVO review,
                              LlmTotalsVO llmTotals) {
}
