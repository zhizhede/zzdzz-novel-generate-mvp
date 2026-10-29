package com.zzdzz.novelgen.derive.orchestration;

import com.zzdzz.novelgen.derive.draft.FullDraftCompletionService;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DraftStageHandlerTest {

    @Mock
    private PlanSnapshotService snapshotService;

    @Mock
    private FullDraftCompletionService completionService;

    private DraftStageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DraftStageHandler(snapshotService, completionService);
        DerivePlanSnapshotDO snapshot = new DerivePlanSnapshotDO();
        snapshot.setRevision(4);
        snapshot.setStatus("ADMITTED");
        when(snapshotService.requireActive(59L)).thenReturn(snapshot);
    }

    @Test
    void completeAllWithEmptyMissingReturnsDoneStageOutcome() {
        when(completionService.completeAll(59L))
                .thenReturn(new FullDraftCompletionService.DraftCompletionResult(
                        4, 3, 3, 0, List.of()));

        StageOutcome outcome = handler.execute(claim());

        assertTrue(outcome.done());
        assertEquals(4, outcome.outputRevision());
        assertEquals(0, outcome.chaptersDegraded());
    }

    @Test
    void completedDraftStagePersistsCompletedRevision() {
        when(completionService.completeAll(59L))
                .thenReturn(new FullDraftCompletionService.DraftCompletionResult(
                        4, 3, 3, 0, List.of()));

        StageOutcome outcome = handler.execute(claim());

        assertTrue(outcome.done());
        assertEquals(4, outcome.outputRevision());
        assertTrue(outcome.completionMessage().contains("\"chaptersCompleted\":3"));
    }

    @Test
    void completeAllWithMissingChaptersReturnsFailedStageOutcome() {
        when(completionService.completeAll(59L))
                .thenReturn(new FullDraftCompletionService.DraftCompletionResult(
                        4, 3, 2, 0, List.of(2)));

        StageOutcome outcome = handler.execute(claim());

        assertFalse(outcome.done());
        assertEquals("DRAFT_INCOMPLETE", outcome.errorCode());
        assertEquals("chapter[2]", outcome.errorPath());
        assertTrue(outcome.errorMessage().contains("chapter[2]"));
    }

    private static StageClaim claim() {
        return new StageClaim(59L, DeriveStage.DRAFT, 0, "owner",
                OffsetDateTime.now().plusMinutes(5));
    }
}