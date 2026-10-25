package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
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

/** ThreadLedger 状态同步测试。 */
@ExtendWith(MockitoExtension.class)
class DeriveThreadServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private BeatInstanceMapper beatInstanceMapper;
    @Mock
    private StoryThreadMapper storyThreadMapper;
    @InjectMocks
    private DeriveThreadService service;

    @Test
    void payoffBeatMarksThreadPaid() throws Exception {
        BeatInstanceDO beat = new BeatInstanceDO();
        beat.setId(1L);
        beat.setRunId(40L);
        beat.setStatus("PLANNED");
        beat.setRouteContract(MAPPER.writeValueAsString(Map.of(
                "beatRole", "CLUE_PAYOFF",
                "threadRefs", List.of("GM-CLUE-01"))));

        StoryThreadDO thread = new StoryThreadDO();
        thread.setId(2L);
        thread.setRunId(40L);
        thread.setThreadKey("GM-CLUE-01");
        thread.setStatus("OPEN");

        when(beatInstanceMapper.selectList(any())).thenReturn(List.of(beat));
        when(storyThreadMapper.selectList(any())).thenReturn(List.of(thread));

        service.syncFromBeats(40L);

        verify(storyThreadMapper).updateFull(thread);
    }
}