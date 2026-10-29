package com.zzdzz.novelgen.derive.draft;

import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 全书正文闭环：按章号升序完成，单章失败局部重试后继续，终检由下一 stage 触发。 */
@Service
public class FullDraftCompletionService {

    private static final int MAX_CHAPTER_ATTEMPTS = 2;

    private final PlanSnapshotService snapshotService;
    private final ChapterDraftCompletionService chapterService;

    /** 创建全书正文完成服务。 */
    public FullDraftCompletionService(PlanSnapshotService snapshotService,
                                      ChapterDraftCompletionService chapterService) {
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.chapterService = Objects.requireNonNull(chapterService);
    }

    /** 完成 active snapshot 的全部计划章；不修改计划、不运行终检。 */
    public DraftCompletionResult completeAll(long runId) {
        DerivePlanSnapshotDO active = snapshotService.requireActive(runId);
        CompiledStoryPlan plan = snapshotService.loadCompiled(runId, active.getRevision());
        int expected = plan.chapters().size();
        int completed = 0;
        int degraded = 0;
        List<Integer> missing = new ArrayList<>();
        for (PlanGraphRecords.PlannedChapter planned : plan.chapters()) {
            int chapterNo = planned.chapterNo();
            if (chapterService.chapterComplete(runId, chapterNo)) {
                completed++;
                continue;
            }
            ChapterDraftResult result = null;
            for (int attempt = 1; attempt <= MAX_CHAPTER_ATTEMPTS; attempt++) {
                try {
                    result = chapterService.completeChapter(runId, chapterNo);
                    break;
                } catch (SceneDraftException ignored) {
                    // 场景级失败只在本章局部重试；其他运行时异常原样上抛。
                }
            }
            if (result == null) {
                missing.add(chapterNo);
                continue;
            }
            completed++;
            degraded += result.degraded() ? 1 : 0;
        }
        snapshotService.requireActive(runId, active.getRevision());
        return new DraftCompletionResult(active.getRevision(), expected, completed,
                degraded, List.copyOf(missing));
    }

    /** 全书正文完成读数；只汇总，不触发 FINAL_AUDIT。 */
    public record DraftCompletionResult(
            int planRevision,
            int chaptersExpected,
            int chaptersCompleted,
            int chaptersDegraded,
            List<Integer> missingChapters) {

        public DraftCompletionResult {
            missingChapters = missingChapters == null ? List.of() : List.copyOf(missingChapters);
        }
    }
}
