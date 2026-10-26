package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.vo.derive.GateCriterionVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P6 Task5：directive 空白时自动生成并跨回炉保留。 */
class DeriveGateDirectiveTest {

    @Test
    void blankDirectiveIsGeneratedFromIssues() {
        String directive = DeriveGateService.buildDirective(
                List.of("第4章与第5章章纲重复", "第16章拍负载过高"), "DRAFT");

        assertTrue(directive.contains("按 TruthContract 修复以下问题"));
        assertTrue(directive.contains("第4章与第5章章纲重复"));
        assertTrue(directive.contains("repairTarget=DRAFT"));
    }

    @Test
    void emptyIssuesYieldEmptyDirective() {
        assertEquals("", DeriveGateService.buildDirective(List.of(), "DRAFT"));
        assertEquals("", DeriveGateService.buildDirective(null, null));
    }

    @Test
    void generatedDirectiveIsPersistedAcrossGateReset() {
        String directive = DeriveGateService.buildDirective(
                List.of("persona 禁止私闯但章纲安排独自重返"), "DRAFT");
        String rework = DeriveGateService.withGate(
                "{}", "REWORK", 3, List.of("persona 禁止私闯但章纲安排独自重返"),
                directive, "DRAFT");

        String reset = DeriveGateService.gateReset(rework);

        assertEquals(directive, DeriveGateService.gateDirective(reset),
                "自动生成的 directive 必须跨 ⑥b 回炉保留");
        assertEquals(3, DeriveGateService.gateRound(reset), "轮次不得清零");
        assertEquals("DRAFT", DeriveGateService.repairTarget(reset));
    }

    @Test
    void decideGateKeepsExhaustionLogic() {
        var failed = new GateCriterionVO(
                "closure", false, "FIXABLE", "案情悬空", List.of("缺 culprit"), "DRAFT");
        var review = new DeriveGateService.GateReview(
                List.of(failed), List.of("问题"), "");
        var decision = DeriveGateService.decideGate(review, null, 5);

        assertEquals("FAILED", decision.action());
        assertEquals("REJECT", decision.verdict());
        assertFalse("PASS".equals(decision.action()));
    }
}