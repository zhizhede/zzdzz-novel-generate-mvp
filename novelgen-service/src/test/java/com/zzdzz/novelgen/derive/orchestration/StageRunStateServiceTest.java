package com.zzdzz.novelgen.derive.orchestration;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.mapper.DeriveStageRunMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StageRunStateServiceTest {

    @Mock
    private DeriveStageRunMapper mapper;

    @Test
    void activeRunSeedsDurableStageOrder() {
        DeriveStageRunDO seeded = row("DRAFT", "RUNNING", 0);
        when(mapper.findLatest(59L, "DRAFT")).thenReturn(null, seeded);
        when(mapper.findLatest(59L, "FINAL_AUDIT")).thenReturn(null);
        when(mapper.findLatest(59L, "COMPLETE")).thenReturn(null);
        when(mapper.findAll(59L)).thenReturn(List.of(
                row("DRAFT", "PENDING", 0),
                row("FINAL_AUDIT", "PENDING", 0),
                row("COMPLETE", "PENDING", 0)));
        when(mapper.claim(eq(59L), eq("DRAFT"), eq(0), anyString(), any())).thenReturn(1);

        StageClaim claim = service().claimNext(59L, "owner");

        assertEquals(DeriveStage.DRAFT, claim.stage());
        verify(mapper, times(3)).insertFull(any());
    }

    @Test
    void runningStageCannotBeClaimedBySecondOwner() {
        DeriveStageRunDO running = row("DRAFT", "RUNNING", 0);
        running.setLeaseOwner("first");
        running.setLeaseUntil(OffsetDateTime.now().plusMinutes(5));
        when(mapper.findLatest(59L, "DRAFT")).thenReturn(running);
        when(mapper.findLatest(59L, "FINAL_AUDIT")).thenReturn(row("FINAL_AUDIT", "PENDING", 0));
        when(mapper.findLatest(59L, "COMPLETE")).thenReturn(row("COMPLETE", "PENDING", 0));
        when(mapper.findAll(59L)).thenReturn(List.of(running));

        BizException error = assertThrows(BizException.class,
                () -> service().claimNext(59L, "second"));

        assertTrue(error.getMessage().contains("STAGE_CLAIM_CONFLICT"));
        verify(mapper, never()).claim(anyLong(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void expiredLeaseCanBeReclaimed() {
        DeriveStageRunDO expired = row("DRAFT", "RUNNING", 2);
        DeriveStageRunDO reclaimed = row("DRAFT", "RUNNING", 2);
        expired.setLeaseOwner("dead");
        expired.setLeaseUntil(OffsetDateTime.now().minusMinutes(1));
        when(mapper.findLatest(59L, "DRAFT")).thenReturn(expired, reclaimed);
        when(mapper.findLatest(59L, "FINAL_AUDIT")).thenReturn(row("FINAL_AUDIT", "PENDING", 0));
        when(mapper.findLatest(59L, "COMPLETE")).thenReturn(row("COMPLETE", "PENDING", 0));
        when(mapper.findAll(59L)).thenReturn(List.of(expired));
        when(mapper.claim(eq(59L), eq("DRAFT"), eq(2), anyString(), any())).thenReturn(1);

        StageClaim claim = service().claimNext(59L, "replacement");

        assertEquals(2, claim.attempt());
    }

    @Test
    void failedStageIsRetriedWithNextAttempt() {
        DeriveStageRunDO failed = row("DRAFT", "FAILED", 1);
        DeriveStageRunDO retryRunning = row("DRAFT", "RUNNING", 2);
        when(mapper.findLatest(59L, "DRAFT")).thenReturn(failed, retryRunning);
        when(mapper.findLatest(59L, "FINAL_AUDIT")).thenReturn(row("FINAL_AUDIT", "PENDING", 0));
        when(mapper.findLatest(59L, "COMPLETE")).thenReturn(row("COMPLETE", "PENDING", 0));
        when(mapper.findAll(59L)).thenReturn(List.of(row("DRAFT", "FAILED", 1)));
        when(mapper.insertPendingIfAbsent(eq(59L), eq("DRAFT"), eq(2))).thenReturn(1);
        when(mapper.claim(eq(59L), eq("DRAFT"), eq(2), anyString(), any())).thenReturn(1);

        StageClaim claim = service().claimNext(59L, "retry-owner");

        assertEquals(2, claim.attempt());
    }

    @Test
    void cancelledStageNeverAdvances() {
        when(mapper.findLatest(59L, "DRAFT")).thenReturn(row("DRAFT", "CANCELLED", 0));
        when(mapper.findLatest(59L, "FINAL_AUDIT")).thenReturn(row("FINAL_AUDIT", "PENDING", 0));
        when(mapper.findLatest(59L, "COMPLETE")).thenReturn(row("COMPLETE", "PENDING", 0));
        when(mapper.findAll(59L)).thenReturn(List.of(row("DRAFT", "CANCELLED", 0)));

        assertNull(service().claimNext(59L, "owner"));
        verify(mapper, never()).claim(anyLong(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void finishPersistsRevisionAndFailurePath() {
        StageClaim claim = new StageClaim(7L, DeriveStage.DRAFT, 1, "owner",
                OffsetDateTime.now().plusMinutes(10));
        when(mapper.finish(eq(7L), eq("owner"), eq("DONE"), any(), any(), any(), eq(4))).thenReturn(1);
        when(mapper.finish(eq(7L), eq("owner"), eq("FAILED"), any(), any(), any(), any())).thenReturn(1);

        service().done(claim, 4);

        service().failed(claim, "DRAFT_SCENE_EMPTY",
                "chapter[2].scene[3]", "场景为空");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(mapper).finish(eq(7L), eq("owner"), eq("FAILED"), eq("DRAFT_SCENE_EMPTY"),
                path.capture(), anyString(), any());
        assertEquals("chapter[2].scene[3]", path.getValue());
    }

    private StageRunStateService service() {
        return new StageRunStateService(mapper);
    }

    private static DeriveStageRunDO row(String stage, String status, int attempt) {
        DeriveStageRunDO row = new DeriveStageRunDO();
        row.setId(100L + attempt);
        row.setRunId(59L);
        row.setStage(stage);
        row.setStatus(status);
        row.setAttempt(attempt);
        return row;
    }

    private static void assertTrue(boolean value) {
        org.junit.jupiter.api.Assertions.assertTrue(value);
    }
}


