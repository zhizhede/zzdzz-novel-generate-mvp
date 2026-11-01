package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.AutoVO;
import com.zzdzz.novelgen.model.vo.derive.QcVO;
import com.zzdzz.novelgen.model.vo.derive.WriteVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DirectProbeModeTest {

    @Mock
    private RunMapper runMapper;
    @Mock
    private DerivePlanService planService;
    @Mock
    private DeriveDraftService draftService;
    @Mock
    private DeriveGateService gateService;
    @Mock
    private DeriveWriteService writeService;
    @Mock
    private DeriveResetService resetService;
    @Mock
    private DeriveQcService qcService;

    @Test
    void directDowngradesObligationWindowAudit() {
        assertDeterministicIssueDowngraded(
                "OBLIGATION_WINDOW：OBLIGATION_MISSING：CH5:PAYOFF_THREAD:GM-CLUE-01");
    }

    @Test
    void directDowngradesClosureWindowAudit() {
        assertDeterministicIssueDowngraded(
                "CLOSURE_WINDOW_MISSING：chapterNo=5 obligationKey=CH5:FINAL_CLOSURE:TRUTH");
    }

    @Test
    void directDowngradesBudgetGuardAudit() {
        assertDeterministicIssueDowngraded(
                "BUDGET_PROJECTED_OVERSHOOT：projectedMax=7329 wordsMax=5000");
    }

    @Test
    void directSkipsLlmGateReview() {
        RunDO run = run("DRAFTING", "{\"v\":1,\"steps\":{\"GATE\":{\"status\":\"PENDING\"}}}", 42L);
        when(runMapper.selectById(42L)).thenReturn(run);
        when(gateService.deterministicAudit(42L)).thenAnswer(invocation -> {
            run.setStatus("PASSED");
            return List.of();
        });

        service().start(42L, true);
        await();

        verify(gateService).deterministicAudit(42L);
        verify(gateService, never()).gate(anyLong());
    }

    @Test
    void directUsesDraftQcAndSkipsStrictQc() {
        RunDO run = run("QC", "{\"v\":1,\"steps\":{\"GATE\":{\"status\":\"PASS\"}}}", 43L);
        when(runMapper.selectById(43L)).thenReturn(run);
        when(qcService.qcDraft(43L)).thenAnswer(invocation -> {
            run.setStatus("PASSED");
            return new QcVO(43L, 7L, 1, "PASS",
                    7, 7, false, false, List.of(), 4000, 800, 5);
        });

        service().start(43L, true);
        await();

        verify(qcService).qcDraft(43L);
        verify(qcService, never()).qc(anyLong());
        verify(qcService, never()).qc(eq(43L), eq(true));
        verify(gateService, never()).gate(anyLong());
    }

    private void assertDeterministicIssueDowngraded(String issue) {
        RunDO run = run("DRAFTING", "{\"v\":1,\"steps\":{\"GATE\":{\"status\":\"PENDING\"}}}", 44L);
        when(runMapper.selectById(44L)).thenReturn(run);
        when(gateService.deterministicAudit(44L)).thenReturn(List.of(issue));
        when(runMapper.updateStepStatus(anyLong(), anyString())).thenAnswer(invocation -> {
            run.setStepStatus(invocation.getArgument(1));
            return 1;
        });
        when(writeService.writeNext(44L)).thenAnswer(invocation -> {
            run.setStatus("QC");
            return new WriteVO(44L, 7L, 1, 1000, 1000, 0, true, true, "");
        });
        when(qcService.qcDraft(44L)).thenAnswer(invocation -> {
            run.setStatus("PASSED");
            return new QcVO(44L, 7L, 1, "PASS", 7, 7, false, false,
                    List.of(issue), 1000, 1000, 1);
        });

        DeriveAutoFlowService service = service();
        service.start(44L, true);
        await();

        AutoVO vo = service.status(44L);
        assertTrue(vo.done(), "初稿直通不得熔断：" + vo.lastError());
        ArgumentCaptor<String> stepStatus = ArgumentCaptor.forClass(String.class);
        verify(runMapper).updateStepStatus(eq(44L), stepStatus.capture());
        assertTrue(stepStatus.getValue().contains("\"status\":\"PASS\""),
                "确定性问题必须降级为 PASS：" + stepStatus.getValue());
        assertTrue(stepStatus.getValue().contains(issue.split("：", 2)[0]),
                "降级报告必须保留原问题：" + stepStatus.getValue());
        verify(writeService).writeNext(44L);
        verify(qcService).qcDraft(44L);
        verify(gateService, never()).gate(anyLong());
    }

    private static RunDO run(String status, String stepStatus, long id) {
        RunDO run = new RunDO();
        run.setId(id);
        run.setNovelId(7L);
        run.setStatus(status);
        run.setStepStatus(stepStatus);
        return run;
    }

    private void await() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private DeriveAutoFlowService service() {
        return new DeriveAutoFlowService(runMapper, planService, draftService, gateService,
                writeService, resetService, qcService);
    }
}
