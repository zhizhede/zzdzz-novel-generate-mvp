package com.zzdzz.novelgen.model.vo.derive;

/** ⑦单章正文生成结果（write 端点每次写一章，断点续跑）。 */
public record WriteVO(
        long runId,
        long novelId,
        int chapterNo,
        int charCount,
        int budget,
        int remaining,
        boolean allDone
) {
}
