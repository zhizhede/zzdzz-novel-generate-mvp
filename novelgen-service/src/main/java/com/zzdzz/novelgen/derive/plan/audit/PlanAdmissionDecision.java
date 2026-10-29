package com.zzdzz.novelgen.derive.plan.audit;

/** 计划准入决策；只有确定性审计能决定硬熔断。 */
public enum PlanAdmissionDecision {
    ADMIT,
    REPAIR,
    REJECT
}
