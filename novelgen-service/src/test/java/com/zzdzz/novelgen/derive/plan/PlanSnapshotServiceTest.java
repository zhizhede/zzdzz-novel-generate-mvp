package com.zzdzz.novelgen.derive.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DerivePlanSnapshotMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 计划快照 revision、admit 生命周期与 active 指针契约。 */
@ExtendWith(MockitoExtension.class)
class PlanSnapshotServiceTest {

    @Mock
    private DerivePlanSnapshotMapper mapper;
    @Mock
    private RunMapper runMapper;

    @Test
    void persistDraftAssignsNextRevisionAndStablePlanHash() {
        when(mapper.findMaxRevision(59L)).thenReturn(snapshot(59L, 3, "DRAFT"));
        when(mapper.insertFull(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, DerivePlanSnapshotDO.class).setId(71L);
            return 71L;
        });

        DerivePlanSnapshotDO first = service().persistDraft(59L, plan());
        ArgumentCaptor<DerivePlanSnapshotDO> captor =
                ArgumentCaptor.forClass(DerivePlanSnapshotDO.class);
        verify(mapper).insertFull(captor.capture());

        assertEquals(4, first.getRevision());
        assertEquals("DRAFT", first.getStatus());
        assertEquals(plan().contractHash(), first.getContractHash());
        assertEquals(plan().canonicalHash(), first.getPlanHash());
        assertNotNull(first.getGraph());
        assertEquals(plan(), CompiledStoryPlan.fromJson(first.getGraph()));
    }

    @Test
    void admitRejectsNonDraftRevision() {
        when(mapper.findRevision(59L, 4)).thenReturn(snapshot(59L, 4, "ADMITTED"));

        BizException error = assertThrows(BizException.class,
                () -> service().admit(59L, 4, "{}", "hash"));

        assertEquals(ErrorCode.STATE_CONFLICT, error.errorCode());
        assertTrue(error.getMessage().contains("PLAN_REVISION_CONFLICT"));
    }

    @Test
    void admitRejectsDifferentPlanHashAsStale() {
        DerivePlanSnapshotDO draft = snapshot(59L, 4, "DRAFT");
        draft.setPlanHash("expected");
        when(mapper.findRevision(59L, 4)).thenReturn(draft);

        BizException error = assertThrows(BizException.class,
                () -> service().admit(59L, 4, "{}", "other"));

        assertEquals(ErrorCode.STATE_CONFLICT, error.errorCode());
        assertTrue(error.getMessage().contains("PLAN_REVISION_STALE"));
        verify(mapper, never()).admit(anyLong(), anyInt(), any(), any());
    }

    @Test
    void admitSupersedesThenActivatesInOneOrderedLifecycle() {
        DerivePlanSnapshotDO draft = snapshot(59L, 4, "DRAFT");
        draft.setPlanHash("hash");
        when(mapper.findRevision(59L, 4)).thenReturn(draft);
        when(mapper.supersedeOtherAdmitted(59L, 4)).thenReturn(1);
        when(mapper.admit(59L, 4, "{\"decision\":\"ADMIT\"}", "hash")).thenReturn(1);
        when(mapper.updateRunActiveRevision(59L, 4)).thenReturn(1);

        service().admit(59L, 4, "{\"decision\":\"ADMIT\"}", "hash");

        InOrder ordered = inOrder(mapper);
        ordered.verify(mapper).supersedeOtherAdmitted(59L, 4);
        ordered.verify(mapper).admit(59L, 4, "{\"decision\":\"ADMIT\"}", "hash");
        ordered.verify(mapper).updateRunActiveRevision(59L, 4);
    }

    @Test
    void requireActiveFailsWhenPointerIsMissing() {
        when(runMapper.selectById(59L)).thenReturn(run(59L, null));

        BizException error = assertThrows(BizException.class,
                () -> service().requireActive(59L));

        assertEquals(ErrorCode.STATE_CONFLICT, error.errorCode());
        assertTrue(error.getMessage().contains("PLAN_REVISION_STALE"));
    }

    @Test
    void requireActiveFailsWhenPointerDoesNotResolveToAdmittedSnapshot() {
        when(runMapper.selectById(59L)).thenReturn(run(59L, 4));
        when(mapper.findRevision(59L, 4)).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service().requireActive(59L));

        assertTrue(error.getMessage().contains("PLAN_REVISION_STALE"));
    }

    @Test
    void requireActiveRejectsExpectedRevisionMismatch() {
        DerivePlanSnapshotDO admitted = admittedGraphSnapshot();
        when(runMapper.selectById(59L)).thenReturn(run(59L, 4));
        when(mapper.findRevision(59L, 4)).thenReturn(admitted);

        BizException error = assertThrows(BizException.class,
                () -> service().requireActive(59L, 3));

        assertTrue(error.getMessage().contains("PLAN_REVISION_STALE"));
    }

    @Test
    void requireActiveReturnsTheAdmittedSnapshot() {
        DerivePlanSnapshotDO admitted = admittedGraphSnapshot();
        when(runMapper.selectById(59L)).thenReturn(run(59L, 4));
        when(mapper.findRevision(59L, 4)).thenReturn(admitted);

        DerivePlanSnapshotDO active = service().requireActive(59L, 4);

        assertEquals(4, active.getRevision());
        assertEquals("ADMITTED", active.getStatus());
    }

    @Test
    void activeSnapshotWithMismatchedGraphContractHashIsStale() {
        DerivePlanSnapshotDO admitted = admittedGraphSnapshot();
        admitted.setContractHash("different");
        when(runMapper.selectById(59L)).thenReturn(run(59L, 4));
        when(mapper.findRevision(59L, 4)).thenReturn(admitted);

        BizException error = assertThrows(BizException.class,
                () -> service().requireActive(59L));

        assertTrue(error.getMessage().contains("PLAN_REVISION_STALE"));
    }

    private PlanSnapshotService service() {
        return new PlanSnapshotService(mapper, runMapper, new ObjectMapper());
    }

    private static RunDO run(long runId, Integer activeRevision) {
        RunDO run = new RunDO();
        run.setId(runId);
        run.setActivePlanRevision(activeRevision);
        return run;
    }

    private static DerivePlanSnapshotDO snapshot(long runId, int revision, String status) {
        DerivePlanSnapshotDO snapshot = new DerivePlanSnapshotDO();
        snapshot.setId((long) revision);
        snapshot.setRunId(runId);
        snapshot.setRevision(revision);
        snapshot.setContractVersion(1);
        snapshot.setContractHash("contract-hash");
        snapshot.setStatus(status);
        snapshot.setPlanHash("hash");
        snapshot.setGraph(plan().canonicalJson());
        return snapshot;
    }

    private static DerivePlanSnapshotDO admittedGraphSnapshot() {
        DerivePlanSnapshotDO snapshot = snapshot(59L, 4, "ADMITTED");
        snapshot.setPlanHash(plan().canonicalHash());
        return snapshot;
    }

    private static CompiledStoryPlan plan() {
        var scene = new PlanGraphRecords.PlannedScene(1, "B1", "目标",
                List.of(), List.of(), List.of(), List.of());
        var chapter = new PlanGraphRecords.PlannedChapter(1, 1, "第一章", 2000, List.of(scene));
        var beat = new PlanGraphRecords.PlannedBeat(
                1, "B1", "SYNTHETIC", null, null, null, "事件", new BigDecimal("0.50"),
                1, 1, 1, PlanContracts.StoryLayer.LOCAL_CASE,
                PlanContracts.BeatRole.CASE_SETUP, PlanContracts.AppearanceMode.LIVE, "目标",
                List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of());
        return new CompiledStoryPlan(
                59L, 1, "contract-hash",
                new PlanContracts.ScopeContract(
                        PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 1, 2000, List.of("fixture")),
                List.of(), List.of(), List.of(), List.of(chapter), List.of(beat));
    }
}