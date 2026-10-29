package com.zzdzz.novelgen.derive.draft;

import com.zzdzz.novelgen.derive.plan.audit.PlanAuditIssue;
import java.util.List;
import java.util.Objects;

/** 最终正文审计报告；只读，不修改正文、快照或 active revision。 */
public record FinalAuditReport(
        long runId,
        int planRevision,
        FinalQuality quality,
        List<PlanAuditIssue> issues) {

    public enum FinalQuality { PASS, ADVISORY, DEGRADED, FAILED }

    public FinalAuditReport {
        if (runId <= 0 || planRevision < 0) {
            throw new IllegalArgumentException("runId must be positive and planRevision nonnegative");
        }
        Objects.requireNonNull(quality, "quality");
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
