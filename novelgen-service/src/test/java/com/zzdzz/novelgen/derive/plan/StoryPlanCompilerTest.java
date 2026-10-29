package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompileInput;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompilerSupport;
import com.zzdzz.novelgen.derive.plan.compiler.StoryPlanCompiler;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryPlanCompilerTest {

    @Test
    void twelveAndFifteenChapterFixturesCompileCompletePlans() {
        for (int chapterCount : List.of(12, 15)) {
            var input = PlanCompilerFixtures.input(chapterCount == 12 ? 59L : 60L, chapterCount);

            var result = new StoryPlanCompiler().compile(input);

            assertEquals(chapterCount, result.plan().chapters().size());
            assertTrue(result.plan().chapters().stream()
                    .allMatch(chapter -> !chapter.scenes().isEmpty()));
            assertTrue(result.hardIssues().isEmpty(), result.hardIssues().toString());
            assertTrue(result.plan().beats().stream()
                    .anyMatch(beat -> "SYN-FINAL-REVEAL".equals(beat.beatKey())));
            assertTrue(result.plan().beats().stream()
                    .anyMatch(beat -> beat.beatKey().startsWith("SYN-FINAL-CLOSURE-")));
        }
    }

    @Test
    void compilerUsesOnlyContractKeysAndStableHash() {
        var firstInput = PlanCompilerFixtures.input(59L, 12);
        var secondInput = PlanCompilerFixtures.input(59L, 12);
        var compiler = new StoryPlanCompiler();

        var first = compiler.compile(firstInput).plan();
        var second = compiler.compile(secondInput).plan();

        assertEquals(first.canonicalHash(), second.canonicalHash());
        StoryContract contract = firstInput.contract();
        Set<String> cast = new HashSet<>(contract.castKeys());
        Set<String> threads = new HashSet<>();
        contract.threads().forEach(thread -> threads.add(thread.threadKey()));
        Set<String> clues = new HashSet<>();
        contract.clues().forEach(clue -> clues.add(clue.clueKey()));

        assertTrue(first.beats().stream()
                .allMatch(beat -> beat.focusCharacters().stream().allMatch(cast::contains)));
        assertTrue(first.beats().stream()
                .allMatch(beat -> beat.threadRefs().stream()
                        .allMatch(ref -> threads.contains(ref) || clues.contains(ref))));
    }

    @Test
    void continuityProblemsAreReturnedAsIssuesNotExceptions() {
        var input = new PlanCompileInput(
                59L, 1, "wrong-hash", inputScope(12),
                PlanCompilerFixtures.contract(59L, 12, "复仇掩盖账目"),
                PlanCompilerFixtures.scaffold(12),
                PlanCompilerFixtures.schedule(12),
                List.of(), List.of(), List.of(),
                new java.math.BigDecimal("0.50"));

        var result = new StoryPlanCompiler().compile(input);

        assertFalse(result.hardIssues().isEmpty());
        assertTrue(result.hardIssues().stream()
                .anyMatch(issue -> "PLAN_CONTRACT_DIGEST_MISMATCH".equals(issue.code())));
        assertTrue(result.hardIssues().stream()
                .anyMatch(issue -> "PLAN_SCRIPT_TARGET_MISSING".equals(issue.code())));
        assertEquals(12, result.plan().chapters().size());
    }

    private static PlanContracts.ScopeContract inputScope(int chapterCount) {
        return new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, chapterCount, 2000,
                List.of("fixture"));
    }
}

