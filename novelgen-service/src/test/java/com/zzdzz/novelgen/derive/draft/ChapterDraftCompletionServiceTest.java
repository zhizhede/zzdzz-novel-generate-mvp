package com.zzdzz.novelgen.derive.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterDraftCompletionServiceTest {

    @Mock
    private PlanSnapshotService snapshotService;
    @Mock
    private RunMapper runMapper;
    @Mock
    private NovelMapper novelMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private BeatInstanceMapper beatInstanceMapper;
    @Mock
    private SceneWriter sceneWriter;

    private CompiledStoryPlan plan;
    private ChapterDraftCompletionService service;

    @BeforeEach
    void setUp() {
        plan = DraftFixtures.plan(59L, 3);
        service = new ChapterDraftCompletionService(snapshotService, runMapper, novelMapper,
                chapterMapper, beatInstanceMapper, new SceneRetryPolicy(sceneWriter),
                new ChapterAssembler());
        when(snapshotService.requireActive(59L)).thenReturn(snapshot(3));
        when(snapshotService.loadCompiled(59L, 3)).thenReturn(plan);
        RunDO run = new RunDO();
        run.setId(59L);
        run.setNovelId(12L);
        run.setActivePlanRevision(3);
        when(runMapper.selectById(59L)).thenReturn(run);
        NovelDO novel = new NovelDO();
        novel.setId(12L);
        when(novelMapper.selectById(12L)).thenReturn(novel);
    }

    @Test
    void persistsCompletedChapterTextWithRevisionGuards() {
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(1, null)));
        when(beatInstanceMapper.selectList(any())).thenReturn(beats(1, null));
        when(sceneWriter.write(any(), any(), any(), any()))
                .thenReturn(scene(DraftFixtures.prose(1, 300)));
        when(beatInstanceMapper.updateFilledIfRevision(
                eq(59L), eq("B1-S1"), eq(3), anyString(), eq("WRITTEN"))).thenReturn(1);
        when(chapterMapper.updateContentIfRevision(
                eq(12L), eq(1), eq(3), anyString(), anyInt(), eq("DRAFTED"))).thenReturn(1);

        ChapterDraftResult result = service.completeChapter(59L, 1);

        assertTrue(result.chars() > 100);
        assertFalse(result.degraded());
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(chapterMapper).updateContentIfRevision(
                eq(12L), eq(1), eq(3), content.capture(), anyInt(), eq("DRAFTED"));
        assertTrue(content.getValue().contains("林澈"));
        verify(snapshotService, never()).admit(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void resumingChapterReusesCompletedScenes() {
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(1, null)));
        when(beatInstanceMapper.selectList(any())).thenReturn(beats(1, "已完成的场景正文内容足够长。"));
        when(beatInstanceMapper.updateFilledIfRevision(
                eq(59L), eq("B1-S1"), eq(3), anyString(), eq("WRITTEN"))).thenReturn(1);
        when(chapterMapper.updateContentIfRevision(
                eq(12L), eq(1), eq(3), anyString(), anyInt(), eq("DRAFTED"))).thenReturn(1);

        ChapterDraftResult result = service.completeChapter(59L, 1);

        assertTrue(result.chars() > 0);
        verify(sceneWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void staleSceneRevisionCannotOverwriteText() {
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(1, null)));
        when(beatInstanceMapper.selectList(any())).thenReturn(beats(1, null));
        when(sceneWriter.write(any(), any(), any(), any()))
                .thenReturn(scene(DraftFixtures.prose(1, 300)));
        when(beatInstanceMapper.updateFilledIfRevision(
                eq(59L), eq("B1-S1"), eq(3), anyString(), eq("WRITTEN"))).thenReturn(0);

        BizException error = assertThrows(BizException.class,
                () -> service.completeChapter(59L, 1));

        assertTrue(error.getMessage().contains("DRAFT_PLAN_REVISION_STALE"));
        verify(chapterMapper, never()).updateContentIfRevision(
                anyLong(), anyInt(), anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    void staleChapterRevisionCannotOverwriteText() {
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(1, null)));
        when(beatInstanceMapper.selectList(any())).thenReturn(beats(1, null));
        when(sceneWriter.write(any(), any(), any(), any()))
                .thenReturn(scene(DraftFixtures.prose(1, 300)));
        when(beatInstanceMapper.updateFilledIfRevision(anyLong(), anyString(), anyInt(),
                anyString(), anyString())).thenReturn(1);
        when(chapterMapper.updateContentIfRevision(anyLong(), anyInt(), anyInt(),
                anyString(), anyInt(), anyString())).thenReturn(0);

        BizException error = assertThrows(BizException.class,
                () -> service.completeChapter(59L, 1));

        assertTrue(error.getMessage().contains("DRAFT_PLAN_REVISION_STALE"));
    }

    @Test
    void sceneFailureDoesNotChangePlanRevisionOrPersistDraft() {
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(1, null)));
        when(beatInstanceMapper.selectList(any())).thenReturn(beats(1, null));
        when(sceneWriter.write(any(), any(), any(), any()))
                .thenThrow(new SceneDraftException("DRAFT_SCENE_EMPTY", "空输出"));

        assertThrows(SceneDraftException.class, () -> service.completeChapter(59L, 1));

        verify(beatInstanceMapper, never()).updateFilledIfRevision(
                anyLong(), anyString(), anyInt(), anyString(), anyString());
        verify(chapterMapper, never()).updateContentIfRevision(
                anyLong(), anyInt(), anyInt(), anyString(), anyInt(), anyString());
        verify(snapshotService, never()).admit(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void alreadyCompleteChapterIsIdempotent() {
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(1, "已有正文")));
        when(beatInstanceMapper.selectList(any())).thenReturn(beats(1, "已有正文"));

        ChapterDraftResult result = service.completeChapter(59L, 1);

        assertEquals(4, result.chars());
        verify(sceneWriter, never()).write(any(), any(), any(), any());
    }

    private DerivePlanSnapshotDO snapshot(int revision) {
        DerivePlanSnapshotDO row = new DerivePlanSnapshotDO();
        row.setRunId(59L);
        row.setRevision(revision);
        row.setStatus("ADMITTED");
        row.setPlanHash(plan.canonicalHash());
        row.setGraph(plan.canonicalJson());
        return row;
    }

    private static ChapterDO chapter(int no, String content) {
        ChapterDO row = new ChapterDO();
        row.setId(100L + no);
        row.setNovelId(12L);
        row.setChapterNo(no);
        row.setTitle("第" + no);
        row.setBudget(1200);
        row.setStatus("PLANNED");
        row.setPlanRevision(3);
        row.setContent(content);
        return row;
    }

    private static List<BeatInstanceDO> beats(int chapterNo, String filled) {
        List<BeatInstanceDO> rows = new ArrayList<>();
        BeatInstanceDO beat = new BeatInstanceDO();
        beat.setId(200L + chapterNo);
        beat.setRunId(59L);
        beat.setChapterNo(chapterNo);
        beat.setSeq(chapterNo);
        beat.setBeatKey("B" + chapterNo + "-S1");
        beat.setPlanRevision(3);
        beat.setStatus(filled == null ? "PLANNED" : "WRITTEN");
        beat.setFilledText(filled);
        rows.add(beat);
        return rows;
    }

    private static SceneDraft scene(String text) {
        return new SceneDraft(1, text, text.replaceAll("\\s", "").length());
    }

    private static void assertFalse(boolean value) {
        org.junit.jupiter.api.Assertions.assertFalse(value);
    }
}
