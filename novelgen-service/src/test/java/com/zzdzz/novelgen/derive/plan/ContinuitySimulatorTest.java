package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.derive.plan.compiler.ContinuitySimulator;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuitySimulatorTest {

    @Test
    void knowledgeCannotBeRequiredBeforeItsSource() {
        var contract = PlanCompilerFixtures.contract(59L, 12, "复仇掩盖账目");
        var beats = List.of(
                beat(1, 1, PlanContracts.BeatRole.CASE_SETUP, List.of("F-01"), List.of(),
                        List.of(), List.of()),
                beat(2, 2, PlanContracts.BeatRole.MAIN_ADVANCE, List.of(), List.of("F-01"),
                        List.of(), List.of()));

        var result = new ContinuitySimulator().simulate(contract, beats);

        assertTrue(hasCode(result, "PLAN_KNOWLEDGE_SOURCE_MISSING"), result.issues().toString());
    }

    @Test
    void knowledgeWriteTargetsMustBelongToBeatFocus() {
        var contract = PlanCompilerFixtures.contract(59L, 12, "复仇掩盖账目");
        var beat = beat(1, 1, PlanContracts.BeatRole.CLUE_PLANT,
                List.of(), List.of("F-01"),
                List.of(new PlanGraphRecords.KnowledgeEffect(List.of("C2"), "F-01")),
                List.of());

        var result = new ContinuitySimulator().simulate(contract, List.of(beat));

        assertTrue(hasCode(result, "PLAN_KNOWLEDGE_SOURCE_MISSING"), result.issues().toString());
    }

    @Test
    void cluePayoffBeforePlantOrTwiceIsRejected() {
        var contract = PlanCompilerFixtures.contract(59L, 12, "复仇掩盖账目");
        var beats = List.of(
                beat(1, 1, PlanContracts.BeatRole.CLUE_PAYOFF, List.of(), List.of(),
                        List.of(), List.of("GM-CLUE-01")),
                beat(2, 3, PlanContracts.BeatRole.CLUE_PAYOFF, List.of(), List.of(),
                        List.of(), List.of("GM-CLUE-01")));

        var result = new ContinuitySimulator().simulate(contract, beats);

        assertTrue(hasCode(result, "PLAN_CLUE_PAYOFF_MISSING"), result.issues().toString());
    }

    @Test
    void deadCharacterCannotAppearLiveLater() {
        var contract = PlanCompilerFixtures.contract(59L, 12, "复仇掩盖账目");
        var beats = List.of(
                beat(1, 1, PlanContracts.BeatRole.LOCAL_CLOSURE, List.of("C1"), List.of(),
                        List.of(new PlanGraphRecords.StateEffect(
                                "C1", PlanContracts.StateChangeKind.DEAD, "死亡")),
                        List.of()),
                beat(2, 2, PlanContracts.BeatRole.MAIN_ADVANCE, List.of("C1"), List.of(),
                        List.of(), List.of()));

        var result = new ContinuitySimulator().simulate(contract, beats);

        assertTrue(hasCode(result, "PLAN_STATE_DEPENDENCY"), result.issues().toString());
    }

    @Test
    void echoBeforeOpenIsRejectedAndSimulationIsRepeatable() {
        var contract = PlanCompilerFixtures.contract(59L, 12, "复仇掩盖账目");
        var beat = beat(1, 1, PlanContracts.BeatRole.CLUE_ECHO, List.of("C1"), List.of(),
                List.of(), List.of("VC-01"));
        var simulator = new ContinuitySimulator();

        var first = simulator.simulate(contract, List.of(beat));
        var second = simulator.simulate(contract, List.of(beat));

        assertTrue(hasCode(first, "PLAN_THREAD_OPEN"), first.issues().toString());
        assertTrue(first.chapterStates().equals(second.chapterStates()));
        assertTrue(first.knowledgeEvents().equals(second.knowledgeEvents()));
        assertTrue(first.threadEvents().equals(second.threadEvents()));
        assertTrue(first.issues().equals(second.issues()));
    }

    private static boolean hasCode(ContinuitySimulator.SimulationResult result, String code) {
        return result.issues().stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static PlanGraphRecords.PlannedBeat beat(
            int seq,
            int chapterNo,
            PlanContracts.BeatRole role,
            List<String> requires,
            List<String> writes,
            List<?> effects,
            List<String> threadRefs) {
        List<PlanGraphRecords.KnowledgeEffect> knowledge = new java.util.ArrayList<>();
        List<PlanGraphRecords.StateEffect> states = new java.util.ArrayList<>();
        for (Object effect : effects) {
            if (effect instanceof PlanGraphRecords.KnowledgeEffect value) {
                knowledge.add(value);
            } else if (effect instanceof PlanGraphRecords.StateEffect value) {
                states.add(value);
            }
        }
        return new PlanGraphRecords.PlannedBeat(
                seq, "B" + seq, "SYNTHETIC", null, null, null, "事件",
                new BigDecimal("0.50"), chapterNo, 1, 1,
                PlanContracts.StoryLayer.LOCAL_CASE, role,
                PlanContracts.AppearanceMode.LIVE, "推进目标",
                List.of("C1"), threadRefs, knowledge, states,
                List.of(), "", requires, writes);
    }
}


