package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveSyntheticGoalTest {

    @Test
    void syntheticGoalContainsChapterTargetAndNoRepeatInstruction() {
        var chapter = new PlanContracts.ChapterSlotContract(
                5, "MAIN_ADVANCE", "核验函件", "锁定火灾区域",
                "C1追查", "关系推进", List.of(PlanContracts.BeatRole.MAIN_ADVANCE),
                List.of(), Map.of(), Map.of());

        String goal = DeriveBeatRouteService.syntheticGoal(
                chapter, PlanContracts.BeatRole.MAIN_ADVANCE);

        assertTrue(goal.contains("第5章"));
        assertTrue(goal.contains("锁定火灾区域"));
        assertTrue(goal.contains("禁止复述"));
    }

    @Test
    void syntheticLabelIsExposedToDraftWindow() {
        BeatInstanceDO beat = new BeatInstanceDO();
        beat.setSeq(26);
        beat.setSource("SYNTHETIC");
        beat.setRouteContract("{\"goal\":\"推进第5章新证据，禁止复述函件调包\"}");

        assertTrue(DeriveDraftService.syntheticLabel(beat).contains("禁止复述"));
    }
}