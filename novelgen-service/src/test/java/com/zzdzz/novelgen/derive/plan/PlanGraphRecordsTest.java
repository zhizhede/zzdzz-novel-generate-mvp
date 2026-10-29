package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计划图记录的不可变性、连续编号与稳定序列化契约。 */
class PlanGraphRecordsTest {

    @Test
    void plannedChapterCopiesScenesAndRequiresContinuousSceneNumbers() {
        var mutableScenes = new ArrayList<>(List.of(scene(1, "B1")));

        var chapter = new PlanGraphRecords.PlannedChapter(1, 1, "第一章", 2000, mutableScenes);
        mutableScenes.clear();

        assertEquals(1, chapter.scenes().size());
        assertThrows(IllegalArgumentException.class,
                () -> new PlanGraphRecords.PlannedChapter(1, 1, "断号", 2000,
                        List.of(scene(1, "B1"), scene(3, "B3"))));
    }

    @Test
    void compiledPlanCopiesCollectionsAndRejectsBrokenBeatSequence() {
        var chapters = new ArrayList<>(List.of(chapter(1)));
        var beats = new ArrayList<>(List.of(beat(1, 1, 1)));

        var plan = plan(chapters, beats);
        chapters.clear();
        beats.clear();

        assertEquals(1, plan.chapters().size());
        assertEquals(1, plan.beats().size());
        assertThrows(IllegalArgumentException.class,
                () -> plan(List.of(chapter(1)), List.of(beat(2, 1, 1))));
    }

    @Test
    void snapshotJsonRoundTripsAndCanonicalHashIsStable() {
        var plan = plan(List.of(chapter(1)), List.of(beat(1, 1, 1)));

        String first = plan.canonicalJson();
        String second = plan.canonicalJson();
        CompiledStoryPlan decoded = CompiledStoryPlan.fromJson(first);

        assertEquals(first, second);
        assertEquals(plan.canonicalHash(), decoded.canonicalHash());
        assertEquals(plan, decoded);
        assertTrue(plan.canonicalHash().matches("[0-9a-f]{64}"));
    }

    private static CompiledStoryPlan plan(List<PlanGraphRecords.PlannedChapter> chapters,
                                          List<PlanGraphRecords.PlannedBeat> beats) {
        return new CompiledStoryPlan(
                59L,
                1,
                "contract-hash",
                new PlanContracts.ScopeContract(
                        PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 1, 2000, List.of("fixture")),
                List.of(),
                List.of(),
                List.of(),
                chapters,
                beats);
    }

    private static PlanGraphRecords.PlannedChapter chapter(int chapterNo) {
        return new PlanGraphRecords.PlannedChapter(chapterNo, 1, "第" + chapterNo + "章", 2000,
                List.of(scene(1, "B" + chapterNo)));
    }

    private static PlanGraphRecords.PlannedScene scene(int sceneNo, String beatKey) {
        return new PlanGraphRecords.PlannedScene(sceneNo, beatKey, "目标",
                List.of(), List.of(), List.of(), List.of());
    }

    private static PlanGraphRecords.PlannedBeat beat(int seq, int chapterNo, int sceneNo) {
        return new PlanGraphRecords.PlannedBeat(
                seq, "B" + seq, "SYNTHETIC", null, null, null, "事件", new BigDecimal("0.50"),
                chapterNo, 1, sceneNo, PlanContracts.StoryLayer.LOCAL_CASE,
                PlanContracts.BeatRole.CASE_SETUP, PlanContracts.AppearanceMode.LIVE, "目标",
                List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of());
    }
}