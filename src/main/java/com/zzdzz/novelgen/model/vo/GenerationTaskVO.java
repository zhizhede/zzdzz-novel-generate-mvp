package com.zzdzz.novelgen.model.vo;

/** 生成队列任务：状态 ∈ QUEUED|RUNNING|DONE|STOPPED|CANCELED|INTERRUPTED|PAUSED，进度 = doneChapters/totalChapters。
 * kind=CHAPTERS 逐章生成 / PLAN 卷纲规划（范围列语义不同，前端区分展示）；retryCount 为失败自动重试已用次数。 */
public record GenerationTaskVO(long id, String novelTitle, String kind, int fromChapter, int toChapter,
                               String status, int doneChapters, int totalChapters,
                               Integer currentChapter, String lastMessage, String createTime,
                               String currentStep, Long chapterTokens, int retryCount) {
}
