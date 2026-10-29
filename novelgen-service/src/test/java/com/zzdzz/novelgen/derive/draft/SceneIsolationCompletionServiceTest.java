package com.zzdzz.novelgen.derive.draft;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SceneIsolationCompletionServiceTest {

    @Test
    void exhaustedSceneDoesNotPreventLaterSceneAttempt() {
        var snapshotService = mock(com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService.class);
        var runMapper = mock(com.zzdzz.novelgen.derive.mapper.RunMapper.class);
        var novelMapper = mock(com.zzdzz.novelgen.derive.mapper.NovelMapper.class);
        var chapterMapper = mock(com.zzdzz.novelgen.derive.mapper.ChapterMapper.class);
        var beatMapper = mock(com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper.class);
        var retry = mock(SceneRetryPolicy.class);

        CompiledStoryPlan plan = twoScenePlan();
        var snapshot = new com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO();
        snapshot.setRevision(7);
        snapshot.setStatus("ADMITTED");
        org.mockito.Mockito.when(snapshotService.requireActive(59L)).thenReturn(snapshot);
        org.mockito.Mockito.when(snapshotService.loadCompiled(59L, 7)).thenReturn(plan);

        var run = new com.zzdzz.novelgen.model.entity.derive.RunDO();
        run.setId(59L);
        run.setNovelId(12L);
        org.mockito.Mockito.when(runMapper.selectById(59L)).thenReturn(run);
        var novel = new com.zzdzz.novelgen.model.entity.derive.NovelDO();
        novel.setId(12L);
        org.mockito.Mockito.when(novelMapper.selectById(12L)).thenReturn(novel);

        var chapter = new com.zzdzz.novelgen.model.entity.derive.ChapterDO();
        chapter.setNovelId(12L);
        chapter.setChapterNo(1);
        chapter.setBudget(1200);
        chapter.setPlanRevision(7);
        chapter.setStatus("PLANNED");
        org.mockito.Mockito.when(chapterMapper.selectList(any())).thenReturn(List.of(chapter));

        List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> beats = new ArrayList<>();
        for (int sceneNo = 1; sceneNo <= 2; sceneNo++) {
            var beat = new com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO();
            beat.setId(200L + sceneNo);
            beat.setRunId(59L);
            beat.setChapterNo(1);
            beat.setChapterSeq(sceneNo);
            beat.setSeq(sceneNo);
            beat.setBeatKey("B1-S" + sceneNo);
            beat.setPlanRevision(7);
            beat.setStatus("PLANNED");
            beats.add(beat);
        }
        org.mockito.Mockito.when(beatMapper.selectList(any())).thenReturn(beats);

        String good = DraftFixtures.prose(2, 200);
        org.mockito.Mockito.when(retry.writeWithRetry(any(), any(), anyInt()))
                .thenReturn(new SceneRetryPolicy.SceneRetryResult(
                        null, 3, false, List.of("DRAFT_SCENE_EMPTY:第一场耗尽")))
                .thenReturn(new SceneRetryPolicy.SceneRetryResult(
                        new SceneDraft(2, good, good.length()), 1, false, List.of()));

        var service = new ChapterDraftCompletionService(snapshotService, runMapper, novelMapper,
                chapterMapper, beatMapper, retry, new ChapterAssembler());

        assertThrows(SceneDraftException.class, () -> service.completeChapter(59L, 1));
        verify(retry, atLeastOnce()).writeWithRetry(any(), any(), anyInt());
        org.mockito.Mockito.verify(retry).writeWithRetry(
                org.mockito.ArgumentMatchers.eq(new SceneScript(2, "B1-S2", "第二场完成明确收束", 600, List.of(), List.of())),
                any(), anyInt());
    }

    private static CompiledStoryPlan twoScenePlan() {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 1, 1200, List.of("scene-isolation"));
        var scenes = List.of(
                new PlanGraphRecords.PlannedScene(1, "B1-S1", "第一场完成明确推进",
                        List.of(), List.of(), List.of(), List.of()),
                new PlanGraphRecords.PlannedScene(2, "B1-S2", "第二场完成明确收束",
                        List.of(), List.of(), List.of(), List.of()));
        var chapter = new PlanGraphRecords.PlannedChapter(1, 1, "第一章", 1200, scenes);
        List<PlanGraphRecords.PlannedBeat> beats = new ArrayList<>();
        for (int sceneNo = 1; sceneNo <= 2; sceneNo++) {
            beats.add(new PlanGraphRecords.PlannedBeat(
                    sceneNo, "B1-S" + sceneNo, "ASSET", null, null, null, "事件",
                    new BigDecimal("0.5"), 1, 1, sceneNo,
                    PlanContracts.StoryLayer.LOCAL_CASE,
                    PlanContracts.BeatRole.HIGHLIGHT,
                    PlanContracts.AppearanceMode.LIVE,
                    "第" + sceneNo + "场目标明确推进故事",
                    List.of("C1"), List.of(), List.of(), List.of(), List.of(), null,
                    List.of(), List.of()));
        }
        return new CompiledStoryPlan(59L, 1, "scene-isolation", scope,
                List.of(new ChapterScheduleEntry(1, 1, false, List.of(), List.of())),
                List.of(), List.of(), List.of(chapter), beats);
    }
}

