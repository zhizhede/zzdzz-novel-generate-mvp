package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryClosure;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import com.zzdzz.novelgen.derive.story.StoryThread;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysteryGateAuditorTest {

    @Test
    void auditorAcceptsClosedKernel() {
        StoryKernel kernel = kernel(List.of(
                new StoryThread("GM-CLUE-01", "CLUE", "纸屑", 1, "CLOSED", "C1", 3, Map.of())),
                List.of(closure("GM-CLUE-01", 3, true)),
                List.of());

        assertTrue(new MysteryGateAuditor().audit(kernel, new MysteryProfile()).isEmpty());
    }

    @Test
    void auditorRejectsOpenRequiredThread() {
        StoryKernel kernel = kernel(List.of(
                new StoryThread("GM-CLUE-01", "CLUE", "纸屑", 1, "OPEN", "C1", 3, Map.of())),
                List.of(), List.of());

        assertTrue(hasCode(new MysteryGateAuditor().audit(kernel, new MysteryProfile()),
                "THREAD_OPEN"));
    }

    @Test
    void auditorRejectsMissingFinalClosure() {
        StoryKernel kernel = kernel(List.of(), List.of(), List.of());

        assertTrue(hasCode(new MysteryGateAuditor().audit(kernel, new MysteryProfile()),
                "FINAL_CLOSURE_MISSING"));
    }

    @Test
    void auditorRejectsMysteryTruthMismatch() {
        StoryKernel kernel = kernel(List.of(), List.of(), List.of(
                new StoryObligation("CH3:PAYOFF_THREAD:GM-CLUE-01", 3, "PAYOFF_THREAD",
                        "GM-CLUE-01", true, false, "HARD", "MYSTERY", Map.of())));

        assertTrue(hasCode(new MysteryGateAuditor().audit(kernel, new MysteryProfile()),
                "OBLIGATION_UNFULFILLED"));
    }

    private static StoryKernel kernel(List<StoryThread> threads, List<StoryClosure> closures,
                                      List<StoryObligation> obligations) {
        return new StoryKernel(1L, 1, "ACTIVE", null, List.of(), List.of(), List.of(), threads,
                List.of(), obligations, List.of(), closures);
    }

    private static StoryClosure closure(String target, int chapter, boolean required) {
        return new StoryClosure("K" + target, "FINAL_CLOSURE", target, chapter, required, List.of());
    }

    private static boolean hasCode(List<PlanContracts.PlanIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.code()));
    }
}
