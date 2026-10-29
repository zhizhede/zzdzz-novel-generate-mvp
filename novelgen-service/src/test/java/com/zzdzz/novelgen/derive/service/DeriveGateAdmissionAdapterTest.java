package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionDecision;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditIssue;
import com.zzdzz.novelgen.derive.plan.audit.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeriveGateAdmissionAdapterTest {

    @Test
    void admissionDecisionMapsToLegacyGateVerdicts() {
        var repair = DeriveGateService.gateDecisionFor(
                PlanAdmissionDecision.REPAIR, List.of(issue()), 0);
        var admit = DeriveGateService.gateDecisionFor(
                PlanAdmissionDecision.ADMIT, List.of(), 0);
        var reject = DeriveGateService.gateDecisionFor(
                PlanAdmissionDecision.REJECT, List.of(issue()), 0);

        assertEquals("REWORK", repair.verdict());
        assertEquals("REWORK", repair.action());
        assertEquals("GO", admit.verdict());
        assertEquals("PASS", admit.action());
        assertEquals("REJECT", reject.verdict());
        assertEquals("FAILED", reject.action());
    }

    private static PlanAuditIssue issue() {
        return new PlanAuditIssue("PLAN_CLOSURE_MISSING", Severity.BLOCKER,
                "plan.beats[SYN-FINAL-CLOSURE-12]", 12, "SYN-FINAL-CLOSURE-12",
                PlanContracts.RepairScope.NONE, "缺最终闭合", "", "");
    }
}
