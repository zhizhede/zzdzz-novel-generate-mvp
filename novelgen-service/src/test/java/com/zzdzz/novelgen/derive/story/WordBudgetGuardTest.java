package com.zzdzz.novelgen.derive.story;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordBudgetGuardTest {

    @Test
    void overBudgetRejects() {
        WordBudgetGuard.BudgetDecision decision = WordBudgetGuard.check(
                5200, 1200, 2, 1000, 3000, 5000);

        assertEquals("BUDGET_WINDOW_OVERSHOOT", decision.code());
        assertEquals("COMPRESS_WINDOW", decision.repairTarget());
    }

    @Test
    void projectedOverBudgetRejects() {
        WordBudgetGuard.BudgetDecision decision = WordBudgetGuard.check(
                3500, 900, 2, 1000, 3000, 5000);

        assertEquals("BUDGET_PROJECTED_OVERSHOOT", decision.code());
        assertEquals("COMPRESS_REMAINING_WINDOWS", decision.repairTarget());
    }

    @Test
    void projectedUnderBudgetRejects() {
        WordBudgetGuard.BudgetDecision decision = WordBudgetGuard.check(
                2500, 500, 3, 50, 3000, 5000);

        assertEquals("BUDGET_WINDOW_UNDERSHOOT", decision.code());
        assertEquals("EXPAND_WINDOW", decision.repairTarget());
    }

    @Test
    void validBudgetAccepts() {
        WordBudgetGuard.BudgetDecision decision = WordBudgetGuard.check(
                2500, 700, 2, 1000, 3000, 5000);

        assertTrue(decision.ok());
        assertFalse(decision.code().startsWith("BUDGET_"));
        assertEquals(2500 + (int) Math.floor(2 * 1000 * 0.9), decision.projectedMin());
        assertEquals(2500 + (int) Math.ceil(2 * 1000 * 1.1), decision.projectedMax());
    }
}
