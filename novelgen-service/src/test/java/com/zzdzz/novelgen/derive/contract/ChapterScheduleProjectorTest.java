package com.zzdzz.novelgen.derive.contract;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 章级日程投影器：合同 → 每章 mustPlant/mustPayoff/reveal 的纯函数规则。 */
class ChapterScheduleProjectorTest {

    private static final PlanContracts.ScopeContract SCOPE = new PlanContracts.ScopeContract(
            PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 13, 2000, List.of("单案短篇"));

    @Test
    void projectsPlantPayoffAndReveal() {
        StoryContract contract = contract(clues(), threads(1, 1), 11, SCOPE);

        List<ChapterScheduleEntry> schedule = ChapterScheduleProjector.project(
                contract, 13, List.of(new ScaffoldContract.VolumeRange(1, 1, 13)));

        assertEquals(13, schedule.size(), "每章恰好一条日程");
        assertEquals(List.of("GM-CLUE-01"), entry(schedule, 3).mustPlant());
        assertTrue(entry(schedule, 3).mustPayoff().isEmpty());
        assertEquals(List.of("GM-CLUE-01"), entry(schedule, 10).mustPayoff());
        assertTrue(entry(schedule, 11).reveal(), "揭示章必须标 reveal");
        assertFalse(entry(schedule, 10).reveal());
        assertEquals(List.of("GM-CLUE-02", "GM-CLUE-03"), entry(schedule, 11).mustPayoff());
        assertEquals(List.of("GM-CLUE-04", "GM-CLUE-05"), entry(schedule, 12).mustPayoff());
        assertEquals(List.of("GM-CLUE-06"), entry(schedule, 13).mustPayoff());
        assertTrue(entry(schedule, 1).mustPlant().isEmpty(), "合同没声明的章不得凭空出现埋点");
        assertTrue(schedule.stream().allMatch(item -> item.volumeNo() == 1));
    }

    @Test
    void rejectsClueOutOfRange() {
        List<StoryContract.Clue> clues = new ArrayList<>(clues());
        clues.set(0, new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 3, 20, "C1"));

        BizException error = assertThrows(BizException.class,
                () -> ChapterScheduleProjector.project(
                        contract(clues, threads(1, 1), 11, SCOPE), 13,
                        List.of(new ScaffoldContract.VolumeRange(1, 1, 13))));

        assertTrue(error.getMessage().contains("CONTRACT_SCHEDULE_INVALID"), error.getMessage());
        assertTrue(error.getMessage().contains("GM-CLUE-01"), error.getMessage());
    }

    @Test
    void rejectsPayoffBeforePlant() {
        List<StoryContract.Clue> clues = new ArrayList<>(clues());
        clues.set(1, new StoryContract.Clue("GM-CLUE-02", "值班表涂改", 9, 6, "C1"));

        BizException error = assertThrows(BizException.class,
                () -> ChapterScheduleProjector.project(
                        contract(clues, threads(1, 1), 11, SCOPE), 13,
                        List.of(new ScaffoldContract.VolumeRange(1, 1, 13))));

        assertTrue(error.getMessage().contains("CONTRACT_SCHEDULE_INVALID"), error.getMessage());
        assertTrue(error.getMessage().contains("晚于"), error.getMessage());
    }

    @Test
    void rejectsThreadVolumeMismatch() {
        // 章号推出 open=1/payoff=1，但线程写成 open=2：合同自相矛盾，必须拒绝。
        List<StoryContract.ThreadRef> threads = new ArrayList<>(threads(1, 1));
        threads.set(0, new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 2, 2, "C1"));

        BizException error = assertThrows(BizException.class,
                () -> ChapterScheduleProjector.project(
                        contract(clues(), threads, 11, SCOPE), 13,
                        List.of(new ScaffoldContract.VolumeRange(1, 1, 13))));

        assertTrue(error.getMessage().contains("卷号不一致"), error.getMessage());
    }

    @Test
    void rejectsMissingThreadForClue() {
        List<StoryContract.ThreadRef> threads = threads(1, 1).subList(1, 6);

        BizException error = assertThrows(BizException.class,
                () -> ChapterScheduleProjector.project(
                        contract(clues(), threads, 11, SCOPE), 13,
                        List.of(new ScaffoldContract.VolumeRange(1, 1, 13))));

        assertTrue(error.getMessage().contains("没有同 key 线程"), error.getMessage());
    }

    @Test
    void multiVolumeRangesCoverAllChapters() {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.MULTI_VOLUME, 2, 12, 2000, List.of("两卷"));
        List<StoryContract.Clue> clues = List.of(
                new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 2, 9, "C1"),
                new StoryContract.Clue("GM-CLUE-02", "值班表涂改", 4, 12, "C1"),
                new StoryContract.Clue("GM-CLUE-03", "旧债欠条", 5, 7, "C2"));
        List<StoryContract.ThreadRef> threads = List.of(
                new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 2, "C1"),
                new StoryContract.ThreadRef("GM-CLUE-02", "CLUE", 1, 2, "C1"),
                new StoryContract.ThreadRef("GM-CLUE-03", "CLUE", 1, 2, "C2"));

        List<ChapterScheduleEntry> schedule = ChapterScheduleProjector.project(
                contract(clues, threads, 10, scope), 12, List.of(
                        new ScaffoldContract.VolumeRange(2, 7, 12),
                        new ScaffoldContract.VolumeRange(1, 1, 6)));

        assertEquals(12, schedule.size());
        assertEquals(1, entry(schedule, 2).volumeNo());
        assertEquals(2, entry(schedule, 9).volumeNo());
        assertEquals(2, entry(schedule, 7).volumeNo());
        assertEquals(List.of("GM-CLUE-03"), entry(schedule, 7).mustPayoff());
        assertEquals(List.of("GM-CLUE-02"), entry(schedule, 12).mustPayoff());
        assertEquals(2, entry(schedule, 10).volumeNo());
        assertTrue(entry(schedule, 10).reveal());
    }

    @Test
    void singleVolumeAllVolumeOne() {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 13, 2000, List.of("单卷"));

        List<ChapterScheduleEntry> schedule = ChapterScheduleProjector.project(
                contract(clues(), threads(1, 1), 11, scope), 13,
                List.of(new ScaffoldContract.VolumeRange(1, 1, 13)));

        assertTrue(schedule.stream().allMatch(item -> item.volumeNo() == 1));
    }

    @Test
    void rejectsRangesWithGap() {
        BizException error = assertThrows(BizException.class,
                () -> ChapterScheduleProjector.project(
                        contract(clues(), threads(1, 1), 11, SCOPE), 13,
                        List.of(new ScaffoldContract.VolumeRange(1, 1, 6),
                                new ScaffoldContract.VolumeRange(2, 8, 13))));

        assertTrue(error.getMessage().contains("不连续"), error.getMessage());
    }

    private static ChapterScheduleEntry entry(List<ChapterScheduleEntry> schedule, int chapterNo) {
        return schedule.stream().filter(item -> item.chapterNo() == chapterNo)
                .findFirst().orElseThrow();
    }

    private static StoryContract contract(List<StoryContract.Clue> clues,
                                          List<StoryContract.ThreadRef> threads,
                                          int revealChapter, PlanContracts.ScopeContract scope) {
        return new StoryContract(59L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙",
                        "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", revealChapter),
                clues, threads, List.of("不得出现第二起案件"),
                List.of(new StoryContract.VolumeTruth(1, "陆敬为掩盖旧账制造失踪", List.of())),
                scope);
    }

    private static List<StoryContract.Clue> clues() {
        return List.of(
                new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 3, 10, "C1"),
                new StoryContract.Clue("GM-CLUE-02", "值班表涂改", 5, 11, "C1"),
                new StoryContract.Clue("GM-CLUE-03", "旧债欠条", 6, 11, "C2"),
                new StoryContract.Clue("GM-CLUE-04", "仓库脚印", 4, 12, "C1"),
                new StoryContract.Clue("GM-CLUE-05", "换班记录", 8, 12, "C2"),
                new StoryContract.Clue("GM-CLUE-06", "发带", 9, 13, "C2"));
    }

    private static List<StoryContract.ThreadRef> threads(int open, int payoff) {
        return clues().stream()
                .map(clue -> new StoryContract.ThreadRef(clue.clueKey(), "CLUE", open, payoff,
                        clue.ownerKey()))
                .toList();
    }
}
