package com.zzdzz.novelgen.derive.draft;

import java.util.List;
import java.util.Map;

/** 单场连续性上下文；只含当前场需要的状态，不携带其他场脚本。 */
public record SceneContext(
        long runId,
        int planRevision,
        int chapterNo,
        String priorSummary,
        Map<String, List<String>> stateBefore,
        String styleConstraints) {

    public SceneContext {
        if (runId <= 0 || planRevision <= 0 || chapterNo <= 0) {
            throw new IllegalArgumentException("runId/planRevision/chapterNo must be positive");
        }
        priorSummary = priorSummary == null ? "" : priorSummary;
        stateBefore = stateBefore == null ? Map.of() : Map.copyOf(stateBefore);
        styleConstraints = styleConstraints == null ? "" : styleConstraints;
    }

    /** 兼容不含文风覆盖的最小上下文。 */
    public SceneContext(long runId, int planRevision, int chapterNo,
                        String priorSummary, Map<String, List<String>> stateBefore) {
        this(runId, planRevision, chapterNo, priorSummary, stateBefore, "");
    }
}
