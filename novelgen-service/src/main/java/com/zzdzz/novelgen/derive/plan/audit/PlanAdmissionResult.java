package com.zzdzz.novelgen.derive.plan.audit;

import java.util.List;
import java.util.Objects;

/** 一次计划准入的结果。 */
public record PlanAdmissionResult(
        long runId,
        int revision,
        PlanAdmissionDecision decision,
        PlanAuditReport report) {

    public PlanAdmissionResult {
        if (runId <= 0 || revision <= 0) {
            throw new IllegalArgumentException("runId/revision must be positive");
        }
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(report, "report");
    }
}
