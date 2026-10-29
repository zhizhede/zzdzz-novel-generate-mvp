package com.zzdzz.novelgen.derive.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** COMPLETE stage：正文与终检均完成，只落 ledger 终态并绑定当前 active revision。 */
@Component
public class CompleteStageHandler implements DeriveStageHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlanSnapshotService snapshotService;

    /** 创建 COMPLETE stage handler。 */
    public CompleteStageHandler(PlanSnapshotService snapshotService) {
        this.snapshotService = Objects.requireNonNull(snapshotService);
    }

    @Override
    public DeriveStage supports() {
        return DeriveStage.COMPLETE;
    }

    @Override
    public StageOutcome execute(StageClaim claim) {
        int revision = snapshotService.requireActive(claim.stageRunId()).getRevision();
        String message;
        try {
            message = MAPPER.writeValueAsString(java.util.Map.of(
                    "state", "DRAFT_COMPLETE", "planRevision", revision));
        } catch (Exception e) {
            message = "DRAFT_COMPLETE";
        }
        return StageOutcome.done(revision, 0, message);
    }
}
