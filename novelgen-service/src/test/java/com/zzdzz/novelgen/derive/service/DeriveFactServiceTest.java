package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveFactMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveRepairLogMapper;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveFactDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveRepairLogDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FactLedger 重建与修复审计测试。 */
@ExtendWith(MockitoExtension.class)
class DeriveFactServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private BeatInstanceMapper beatInstanceMapper;
    @Mock
    private DeriveFactMapper factMapper;
    @Mock
    private DeriveRepairLogMapper repairLogMapper;
    @InjectMocks
    private DeriveFactService service;

    @Test
    void rebuildUpsertsFactsFromRouteContract() throws Exception {
        BeatInstanceDO beat = new BeatInstanceDO();
        beat.setId(1L);
        beat.setRunId(40L);
        beat.setChapterNo(2);
        beat.setSeq(2);
        beat.setStatus("PLANNED");
        beat.setRouteContract(MAPPER.writeValueAsString(Map.of(
                "focusCharacters", List.of("C2"),
                "writesKnowledge", List.of("C2完成详细报告"),
                "requiresKnowledge", List.of())));

        when(beatInstanceMapper.selectList(any())).thenReturn(List.of(beat));
        when(factMapper.selectList(any())).thenReturn(List.of());

        service.rebuild(40L, 87L);

        verify(factMapper).insertFull(any(DeriveFactDO.class));
        verify(factMapper).updateFull(any(DeriveFactDO.class));
    }

    @Test
    void recordRepairWritesAuditRow() {
        service.recordRepair(40L, "PLAN_VALIDATE", "TEST_REPAIR",
                Map.of("before", 1), Map.of("after", 2), "测试", "PLAN_VOLUME");

        verify(repairLogMapper).insertFull(any(DeriveRepairLogDO.class));
    }
}