package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.derive.plan.compiler.BeatAssignmentSolver;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeatAssignmentSolverTest {

    @Test
    void contractPlantPayoffRevealAndClosureAreLockedToContractChapters() {
        var input = PlanCompilerFixtures.input(59L, 12);

        var result = new BeatAssignmentSolver().solve(
                input.contract(), input.scope(), input.scaffold(), input.schedule(),
                input.candidates(), input.defaultPace());
        Map<String, PlanGraphRecords.PlannedBeat> byKey = result.beats().stream()
                .collect(Collectors.toMap(PlanGraphRecords.PlannedBeat::beatKey,
                        Function.identity(), (left, right) -> left));

        assertEquals(2, byKey.get("SYN-CLUE-PLANT-GM-CLUE-01").chapterNo());
        assertEquals(10, byKey.get("SYN-CLUE-PAYOFF-GM-CLUE-01").chapterNo());
        assertEquals(11, byKey.get("SYN-FINAL-REVEAL").chapterNo());
        assertEquals(12, byKey.get("SYN-FINAL-CLOSURE-12").chapterNo());
        assertTrue(result.issues().isEmpty(), result.issues().toString());
    }

    @Test
    void forbiddenCandidateIsSkippedWithDomainReason() {
        var input = PlanCompilerFixtures.input(59L, 12);
        var forbidden = new PlanGraphRecords.BeatCandidate(
                "B-DEATH", "ASSET", 91L, null, null, 3,
                "死亡现场", "中段", new BigDecimal("0.50"), List.of());
        var candidates = new java.util.ArrayList<>(input.candidates());
        candidates.add(forbidden);

        var result = new BeatAssignmentSolver().solve(
                input.contract(), input.scope(), input.scaffold(), input.schedule(),
                candidates, input.defaultPace());

        assertTrue(result.beats().stream().noneMatch(beat -> "B-DEATH".equals(beat.beatKey())));
        assertTrue(result.skipped().stream()
                .anyMatch(skip -> "B-DEATH".equals(skip.beatKey())
                        && skip.reason().contains("死亡")));
    }

    @Test
    void everyCandidateIsAssignedOrSkippedExactlyOnceAndOrderingIsDeterministic() {
        var input = PlanCompilerFixtures.input(60L, 15);
        var solver = new BeatAssignmentSolver();

        var first = solver.solve(input.contract(), input.scope(), input.scaffold(),
                input.schedule(), input.candidates(), input.defaultPace());
        var second = solver.solve(input.contract(), input.scope(), input.scaffold(),
                input.schedule(), input.candidates(), input.defaultPace());

        assertEquals(first, second);
        assertEquals(first.beats(), second.beats());
        for (var candidate : input.candidates()) {
            long assigned = first.beats().stream()
                    .filter(beat -> candidate.beatKey().equals(beat.beatKey())).count();
            long skipped = first.skipped().stream()
                    .filter(skip -> candidate.beatKey().equals(skip.beatKey())).count();
            assertEquals(1, assigned + skipped, candidate.beatKey());
        }
        for (int index = 0; index < first.beats().size(); index++) {
            assertEquals(index + 1, first.beats().get(index).seq());
        }
    }

    @Test
    void dependenciesAreRewrittenToEarlierFinalSeq() {
        var contract = PlanCompilerFixtures.contract(59L, 12, "复仇掩盖账目");
        var candidates = new java.util.ArrayList<PlanGraphRecords.BeatCandidate>();
        candidates.add(new PlanGraphRecords.BeatCandidate(
                "B02", "ASSET", 2L, null, null, 2, "事件", "中段",
                new BigDecimal("0.50"), List.of(1)));
        candidates.add(new PlanGraphRecords.BeatCandidate(
                "B01", "ASSET", 1L, null, null, 1, "事件", "中段",
                new BigDecimal("0.50"), List.of()));

        var result = new BeatAssignmentSolver().solve(
                contract, contract.scope(), PlanCompilerFixtures.scaffold(12),
                PlanCompilerFixtures.schedule(12), candidates, new BigDecimal("0.50"));

        var beat = result.beats().stream()
                .filter(row -> "B02".equals(row.beatKey())).findFirst().orElseThrow();
        var dependency = result.beats().stream()
                .filter(row -> row.seq() == beat.dependsOn().get(0)).findFirst().orElseThrow();
        assertEquals("B01", dependency.beatKey());
        assertTrue(dependency.seq() < beat.seq());
    }
}
