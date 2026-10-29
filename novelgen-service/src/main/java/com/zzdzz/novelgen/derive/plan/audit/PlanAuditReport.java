package com.zzdzz.novelgen.derive.plan.audit;

import java.util.List;
import java.util.Objects;

/** 计划审计报告；无 BLOCKER 才允许进入 admission。 */
public record PlanAuditReport(List<PlanAuditIssue> issues) {

    public PlanAuditReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 是否满足确定性准入的最低条件。 */
    public boolean admitted() {
        return issues.stream().noneMatch(issue -> issue.severity() == Severity.BLOCKER);
    }
}
