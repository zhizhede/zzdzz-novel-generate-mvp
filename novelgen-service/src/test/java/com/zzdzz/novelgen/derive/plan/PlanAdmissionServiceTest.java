package com.zzdzz.novelgen.derive.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionDecision;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionResult;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionService;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditIssue;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditReport;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditor;
import com.zzdzz.novelgen.derive.plan.audit.SemanticPlanReviewService;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanProjectionService;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.derive.plan.compiler.StoryPlanCompiler;
import com.zzdzz.novelgen.derive.service.DeriveContractService;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanAdmissionServiceTest {

    @Mock
    private PlanAuditor auditor;
    @Mock
    private PlanSnapshotService snapshotService;
    @Mock
    private PlanProjectionService projectionService;
    @Mock
    private DeriveContractService contractService;
    @Mock
    private RunMapper runMapper;

    @Test
    void blockerReturnsRepairAndDoesNotPersistAdmission() {
        var fixture = validFixture();
        when(runMapper.selectById(59L)).thenReturn(run());
        when(contractService.load(run())).thenReturn(fixture.contract());
        when(snapshotService.requireRevision(59L, 4)).thenReturn(snapshot(fixture.plan()));
        when(snapshotService.loadCompiled(59L, 4)).thenReturn(fixture.plan());
        when(auditor.audit(fixture.plan(), fixture.contract()))
                .thenReturn(report(issue("PLAN_CLOSURE_MISSING", "BLOCKER")));

        PlanAdmissionResult result = service(null).admit(59L, 4);

        assertEquals(PlanAdmissionDecision.REPAIR, result.decision());
        assertFalse(result.report().admitted());
        verify(projectionService, never()).activate(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
        verify(snapshotService, never()).admit(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void advisoryOnlyAdmitsAndActivatesProjectionBeforeSnapshot() {
        var fixture = validFixture();
        when(runMapper.selectById(59L)).thenReturn(run());
        when(contractService.load(run())).thenReturn(fixture.contract());
        when(snapshotService.requireRevision(59L, 4)).thenReturn(snapshot(fixture.plan()));
        when(snapshotService.loadCompiled(59L, 4)).thenReturn(fixture.plan());
        when(auditor.audit(fixture.plan(), fixture.contract()))
                .thenReturn(report(issue("PLAN_STYLE_SUGGESTION", "ADVISORY")));

        PlanAdmissionResult result = service(null).admit(59L, 4);

        assertEquals(PlanAdmissionDecision.ADMIT, result.decision());
        InOrder ordered = inOrder(projectionService, snapshotService);
        ordered.verify(projectionService).activate(59L, 12L, 4, fixture.plan());
        ordered.verify(snapshotService).admit(
                org.mockito.ArgumentMatchers.eq(59L),
                org.mockito.ArgumentMatchers.eq(4),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(fixture.plan().canonicalHash()));
    }

    @Test
    void projectionFailureLeavesNoAdmittedSnapshot() {
        var fixture = validFixture();
        when(runMapper.selectById(59L)).thenReturn(run());
        when(contractService.load(run())).thenReturn(fixture.contract());
        when(snapshotService.requireRevision(59L, 4)).thenReturn(snapshot(fixture.plan()));
        when(snapshotService.loadCompiled(59L, 4)).thenReturn(fixture.plan());
        when(auditor.audit(fixture.plan(), fixture.contract()))
                .thenReturn(report());
        doThrow(new IllegalStateException("projection failed"))
                .when(projectionService).activate(59L, 12L, 4, fixture.plan());

        assertThrows(IllegalStateException.class, () -> service(null).admit(59L, 4));

        verify(snapshotService, never()).admit(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void semanticReviewOnlyAddsAdvisoryAndCannotChangePlanOrDecision() {
        var fixture = validFixture();
        when(runMapper.selectById(59L)).thenReturn(run());
        when(contractService.load(run())).thenReturn(fixture.contract());
        when(snapshotService.requireRevision(59L, 4)).thenReturn(snapshot(fixture.plan()));
        when(snapshotService.loadCompiled(59L, 4)).thenReturn(fixture.plan());
        when(auditor.audit(fixture.plan(), fixture.contract())).thenReturn(report());
        SemanticPlanReviewService semantic = plan ->
                List.of(new PlanAuditIssue("PLAN_STYLE_SUGGESTION",
                        com.zzdzz.novelgen.derive.plan.audit.Severity.BLOCKER,
                        "plan", null, null,
                        com.zzdzz.novelgen.derive.plan.PlanContracts.RepairScope.NONE,
                        "模型主观意见", "", ""));

        PlanAdmissionResult result = service(semantic).admit(59L, 4);

        assertEquals(PlanAdmissionDecision.ADMIT, result.decision());
        assertTrue(result.report().issues().stream()
                .anyMatch(issue -> "ADVISORY".equals(issue.severity().name())));
    }

    private PlanAdmissionService service(SemanticPlanReviewService semantic) {
        var service = new PlanAdmissionService(auditor, snapshotService, projectionService,
                contractService, runMapper, new ObjectMapper());
        service.setSemanticReviewService(semantic);
        return service;
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(59L);
        run.setNovelId(12L);
        return run;
    }

    private static DerivePlanSnapshotDO snapshot(CompiledStoryPlan plan) {
        DerivePlanSnapshotDO snapshot = new DerivePlanSnapshotDO();
        snapshot.setId(4L);
        snapshot.setRunId(59L);
        snapshot.setRevision(4);
        snapshot.setStatus("DRAFT");
        snapshot.setPlanHash(plan.canonicalHash());
        snapshot.setGraph(plan.canonicalJson());
        return snapshot;
    }

    private static ValidFixture validFixture() {
        var input = PlanCompilerFixtures.input(59L, 12);
        var plan = new StoryPlanCompiler().compile(input).plan();
        return new ValidFixture(plan, input.contract());
    }

    private static PlanAuditReport report(PlanAuditIssue... issues) {
        return new PlanAuditReport(List.of(issues));
    }

    private static PlanAuditIssue issue(String code, String severity) {
        return new PlanAuditIssue(code,
                com.zzdzz.novelgen.derive.plan.audit.Severity.valueOf(severity),
                "plan", 1, "B1",
                com.zzdzz.novelgen.derive.plan.PlanContracts.RepairScope.NONE,
                code, "", "");
    }

    private record ValidFixture(CompiledStoryPlan plan,
                                com.zzdzz.novelgen.derive.contract.StoryContract contract) {
    }
}

