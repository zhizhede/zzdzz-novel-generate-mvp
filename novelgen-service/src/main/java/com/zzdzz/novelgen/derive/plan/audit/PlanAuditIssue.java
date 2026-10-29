package com.zzdzz.novelgen.derive.plan.audit;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.util.List;
import java.util.Objects;

/** 可定位到计划图路径的确定性审计问题。 */
public record PlanAuditIssue(
        String code,
        Severity severity,
        String path,
        Integer chapterNo,
        String beatKey,
        PlanContracts.RepairScope repairScope,
        String message,
        String expected,
        String actual) {

    public PlanAuditIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        path = path == null || path.isBlank() ? "plan" : path;
        repairScope = repairScope == null ? PlanContracts.RepairScope.NONE : repairScope;
        message = message == null ? "" : message;
        expected = expected == null ? "" : expected;
        actual = actual == null ? "" : actual;
    }

    /** 兼容计划层 PlanIssue 的稳定转换。 */
    public static PlanAuditIssue from(PlanContracts.PlanIssue issue, String path,
                                      String beatKey, String expected, String actual) {
        return new PlanAuditIssue(
                issue.code(),
                Severity.valueOf(issue.level()),
                path,
                issue.chapterNo(),
                beatKey,
                issue.repairScope(),
                issue.message(),
                expected,
                actual);
    }
}
