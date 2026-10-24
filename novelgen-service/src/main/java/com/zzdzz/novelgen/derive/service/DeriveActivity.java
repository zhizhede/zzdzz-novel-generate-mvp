package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.vo.derive.DraftProgressVO;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 衍生长任务实时活动（⑥b/闸/⑦/⑧ 共用，2026-10-18「运行过程」透明化）。
 * 内存态：runId → 当前单元；进程重启即失（历史过程看 llm_call_log 时间线）。
 */
final class DeriveActivity {

    private static final ConcurrentHashMap<Long, Act> MAP = new ConcurrentHashMap<>();

    private DeriveActivity() {
    }

    static final class Act {
        final String phase;
        final long startedMs = System.currentTimeMillis();
        volatile long lastBeatMs = startedMs;
        volatile int frames;
        volatile long chars;

        Act(String phase) {
            this.phase = phase;
        }
    }

    static void begin(long runId, String phase) {
        MAP.put(runId, new Act(phase));
    }

    static void touch(long runId, String piece) {
        Act a = MAP.get(runId);
        if (a == null) {
            return;
        }
        a.frames++;
        if (piece != null) {
            a.chars += piece.length();
        }
        a.lastBeatMs = System.currentTimeMillis();
    }

    static void end(long runId) {
        MAP.remove(runId);
    }

    static DraftProgressVO snapshot(long runId) {
        Act a = MAP.get(runId);
        if (a == null) {
            return new DraftProgressVO(runId, false, "", 0, 0, 0, 0);
        }
        long now = System.currentTimeMillis();
        return new DraftProgressVO(runId, true, a.phase, a.frames, a.chars,
                now - a.startedMs, now - a.lastBeatMs);
    }
}
