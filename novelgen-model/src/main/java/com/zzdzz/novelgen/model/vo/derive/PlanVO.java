package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** ⑥a章节计划结果。 */
public record PlanVO(
        long runId,
        long novelId,
        int chapterCount,
        int beatCount,
        int totalBudget,
        List<ChapterPlanItem> chapters
) {
    public record ChapterPlanItem(int no, String title, int budget, int beatCount, double pacePlan) {
    }
}
