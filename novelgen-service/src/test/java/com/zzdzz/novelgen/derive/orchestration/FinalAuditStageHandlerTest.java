package com.zzdzz.novelgen.derive.orchestration;

import com.zzdzz.novelgen.derive.draft.FinalAuditReport;
import com.zzdzz.novelgen.derive.draft.FinalAuditService;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditIssue;
import com.zzdzz.novelgen.derive.plan.audit.Severity;
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
class FinalAuditStageHandlerTest {

    @Mock
    private FinalAuditService auditService;

    private FinalAuditStageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FinalAuditStageHandler(auditService);
    }

    @Test
    void finalAuditHandlerReturnsFailedStageOutcomeForBlocker() {
        PlanAuditIssue blocker = issue("FINAL_AUDIT_BLOCKER", Severity.BLOCKER, "chapter[2]");
        when(auditService.audit(59L))
                .thenReturn(new FinalAuditReport(59L, 5, FinalAuditReport.FinalQuality.PASS,
                        List.of(blocker)));

        StageOutcome outcome = handler.execute(claim());

        assertFalse(outcome.done());
        assertEquals("FINAL_AUDIT_BLOCKER", outcome.errorCode());
        assertEquals("chapter[2]", outcome.errorPath());
    }

    @Test
    void finalAuditHandlerDoesNotReturnDoneForFailedReport() {
        when(auditService.audit(59L))
                .thenReturn(new FinalAuditReport(59L, 5, FinalAuditReport.FinalQuality.FAILED,
                        List.of()));

        StageOutcome outcome = handler.execute(claim());

        assertFalse(outcome.done());
        assertEquals("FINAL_AUDIT_FAILED", outcome.errorCode());
        assertEquals("run[59]", outcome.errorPath());
    }

    @Test
    void advisoryAndPassReportsRemainDone() {
        when(auditService.audit(59L))
                .thenReturn(new FinalAuditReport(59L, 5, FinalAuditReport.FinalQuality.ADVISORY,
                        List.of()))
                .thenReturn(new FinalAuditReport(59L, 5, FinalAuditReport.FinalQuality.PASS,
                        List.of()));

        assertTrue(handler.execute(claim()).done());
        assertTrue(handler.execute(claim()).done());
    }

    private static StageClaim claim() {
        return new StageClaim(59L, DeriveStage.FINAL_AUDIT, 0, "owner",
                OffsetDateTime.now().plusMinutes(5));
    }

    private static PlanAuditIssue issue(String code, Severity severity, String path) {
        return new PlanAuditIssue(code, severity, path, null, null,
                PlanContracts.RepairScope.NONE, "终检硬伤", "", "invalid");
    }
}