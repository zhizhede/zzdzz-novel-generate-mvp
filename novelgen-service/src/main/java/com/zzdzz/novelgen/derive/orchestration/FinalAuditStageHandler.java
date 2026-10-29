package com.zzdzz.novelgen.derive.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.draft.FinalAuditReport;
import com.zzdzz.novelgen.derive.draft.FinalAuditService;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditIssue;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** FINAL_AUDIT stage handler：只读审计；FAILED 或 BLOCKER 报告才让阶段失败。 */
@Component
public class FinalAuditStageHandler implements DeriveStageHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FinalAuditService auditService;

    /** 创建终检阶段 handler。 */
    public FinalAuditStageHandler(FinalAuditService auditService) {
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public DeriveStage supports() {
        return DeriveStage.FINAL_AUDIT;
    }

    @Override
    public StageOutcome execute(StageClaim claim) {
        FinalAuditReport report = auditService.audit(claim.stageRunId());
        boolean blocker = report.issues().stream()
                .anyMatch(issue -> issue.severity().name().equals("BLOCKER"));
        if (blocker) {
            String path = report.issues().stream()
                    .filter(issue -> issue.severity().name().equals("BLOCKER"))
                    .map(PlanAuditIssue::path)
                    .collect(Collectors.joining(","));
            return StageOutcome.failed("FINAL_AUDIT_BLOCKER", path,
                    report.issues().stream()
                            .filter(issue -> issue.severity().name().equals("BLOCKER"))
                            .map(PlanAuditIssue::message)
                            .collect(Collectors.joining("；")));
        }
        if (report.quality() == FinalAuditReport.FinalQuality.FAILED) {
            return StageOutcome.failed("FINAL_AUDIT_FAILED", "run[" + claim.stageRunId() + "]",
                    "最终审计未通过：" + report.quality().name());
        }
        String message;
        try {
            message = MAPPER.writeValueAsString(report);
        } catch (Exception e) {
            message = report.quality().name();
        }
        return StageOutcome.done(report.planRevision(),
                report.quality() == FinalAuditReport.FinalQuality.DEGRADED ? 1 : 0,
                message);
    }
}
