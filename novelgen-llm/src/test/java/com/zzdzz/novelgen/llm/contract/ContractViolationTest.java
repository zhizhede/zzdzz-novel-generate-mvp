package com.zzdzz.novelgen.llm.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContractViolationTest {

    @Test
    void feedbackLineIncludesCodePathExpectedAndActual() {
        ContractViolation violation = new ContractViolation(
                "SCHEMA_ENUM_INVALID",
                "assignments[4].beatRole",
                "枚举非法",
                "HOOK|...|FINAL_CLOSURE",
                "OBSTACLE");

        String feedbackLine = violation.feedbackLine();

        assertTrue(feedbackLine.contains("SCHEMA_ENUM_INVALID"));
        assertTrue(feedbackLine.contains("assignments[4].beatRole"));
        assertTrue(feedbackLine.contains("HOOK|...|FINAL_CLOSURE"));
        assertTrue(feedbackLine.contains("OBSTACLE"));
    }
}
