package com.zzdzz.novelgen.derive.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.draft.FullDraftCompletionService;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** DRAFT stage handler：只完成正文；missingChapters 非空时阶段失败。 */
@Component
public class DraftStageHandler implements DeriveStageHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlanSnapshotService snapshotService;
    private final FullDraftCompletionService completionService;

    /** 创建正文阶段 handler。 */
    public DraftStageHandler(PlanSnapshotService snapshotService,
                             FullDraftCompletionService completionService) {
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.completionService = Objects.requireNonNull(completionService);
    }

    @Override
    public DeriveStage supports() {
        return DeriveStage.DRAFT;
    }

    @Override
    public StageOutcome execute(StageClaim claim) {
        var active = snapshotService.requireActive(claim.stageRunId());
        var result = completionService.completeAll(claim.stageRunId());
        if (!result.missingChapters().isEmpty()) {
            int firstMissing = result.missingChapters().get(0);
            String path = "chapter[" + firstMissing + "]";
            return StageOutcome.failed("DRAFT_INCOMPLETE", path,
                    "正文未完成，缺章：" + path + "；缺失列表=" + result.missingChapters());
        }
        String message;
        try {
            message = MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            message = "{\"chaptersDegraded\":" + result.chaptersDegraded() + "}";
        }
        return StageOutcome.done(active.getRevision(), result.chaptersDegraded(), message);
    }
}
