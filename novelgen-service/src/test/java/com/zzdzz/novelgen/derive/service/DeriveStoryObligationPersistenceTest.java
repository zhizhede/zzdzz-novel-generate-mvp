package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.DeriveChapterScheduleMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeriveStoryObligationPersistenceTest {

    @Mock
    private DeriveChapterScheduleMapper scheduleMapper;
    @Mock
    private DeriveStoryObligationMapper obligationMapper;
    @Mock
    private RunMapper runMapper;
    @Mock
    private DeriveContractService contractService;

    @Test
    void obligationTablePersistsProjectedRows() {
        RunDO run = run();
        StoryContract contract = contract();
        when(contractService.require(run)).thenReturn(contract);

        DeriveChapterScheduleService service = new DeriveChapterScheduleService(
                scheduleMapper, obligationMapper, runMapper, contractService);
        List<ChapterScheduleEntry> entries = List.of(
                new ChapterScheduleEntry(1, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(2, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(3, 1, true, List.of(), List.of("GM-CLUE-01")));

        service.persist(run, entries);

        ArgumentCaptor<DeriveStoryObligationDO> captor =
                ArgumentCaptor.forClass(DeriveStoryObligationDO.class);
        verify(obligationMapper, times(7)).insertFull(captor.capture());
        List<DeriveStoryObligationDO> rows = captor.getAllValues();
        assertEquals(7, rows.size());
        assertTrue(rows.stream().allMatch(row -> "PLANNED".equals(row.getStatus())));
        assertTrue(rows.stream().allMatch(row -> row.getPayload() != null));
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(59L);
        return run;
    }

    private static StoryContract contract() {
        var scope = new com.zzdzz.novelgen.derive.plan.PlanContracts.ScopeContract(
                com.zzdzz.novelgen.derive.plan.PlanContracts.ScopeDecision.SINGLE_VOLUME,
                1, 3, 2000, List.of("短篇"));
        return new StoryContract(59L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙", "真相", "选择", 3),
                List.of(new StoryContract.Clue("GM-CLUE-01", "纸屑", 1, 3, "C1"),
                        new StoryContract.Clue("GM-CLUE-02", "值班表", 1, 2, "C2"),
                        new StoryContract.Clue("GM-CLUE-03", "脚印", 2, 3, "C3")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-02", "CLUE", 1, 1, "C2"),
                        new StoryContract.ThreadRef("GM-CLUE-03", "CLUE", 1, 1, "C3")),
                List.of(), List.of(), scope);
    }
}
