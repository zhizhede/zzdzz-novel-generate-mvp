package com.zzdzz.novelgen.derive.story;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualProbeBudgetGuardTest {

    @Test
    void currentWindowOverBudgetRejects() {
        WordBudgetGuard.BudgetDecision decision =
                WordBudgetGuard.check(5100, 1000, 0, 800, 3000, 5000);

        assertFalse(decision.ok());
        assertEquals("BUDGET_WINDOW_OVERSHOOT", decision.code());
        assertEquals("COMPRESS_WINDOW", decision.repairTarget());
    }

    @Test
    void projectedOverBudgetRejects() {
        WordBudgetGuard.BudgetDecision decision =
                WordBudgetGuard.check(2500, 1000, 5, 800, 3000, 5000);

        assertFalse(decision.ok());
        assertEquals("BUDGET_PROJECTED_OVERSHOOT", decision.code());
        assertEquals("COMPRESS_REMAINING_WINDOWS", decision.repairTarget());
    }

    @Test
    void intermediateProjectedUnderBudgetIsAdvisory() {
        WordBudgetGuard.BudgetDecision decision =
                WordBudgetGuard.check(500, 300, 5, 100, 3000, 5000);

        assertEquals("BUDGET_PROJECTED_UNDERSHOOT", decision.code());
        assertEquals("ADVISORY", decision.severity());
        assertTrue(decision.ok());
    }

    @Test
    void finalProjectedUnderBudgetRejects() {
        WordBudgetGuard.BudgetDecision decision =
                WordBudgetGuard.check(2500, 300, 0, 100, 3000, 5000);

        assertFalse(decision.ok());
        assertEquals("BUDGET_FINAL_UNDERSHOOT", decision.code());
        assertEquals("EXPAND_WINDOW", decision.repairTarget());
    }

    @Test
    void validBudgetAccepts() {
        WordBudgetGuard.BudgetDecision decision =
                WordBudgetGuard.check(1800, 900, 4, 700, 3000, 5000);

        assertTrue(decision.ok());
        assertEquals("OK", decision.code());
        assertTrue(decision.projectedMax() <= 5000);
    }
}