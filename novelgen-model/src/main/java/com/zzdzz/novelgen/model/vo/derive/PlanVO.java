package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/**
 * ⑥a 章节计划结果。2026-10-18 夜起改为幂等单元制：
 * unit=SCALE 定规模（章数由 AI 判，不再由字数÷硬常量算）→ WINDOW 逐窗分配拍 → DONE 终局校验。
 */
public record PlanVO(
        long runId,
        long novelId,
        int chapterCount,
        int beatCount,
        int totalBudget,
        List<ChapterPlanItem> chapters,
        String unit,
        int windowIndex,
        int windowTotal,
        int plannedChapters,
        int usedBeats,
        boolean allDone,
        Integer volumeNo,
        int routedBeats,
        List<String> validationWarnings
) {
    public record ChapterPlanItem(int no, String title, int budget, int beatCount, double pacePlan) {
    }

    /** 兼容一次性计划语义的旧构造（外部按五项读的地方不必改）。 */
    public PlanVO(long runId, long novelId, int chapterCount, int beatCount, int totalBudget,
                  List<ChapterPlanItem> chapters) {
        this(runId, novelId, chapterCount, beatCount, totalBudget, chapters,
                "DONE", 1, 1, chapterCount, beatCount, true, null, beatCount, List.of());
    }

    /** 兼容 V1 单元结果的旧构造。 */
    public PlanVO(long runId, long novelId, int chapterCount, int beatCount, int totalBudget,
                  List<ChapterPlanItem> chapters, String unit, int windowIndex,
                  int windowTotal, int plannedChapters, int usedBeats, boolean allDone) {
        this(runId, novelId, chapterCount, beatCount, totalBudget, chapters,
                unit, windowIndex, windowTotal, plannedChapters, usedBeats, allDone,
                null, usedBeats, List.of());
    }
}
