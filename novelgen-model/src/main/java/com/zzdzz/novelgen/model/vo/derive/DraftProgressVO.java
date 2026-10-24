package com.zzdzz.novelgen.model.vo.derive;

/**
 * ⑥b 服务端进行中进度（透明化，2026-10-18）：前端轮询展示，防「盲等 10 分钟只看到转圈」。
 * idleMs=距最近一个流式增量的毫秒数——持续增大且 frames 不涨＝模型卡流。
 */
public record DraftProgressVO(
        long runId,
        boolean active,
        String phase,
        int frames,
        long chars,
        long elapsedMs,
        long idleMs
) {
}
