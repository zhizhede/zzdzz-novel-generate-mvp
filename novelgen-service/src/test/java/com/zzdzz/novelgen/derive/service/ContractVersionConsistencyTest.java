package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.mapper.DeriveChapterScheduleMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveGenreContractMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryContractMapper;
import com.zzdzz.novelgen.derive.story.ContractVersionService;
import com.zzdzz.novelgen.model.entity.derive.DeriveChapterScheduleDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreContractDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryContractDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractVersionConsistencyTest {

    @Mock
    private DeriveStoryContractMapper storyMapper;
    @Mock
    private DeriveGenreContractMapper genreMapper;
    @Mock
    private DeriveChapterScheduleMapper scheduleMapper;

    @Test
    void newRunVersionsMatchAfterContractFreeze() {
        when(storyMapper.selectOne(any())).thenReturn(story(1));
        when(genreMapper.findByRun(9L)).thenReturn(genre(1));
        when(scheduleMapper.selectByRun(9L)).thenReturn(List.of(schedule(1)));

        ContractVersionService.ContractVersionSnapshot snapshot = service().current(run());

        assertEquals(1, snapshot.storyContractVersion());
        assertEquals(1, snapshot.genreContractVersion());
        assertEquals(1, snapshot.scheduleVersion());
        assertDoesNotThrow(() -> service().assertConsistent(run()));
    }

    @Test
    void scheduleVersionMatchesGenreContract() {
        when(storyMapper.selectOne(any())).thenReturn(story(3));
        when(genreMapper.findByRun(9L)).thenReturn(genre(3));
        when(scheduleMapper.selectByRun(9L)).thenReturn(List.of(schedule(3)));

        assertDoesNotThrow(() -> service().assertConsistent(run()));
    }

    @Test
    void legacyRunWithoutGenreContractStillLoads() {
        when(storyMapper.selectOne(any())).thenReturn(story(1));
        when(genreMapper.findByRun(9L)).thenReturn(null);
        when(scheduleMapper.selectByRun(9L)).thenReturn(List.of(schedule(1)));

        ContractVersionService.ContractVersionSnapshot snapshot = service().current(run());

        assertEquals(1, snapshot.storyContractVersion());
        assertEquals(1, snapshot.genreContractVersion(), "旧 run 无类型合同行时按故事合同兼容");
        assertDoesNotThrow(() -> service().assertConsistent(run()));
    }

    @Test
    void staleScheduleIsDetected() {
        when(storyMapper.selectOne(any())).thenReturn(story(1));
        when(genreMapper.findByRun(9L)).thenReturn(genre(1));
        when(scheduleMapper.selectByRun(9L)).thenReturn(List.of(schedule(0)));

        BizException error = assertThrows(BizException.class,
                () -> service().assertConsistent(run()));

        assertTrue(error.getMessage().contains("SCHEDULE_STALE"), error.getMessage());
    }

    @Test
    void genreContractVersionMismatchIsDetected() {
        when(storyMapper.selectOne(any())).thenReturn(story(1));
        when(genreMapper.findByRun(9L)).thenReturn(genre(0));
        when(scheduleMapper.selectByRun(9L)).thenReturn(List.of(schedule(1)));

        BizException error = assertThrows(BizException.class,
                () -> service().assertConsistent(run()));

        assertTrue(error.getMessage().contains("CONTRACT_VERSION_MISMATCH"),
                error.getMessage());
    }

    private ContractVersionService service() {
        return new ContractVersionService(storyMapper, genreMapper, scheduleMapper);
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(9L);
        run.setNovelId(77L);
        run.setGenre("MYSTERY");
        return run;
    }

    private static DeriveStoryContractDO story(int version) {
        DeriveStoryContractDO row = new DeriveStoryContractDO();
        row.setRunId(9L);
        row.setVersion(version);
        return row;
    }

    private static DeriveGenreContractDO genre(int version) {
        DeriveGenreContractDO row = new DeriveGenreContractDO();
        row.setRunId(9L);
        row.setGenre("MYSTERY");
        row.setContractVersion(version);
        return row;
    }

    private static DeriveChapterScheduleDO schedule(int version) {
        DeriveChapterScheduleDO row = new DeriveChapterScheduleDO();
        row.setRunId(9L);
        row.setChapterNo(1);
        row.setContractVersion(version);
        return row;
    }
}