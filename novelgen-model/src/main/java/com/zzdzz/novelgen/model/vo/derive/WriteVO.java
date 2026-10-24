package com.zzdzz.novelgen.model.vo.derive;

/** ⑦单章正文生成结果（write 端点每次写一章，断点续跑）。
 *  gateCleared/lastIssue（修2，2026-10-16）：3 轮输出 gate 是否全过——
 *  false=耗尽后按最优稿静默落库（旧口径只在服务端 WARN，调用方看到的仍是 ok），交⑧硬判。 */
public record WriteVO(
        long runId,
        long novelId,
        int chapterNo,
        int charCount,
        int budget,
        int remaining,
        boolean allDone,
        boolean gateCleared,
        String lastIssue
) {
}
