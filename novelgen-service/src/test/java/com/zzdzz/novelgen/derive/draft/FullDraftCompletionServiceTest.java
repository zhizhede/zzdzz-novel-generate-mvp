package com.zzdzz.novelgen.derive.draft;

import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FullDraftCompletionServiceTest {

    @Mock
    private PlanSnapshotService snapshotService;
    @Mock
    private ChapterDraftCompletionService chapterService;

    private CompiledStoryPlan plan;

    @BeforeEach
    void setUp() {
        plan = DraftFixtures.plan(59L, 12);
        DerivePlanSnapshotDO snapshot = new DerivePlanSnapshotDO();
        snapshot.setRevision(4);
        snapshot.setStatus("ADMITTED");
        when(snapshotService.requireActive(59L)).thenReturn(snapshot);
        when(snapshotService.loadCompiled(59L, 4)).thenReturn(plan);
    }

    @Test
    void completesEveryChapterAndCountsTwelveNonemptyChapters() {
        when(chapterService.chapterComplete(anyLong(), anyInt())).thenReturn(false);
        when(chapterService.completeChapter(anyLong(), anyInt()))
                .thenAnswer(inv -> result(inv.getArgument(1), false));

        FullDraftCompletionService.DraftCompletionResult result = service(12).completeAll(59L);

        assertEquals(12, result.chaptersExpected());
        assertEquals(12, result.chaptersCompleted());
        assertEquals(0, result.missingChapters().size());
        verify(chapterService, times(12)).completeChapter(anyLong(), anyInt());
    }

    @Test
    void sceneDraftExceptionIsRetriedThenRecorded() {
        setUpThreeChapterPlan();
        when(chapterService.chapterComplete(anyLong(), anyInt())).thenReturn(false);
        when(chapterService.completeChapter(anyLong(), anyInt()))
                .thenAnswer(inv -> {
                    int chapterNo = inv.getArgument(1);
                    if (chapterNo == 2) {
                        throw new SceneDraftException("DRAFT_SCENE_EMPTY", "第二章没有可用场景");
                    }
                    return result(chapterNo, false);
                });

        FullDraftCompletionService.DraftCompletionResult result = service(3).completeAll(59L);

        assertEquals(2, result.chaptersCompleted());
        assertEquals(List.of(2), result.missingChapters());
        verify(chapterService, times(2)).completeChapter(anyLong(), org.mockito.ArgumentMatchers.eq(2));
        verify(chapterService).completeChapter(anyLong(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void degradedChapterDoesNotStopLaterChapters() {
        setUpThreeChapterPlan();
        when(chapterService.chapterComplete(anyLong(), anyInt())).thenReturn(false);
        when(chapterService.completeChapter(anyLong(), anyInt()))
                .thenAnswer(inv -> result(inv.getArgument(1),
                        ((Integer) inv.getArgument(1)) == 1));

        FullDraftCompletionService.DraftCompletionResult result = service(3).completeAll(59L);

        assertEquals(1, result.chaptersDegraded());
        assertEquals(3, result.chaptersCompleted());
    }

    @Test
    void staleRevisionExceptionIsRethrown() {
        setUpThreeChapterPlan();
        when(chapterService.chapterComplete(anyLong(), anyInt())).thenReturn(false);
        RuntimeException failure = new RuntimeException("PLAN_REVISION_STALE：计划已变化");
        when(chapterService.completeChapter(anyLong(), anyInt())).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service(3).completeAll(59L));

        assertSame(failure, thrown);
        verify(chapterService, times(1)).completeChapter(anyLong(), anyInt());
    }

    @Test
    void databaseRuntimeExceptionIsRethrown() {
        setUpThreeChapterPlan();
        when(chapterService.chapterComplete(anyLong(), anyInt())).thenReturn(false);
        org.springframework.dao.DataAccessResourceFailureException failure =
                new org.springframework.dao.DataAccessResourceFailureException("数据库不可用");
        when(chapterService.completeChapter(anyLong(), anyInt())).thenThrow(failure);

        org.springframework.dao.DataAccessResourceFailureException thrown =
                assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                        () -> service(3).completeAll(59L));

        assertSame(failure, thrown);
        verify(chapterService, times(1)).completeChapter(anyLong(), anyInt());
    }

    @Test
    void contractRuntimeExceptionIsRethrown() {
        setUpThreeChapterPlan();
        when(chapterService.chapterComplete(anyLong(), anyInt())).thenReturn(false);
        com.zzdzz.novelgen.common.web.BizException failure =
                new com.zzdzz.novelgen.common.web.BizException(
                        com.zzdzz.novelgen.common.web.ErrorCode.STATE_CONFLICT, "CONTRACT_DRIFT");
        when(chapterService.completeChapter(anyLong(), anyInt())).thenThrow(failure);

        com.zzdzz.novelgen.common.web.BizException thrown =
                assertThrows(com.zzdzz.novelgen.common.web.BizException.class,
                        () -> service(3).completeAll(59L));

        assertSame(failure, thrown);
        verify(chapterService, times(1)).completeChapter(anyLong(), anyInt());
    }

    @Test
    void nullPointerRuntimeExceptionIsRethrown() {
        setUpThreeChapterPlan();
        when(chapterService.chapterComplete(anyLong(), anyInt())).thenReturn(false);
        NullPointerException failure = new NullPointerException("意外空指针");
        when(chapterService.completeChapter(anyLong(), anyInt())).thenThrow(failure);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> service(3).completeAll(59L));

        assertSame(failure, thrown);
        verify(chapterService, times(1)).completeChapter(anyLong(), anyInt());
    }

    @Test
    void chapterFailureDoesNotBecomeDRAFT_CHAPTER_EMPTY() {
        setUpThreeChapterPlan();
        when(chapterService.chapterComplete(anyLong(), anyInt())).thenReturn(false);
        com.zzdzz.novelgen.common.web.BizException failure =
                new com.zzdzz.novelgen.common.web.BizException(
                        com.zzdzz.novelgen.common.web.ErrorCode.STATE_CONFLICT, "合同路径 chapter[2]");
        when(chapterService.completeChapter(anyLong(), anyInt())).thenThrow(failure);

        com.zzdzz.novelgen.common.web.BizException thrown =
                assertThrows(com.zzdzz.novelgen.common.web.BizException.class,
                        () -> service(3).completeAll(59L));

        assertSame(failure, thrown);
        assertTrue(!thrown.getMessage().contains("DRAFT_CHAPTER_EMPTY"));
    }

    private void setUpThreeChapterPlan() {
        plan = DraftFixtures.plan(59L, 3);
        DerivePlanSnapshotDO snapshot = new DerivePlanSnapshotDO();
        snapshot.setRevision(4);
        snapshot.setStatus("ADMITTED");
        when(snapshotService.requireActive(59L)).thenReturn(snapshot);
        when(snapshotService.loadCompiled(59L, 4)).thenReturn(plan);
    }

    private FullDraftCompletionService service(int chapters) {
        return new FullDraftCompletionService(snapshotService, chapterService);
    }

    private static ChapterDraftResult result(int chapterNo, boolean degraded) {
        String text = DraftFixtures.prose(chapterNo, 80);
        return new ChapterDraftResult(chapterNo, text, text.length(),
                List.of(new SceneDraft(1, text, text.length())), degraded);
    }
}


