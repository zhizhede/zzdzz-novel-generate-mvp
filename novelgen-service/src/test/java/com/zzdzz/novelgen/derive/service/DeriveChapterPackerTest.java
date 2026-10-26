package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase2：全局拍池机械装箱，章节归属不得由模型决定。 */
class DeriveChapterPackerTest {

    @Test
    void emptyChapterReceivesMovableBeat() {
        PlanContracts.RoutePlan packed = DeriveChapterPacker.pack(plan(
                assignment(1, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(2, 1, PlanContracts.BeatRole.CLUE_ECHO),
                assignment(3, 1, PlanContracts.BeatRole.CLUE_PLANT)));

        assertEquals(1, packed.assignments().stream()
                .filter(a -> a.chapterNo() == 2).count());
    }

    @Test
    void protectedClosureIsNeverMoved() {
        PlanContracts.RoutePlan packed = DeriveChapterPacker.pack(plan(
                assignment(1, 1, PlanContracts.BeatRole.HOOK),
                assignment(2, 1, PlanContracts.BeatRole.LOCAL_CLOSURE),
                assignment(3, 1, PlanContracts.BeatRole.CLUE_ECHO)));

        assertTrue(packed.assignments().stream()
                .filter(a -> "LOCAL_CLOSURE".equals(a.beatRole().name()))
                .allMatch(a -> a.chapterNo() == 1));
    }

    @Test
    void seqIsPreservedDuringPacking() {
        PlanContracts.RoutePlan packed = DeriveChapterPacker.pack(plan(
                assignment(7, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(8, 1, PlanContracts.BeatRole.CLUE_ECHO)));

        assertTrue(packed.assignments().stream()
                .anyMatch(a -> a.seq() == 7 && a.chapterNo() == 2));
    }

    private static PlanContracts.RoutePlan plan(PlanContracts.BeatAssignment... beats) {
        return new PlanContracts.RoutePlan(1, List.of(
                slot(1), slot(2)), List.of(beats), List.of(), List.of());
    }

    private static PlanContracts.ChapterSlotContract slot(int no) {
        return new PlanContracts.ChapterSlotContract(no, "推进", "局部目标", "主线推进",
                "主角行动", "关系推进", List.of(), List.of(), Map.of(), Map.of());
    }

    private static PlanContracts.BeatAssignment assignment(
            int seq, int chapter, PlanContracts.BeatRole role) {
        return new PlanContracts.BeatAssignment(seq, chapter, PlanContracts.StoryLayer.MAIN_MYSTERY,
                role, List.of("C1"), List.of(), List.of(), "", PlanContracts.AppearanceMode.LIVE,
                List.of(), List.of(), Map.of(), Map.of());
    }
}
