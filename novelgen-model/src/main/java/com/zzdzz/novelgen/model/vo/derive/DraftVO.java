package com.zzdzz.novelgen.model.vo.derive;

/** ⑥b具体稿结果。 */
public record DraftVO(
        long runId,
        long novelId,
        String title,
        String protagonist,
        int chapterCount,
        int filledCount,
        int skippedCount
) {
}
