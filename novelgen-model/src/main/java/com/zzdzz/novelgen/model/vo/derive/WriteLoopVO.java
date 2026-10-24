package com.zzdzz.novelgen.model.vo.derive;

/**
 * ⑦ 服务端连跑状态（2026-10-18 根修：点一次按钮由服务器逐章写到完，关页面不断）。
 * phase/chars/idleMs 来自 DeriveActivity（当前章的实时生成心跳）；chaptersDone/lastError 为循环账。
 */
public record WriteLoopVO(
        long runId,
        boolean running,
        boolean stopRequested,
        boolean allDone,
        int chaptersDone,
        Integer lastChapterNo,
        long lastChapterMs,
        String lastError,
        long elapsedMs,
        String phase,
        long chars,
        long idleMs
) {
}
