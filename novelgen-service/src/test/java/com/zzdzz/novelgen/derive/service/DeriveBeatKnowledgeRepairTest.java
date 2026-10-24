package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 拍路由的知识因果确定性修复。 */
class DeriveBeatKnowledgeRepairTest {

    @Test
    void laterReferenceAddsMissingCharacterBackToFirstDiscoveryBeat() {
        PlanContracts.BeatAssignment discovery = assignment(
                1, 2, List.of("C1"), List.of(), List.of("门底擦痕方向异常"));
        PlanContracts.BeatAssignment echo = assignment(
                2, 5, List.of("C1", "C3"), List.of("门底擦痕方向异常"), List.of());
        PlanContracts.RoutePlan plan = new PlanContracts.RoutePlan(
                1, List.of(), List.of(echo, discovery), List.of(), List.of());

        PlanContracts.RoutePlan repaired =
                DeriveBeatRouteService.repairKnowledgeCausality(plan);

        assertEquals(List.of("C1", "C3"),
                repaired.assignments().get(0).focusCharacters());
        assertEquals(List.of("C1", "C3"),
                repaired.assignments().get(1).focusCharacters());
    }

    @Test
    void missingDiscoveryBeatIsNotFabricated() {
        PlanContracts.BeatAssignment echo = assignment(
                2, 5, List.of("C3"), List.of("未知线索"), List.of());
        PlanContracts.RoutePlan plan = new PlanContracts.RoutePlan(
                1, List.of(), List.of(echo), List.of(), List.of());

        PlanContracts.RoutePlan repaired =
                DeriveBeatRouteService.repairKnowledgeCausality(plan);

        assertTrue(repaired.assignments().get(0).writesKnowledge().isEmpty());
        assertEquals(List.of("C3"), repaired.assignments().get(0).focusCharacters());
    }

    private static PlanContracts.BeatAssignment assignment(
            int seq, int chapterNo, List<String> focus,
            List<String> requiresKnowledge, List<String> writesKnowledge) {
        return new PlanContracts.BeatAssignment(
                seq, chapterNo, PlanContracts.StoryLayer.LOCAL_CASE,
                PlanContracts.BeatRole.CLUE_ECHO, focus, List.of(),
                List.of(), "", PlanContracts.AppearanceMode.LIVE,
                requiresKnowledge, writesKnowledge, Map.of(), Map.of());
    }
}
