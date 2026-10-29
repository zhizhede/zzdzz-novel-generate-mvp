package com.zzdzz.novelgen.derive.orchestration;

import com.zzdzz.novelgen.model.vo.derive.AutoVO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeriveRunOrchestratorTest {

    @Mock
    private StageRunStateService state;

    @Mock
    private DeriveStageHandlerRegistry registry;

    @Test
    void advanceRunsExactlyOneStagePerCall() {
        StageClaim claim = claim(DeriveStage.DRAFT);
        RecordingHandler handler = new RecordingHandler(DeriveStage.DRAFT,
                StageOutcome.done(4));
        when(state.claimNext(anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(claim);
        when(registry.require(DeriveStage.DRAFT)).thenReturn(handler);
        when(state.status(59L)).thenReturn(doneStatus());

        AutoVO result = orchestrator().advance(59L);

        assertEquals(1, handler.executions);
        assertEquals("COMPLETE:DONE", result.stage());
        verify(state).done(claim, 4, null);
        verify(state, never()).failed(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void failedStageStopsWithoutAdvancing() {
        StageClaim claim = claim(DeriveStage.DRAFT);
        RecordingHandler handler = new RecordingHandler(DeriveStage.DRAFT,
                StageOutcome.failed("DRAFT_PLAN_REVISION_STALE", "run[59]", "旧 revision"));
        when(state.claimNext(anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(claim);
        when(registry.require(DeriveStage.DRAFT)).thenReturn(handler);
        when(state.status(59L)).thenReturn(failedStatus());

        AutoVO result = orchestrator().advance(59L);

        assertEquals(1, handler.executions);
        verify(state).failed(claim, "DRAFT_PLAN_REVISION_STALE", "run[59]", "旧 revision");
        assertEquals("DRAFT_PLAN_REVISION_STALE:run[59]", result.lastError());
    }

    @Test
    void failedDraftStageDoesNotAdvanceToFinalAudit() {
        StageClaim claim = claim(DeriveStage.DRAFT);
        var snapshotService = org.mockito.Mockito.mock(
                com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService.class);
        var completionService = org.mockito.Mockito.mock(
                com.zzdzz.novelgen.derive.draft.FullDraftCompletionService.class);
        var snapshot = new com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO();
        snapshot.setRevision(4);
        snapshot.setStatus("ADMITTED");
        org.mockito.Mockito.when(snapshotService.requireActive(59L)).thenReturn(snapshot);
        org.mockito.Mockito.when(completionService.completeAll(59L))
                .thenReturn(new com.zzdzz.novelgen.derive.draft.FullDraftCompletionService.DraftCompletionResult(
                        4, 3, 2, 0, List.of(2)));
        var handler = new DraftStageHandler(snapshotService, completionService);
        when(state.claimNext(anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(claim);
        when(registry.require(DeriveStage.DRAFT)).thenReturn(handler);
        when(state.status(59L)).thenReturn(failedStatus());

        AutoVO result = orchestrator().advance(59L);

        verify(state).failed(org.mockito.ArgumentMatchers.eq(claim),
                org.mockito.ArgumentMatchers.eq("DRAFT_INCOMPLETE"),
                org.mockito.ArgumentMatchers.eq("chapter[2]"),
                org.mockito.ArgumentMatchers.contains("chapter[2]"));
        verify(registry, never()).require(DeriveStage.FINAL_AUDIT);
    }

    @Test
    void statusComesFromDatabaseNotMemory() {
        when(state.status(59L)).thenReturn(doneStatus());

        AutoVO result = orchestrator().status(59L);

        assertEquals(3, result.steps());
        assertEquals(true, result.done());
        verify(state).status(59L);
    }

    @Test
    void cancelMarksOpenLedgerWithoutExecutingHandler() {
        when(state.status(59L)).thenReturn(cancelledStatus());

        AutoVO result = orchestrator().cancel(59L);

        verify(state).cancelOpen(59L);
        assertEquals(true, result.stopRequested());
        verify(registry, never()).require(org.mockito.ArgumentMatchers.any());
    }

    private DeriveRunOrchestrator orchestrator() {
        return new DeriveRunOrchestrator(state, registry);
    }

    private static StageClaim claim(DeriveStage stage) {
        return new StageClaim(59L, stage, 0, "owner", OffsetDateTime.now().plusMinutes(10));
    }

    private static StageStatusSnapshot doneStatus() {
        return new StageStatusSnapshot(List.of(
                snapshot(DeriveStage.DRAFT, "DONE"),
                snapshot(DeriveStage.FINAL_AUDIT, "DONE"),
                snapshot(DeriveStage.COMPLETE, "DONE")));
    }

    private static StageStatusSnapshot failedStatus() {
        DeriveStageRunDO row = snapshot(DeriveStage.DRAFT, "FAILED");
        row.setErrorCode("DRAFT_PLAN_REVISION_STALE");
        row.setErrorPath("run[59]");
        return new StageStatusSnapshot(List.of(row));
    }

    private static StageStatusSnapshot cancelledStatus() {
        return new StageStatusSnapshot(List.of(snapshot(DeriveStage.DRAFT, "CANCELLED")));
    }

    private static com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO snapshot(
            DeriveStage stage, String status) {
        var row = new com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO();
        row.setStage(stage.name());
        row.setStatus(status);
        return row;
    }

    private static final class RecordingHandler implements DeriveStageHandler {
        private final DeriveStage stage;
        private final StageOutcome outcome;
        private int executions;

        private RecordingHandler(DeriveStage stage, StageOutcome outcome) {
            this.stage = stage;
            this.outcome = outcome;
        }

        @Override
        public DeriveStage supports() {
            return stage;
        }

        @Override
        public StageOutcome execute(StageClaim claim) {
            executions++;
            return outcome;
        }
    }
}


