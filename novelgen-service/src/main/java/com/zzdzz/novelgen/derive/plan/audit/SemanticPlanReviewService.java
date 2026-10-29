package com.zzdzz.novelgen.derive.plan.audit;

import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import java.util.List;

/**
 * 可选的主观语义审稿扩展。
 *
 * <p>其结果只能成为 ADVISORY；它没有修改计划、合同、日程或准入决策的权限。
 */
@FunctionalInterface
public interface SemanticPlanReviewService {

    /** 对已编译计划给出只读语义建议。 */
    List<PlanAuditIssue> review(CompiledStoryPlan plan);
}
