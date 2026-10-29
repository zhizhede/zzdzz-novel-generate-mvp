package com.zzdzz.novelgen.derive.plan.compiler;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.util.List;
import java.util.Objects;

/** 计划编译结果；有硬问题时仍返回结构计划，供审计器精确定位。 */
public record PlanCompileResult(
        CompiledStoryPlan plan,
        List<PlanContracts.PlanIssue> hardIssues,
        List<String> warnings) {

    public PlanCompileResult {
        Objects.requireNonNull(plan, "plan");
        hardIssues = hardIssues == null ? List.of() : List.copyOf(hardIssues);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
