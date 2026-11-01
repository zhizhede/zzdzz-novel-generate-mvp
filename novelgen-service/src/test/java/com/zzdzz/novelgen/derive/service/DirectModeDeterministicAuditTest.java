package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.AutoVO;
import com.zzdzz.novelgen.model.vo.derive.QcVO;
import com.zzdzz.novelgen.model.vo.derive.WriteVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DirectModeDeterministicAuditTest {

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
    void directDowngradesDeterministicAuditAndWritesDraft() {
        long runId = 99041L;
        RunDO run = new RunDO();
        run.setId(runId);
        run.setNovelId(41L);
        run.setStatus("DRAFTING");
        run.setStepStatus("{\"v\":1,\"steps\":{\"GATE\":{\"status\":\"PENDING\"}}}");
        when(runMapper.selectById(runId)).thenReturn(run);
        when(gateService.deterministicAudit(runId))
                .thenReturn(List.of("OBLIGATION_MISSING：CH5:FINAL_CLOSURE:TRUTH"));
        when(runMapper.updateStepStatus(anyLong(), anyString())).thenAnswer(invocation -> {
            run.setStepStatus(invocation.getArgument(1));
            return 1;
        });
        when(writeService.writeNext(runId)).thenAnswer(invocation -> {
            run.setStatus("QC");
            return new WriteVO(runId, 41L, 1, 1000, 1000, 0, true, true, "");
        });
        when(qcService.qcDraft(runId)).thenAnswer(invocation -> {
            run.setStatus("PASSED");
            return new QcVO(runId, 41L, 1, "PASS", 7, 7, false, true,
                    List.of("直通初稿"), 1000, 1000, 0);
        });
        DeriveAutoFlowService service = service();

        AutoVO vo = service.start(runId, true);

        await();
        verify(gateService).deterministicAudit(runId);
        verify(writeService).writeNext(runId);
        verify(qcService).qcDraft(runId);
        AutoVO done = service.status(runId);
        assertTrue(done.done(), "初稿直通不得因确定性问题熔断：" + done.lastError());
    }

    private DeriveAutoFlowService service() {
        return new DeriveAutoFlowService(runMapper, planService, draftService, gateService,
                writeService, resetService, qcService);
    }

    private static void await() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
