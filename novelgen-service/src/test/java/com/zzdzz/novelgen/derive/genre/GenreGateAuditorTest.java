package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import com.zzdzz.novelgen.derive.story.StoryThread;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenreGateAuditorTest {

    @Test
    void auditorRejectsOpenRequiredThread() {
        StoryKernel kernel = new StoryKernel(1L, 1, "ACTIVE", null, List.of(), List.of(),
                List.of(), List.of(new StoryThread("T1", "RELATIONSHIP", "关系", 1,
                        "OPEN", "C1", 4, Map.of())), List.of(), List.of(), List.of(), List.of());

        assertTrue(hasCode(new GenreGateAuditor().audit(kernel, new MysteryProfile()), "THREAD_OPEN"));
    }

    @Test
    void auditorRejectsMissingFinalClosure() {
        StoryKernel kernel = new StoryKernel(1L, 1, "ACTIVE", null, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertTrue(hasCode(new GenreGateAuditor().audit(kernel, new MysteryProfile()),
                "FINAL_CLOSURE_MISSING"));
    }

    private static boolean hasCode(List<PlanContracts.PlanIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.code()));
    }
}
