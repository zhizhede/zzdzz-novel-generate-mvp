package com.zzdzz.novelgen.model.vo;

/** 生成队列任务：状态 ∈ QUEUED|RUNNING|DONE|STOPPED|CANCELED，进度 = doneChapters/totalChapters。 */
public record GenerationTaskVO(long id, String novelTitle, int fromChapter, int toChapter,
                               String status, int doneChapters, int totalChapters,
                               Integer currentChapter, String lastMessage, String createTime) {
}
