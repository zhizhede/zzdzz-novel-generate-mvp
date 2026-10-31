package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.AutoVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
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
    void directStillRunsDeterministicAuditBeforePass() {
        long runId = 99041L;
        RunDO run = new RunDO();
        run.setId(runId);
        run.setNovelId(41L);
        run.setStatus("DRAFTING");
        run.setStepStatus("{\"v\":1,\"steps\":{\"GATE\":{\"status\":\"PENDING\"}}}");
        when(runMapper.selectById(runId)).thenReturn(run);
        when(gateService.deterministicAudit(runId))
                .thenReturn(List.of("OBLIGATION_MISSING：CH5:FINAL_CLOSURE:TRUTH"));
        DeriveAutoFlowService service = service();

        AutoVO vo = service.start(runId, true);

        assertFalse(vo.done());
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        verify(gateService).deterministicAudit(runId);
        verify(writeService, never()).writeNext(anyLong());
    }

    private DeriveAutoFlowService service() {
        return new DeriveAutoFlowService(runMapper, planService, draftService, gateService,
                writeService, resetService, qcService);
    }
}
