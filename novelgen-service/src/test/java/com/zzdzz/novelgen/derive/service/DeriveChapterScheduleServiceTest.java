package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.DeriveChapterScheduleMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.DeriveChapterScheduleDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 日程持久化：先删后插 + 版本守卫 + 空表 fail-fast（计划 §5.3）。 */
@ExtendWith(MockitoExtension.class)
class DeriveChapterScheduleServiceTest {

    @Mock
    private DeriveChapterScheduleMapper scheduleMapper;
    @Mock
    private DeriveStoryObligationMapper obligationMapper;
    @Mock
    private RunMapper runMapper;
    @Mock
    private DeriveContractService contractService;

    private DeriveChapterScheduleService service() {
        return new DeriveChapterScheduleService(scheduleMapper, obligationMapper, runMapper, contractService);
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(59L);
        run.setNovelId(121L);
        run.setStatus("PLANNING");
        run.setStepStatus("{\"v\":1,\"steps\":{}}");
        return run;
    }

    private static StoryContract contract(int version) {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 3, 2000, List.of("单案短篇"));
        return new StoryContract(59L, version, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙",
                        "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 3),
                List.of(new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 1, 3, "C1"),
                        new StoryContract.Clue("GM-CLUE-02", "值班表涂改", 2, 3, "C1"),
                        new StoryContract.Clue("GM-CLUE-03", "旧债欠条", 2, 3, "C2")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-02", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-03", "CLUE", 1, 1, "C2")),
                List.of("不得出现第二起案件"),
                List.of(new StoryContract.VolumeTruth(1, "陆敬为掩盖旧账制造失踪", List.of())),
                scope);
    }

    @Test
    void persistDeletesThenInsertsEveryEntry() {
        RunDO run = run();
        when(contractService.require(run)).thenReturn(contract(1));
        when(runMapper.selectById(59L)).thenReturn(run);
        List<ChapterScheduleEntry> entries = List.of(
                new ChapterScheduleEntry(1, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(2, 1, false, List.of("GM-CLUE-02", "GM-CLUE-03"), List.of()),
                new ChapterScheduleEntry(3, 1, true, List.of(),
                        List.of("GM-CLUE-01", "GM-CLUE-02", "GM-CLUE-03")));

        List<ChapterScheduleEntry> saved = service().persist(run, entries);

        assertEquals(3, saved.size());
        verify(scheduleMapper).deleteByRun(59L);
        ArgumentCaptor<DeriveChapterScheduleDO> rows =
                ArgumentCaptor.forClass(DeriveChapterScheduleDO.class);
        verify(scheduleMapper, times(3)).insertFull(rows.capture());
        assertTrue(rows.getAllValues().stream()
                .allMatch(row -> row.getContractVersion() == 1), "日程必须带合同版本");
        assertEquals("[\"GM-CLUE-02\",\"GM-CLUE-03\"]",
                rows.getAllValues().get(1).getMustPlant());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(runMapper).updateStepStatus(eq(59L), status.capture());
        assertTrue(status.getValue().contains("CHAPTER_SCHEDULE"), status.getValue());
        assertTrue(status.getValue().contains("\"contractVersion\":1"), status.getValue());
    }

    @Test
    void staleContractVersionFailsFast() {
        RunDO run = run();
        DeriveChapterScheduleDO row = new DeriveChapterScheduleDO();
        row.setChapterNo(1);
        row.setContractVersion(2);
        when(scheduleMapper.selectByRun(59L)).thenReturn(List.of(row));
        when(contractService.load(run)).thenReturn(contract(1));

        BizException error = assertThrows(BizException.class, () -> service().assertCurrent(run));

        assertTrue(error.getMessage().contains("SCHEDULE_STALE"), error.getMessage());
    }

    @Test
    void currentContractVersionPassesGuard() {
        RunDO run = run();
        DeriveChapterScheduleDO row = new DeriveChapterScheduleDO();
        row.setChapterNo(1);
        row.setContractVersion(1);
        when(scheduleMapper.selectByRun(59L)).thenReturn(List.of(row));
        when(contractService.load(run)).thenReturn(contract(1));

        service().assertCurrent(run);
    }

    @Test
    void requireFailsWhenScheduleMissing() {
        RunDO run = run();
        when(scheduleMapper.selectByRun(59L)).thenReturn(List.of());

        BizException error = assertThrows(BizException.class, () -> service().require(run));

        assertTrue(error.getMessage().contains("SCHEDULE_REQUIRED"), error.getMessage());
    }

    @Test
    void loadParsesJsonbArrays() {
        RunDO run = run();
        DeriveChapterScheduleDO row = new DeriveChapterScheduleDO();
        row.setChapterNo(3);
        row.setVolumeNo(1);
        row.setReveal(true);
        row.setMustPlant("[]");
        row.setMustPayoff("[\"GM-CLUE-01\",\"GM-CLUE-02\"]");
        when(scheduleMapper.selectByRun(59L)).thenReturn(List.of(row));

        List<ChapterScheduleEntry> entries = service().load(run);

        assertEquals(1, entries.size());
        assertTrue(entries.get(0).reveal());
        assertEquals(List.of("GM-CLUE-01", "GM-CLUE-02"), entries.get(0).mustPayoff());
        assertTrue(entries.get(0).mustPlant().isEmpty());
    }

    @Test
    void projectFallsBackToContractScopeWhenNoScaffold() {
        RunDO run = run();
        run.setStepStatus("{\"v\":1,\"steps\":{}}");
        when(contractService.require(run)).thenReturn(contract(1));

        List<ChapterScheduleEntry> entries = service().project(run);

        assertEquals(3, entries.size(), "无 scaffold 时用合同 scope 的章数");
        assertEquals(List.of("GM-CLUE-01"), entries.get(0).mustPlant());
        assertTrue(entries.get(2).reveal());
    }

    @Test
    void deleteByRunDelegatesToMapper() {
        service().deleteByRun(59L);

        verify(scheduleMapper).deleteByRun(59L);
        verify(scheduleMapper, times(0)).insertFull(any());
    }
}
