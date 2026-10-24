package com.zzdzz.novelgen.model.vo.derive;

/** ⑥b具体稿结果（v3.7 幂等单元制：unit=HEAD 做头 / BATCH 做一窗 / DONE 终局完成）。 */
public record DraftVO(
        long runId,
        long novelId,
        String title,
        String protagonist,
        int chapterCount,
        int filledCount,
        int skippedCount,
        String unit,
        int batchIndex,
        int batchTotal,
        boolean allDone
) {
}
