package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMysteryAdapterTest {

    @Test
    void legacyContractLoadsWithoutGenreColumn() {
        StoryContract contract = LegacyMysteryAdapter.toStoryContract(run());

        assertEquals("C3", contract.truth().culpritKey());
        assertEquals("GM-CLUE-01", contract.clues().get(0).clueKey());
    }

    @Test
    void legacyKernelProjectsClueObligations() {
        StoryContract contract = LegacyMysteryAdapter.toStoryContract(run());

        var obligations = LegacyMysteryAdapter.toKernel(contract, List.of(
                new ChapterScheduleEntry(1, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(3, 1, true, List.of(), List.of()))).obligations();

        assertTrue(obligations.stream().anyMatch(item -> "PLANT_THREAD".equals(item.kind())));
        assertTrue(obligations.stream().anyMatch(item -> "PAYOFF_THREAD".equals(item.kind())));
    }

    @Test
    void legacyRunDoesNotRequireNewGenreFields() {
        RunDO run = run();
        assertEquals(null, run.getGenre());
        assertEquals("MYSTERY", LegacyMysteryAdapter.genre(run));
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(59L);
        run.setStepStatus("""
                {"v":1,"steps":{"STORY_CONTRACT":{
                  "scope":{"chapterCount":3,"volumeCount":1},
                  "cast":[{"key":"C1","name":"林默","role":"DETECTIVE","gender":"男","identity":"值班员"},
                          {"key":"C2","name":"苏晚","role":"ACCESS","gender":"女","identity":"档案员"},
                          {"key":"C3","name":"陆敬","role":"OPPOSITION","gender":"男","identity":"仓管"}],
                  "truth":{"culpritKey":"C3","culpritName":"陆敬","motive":"旧账","method":"换班",
                           "finalTruth":"真相","finalChoice":"选择","revealChapter":3,
                           "motiveTag":"掩盖旧账","methodTag":"换班作案"},
                  "clues":[{"clueKey":"GM-CLUE-01","clue":"纸屑","plantedChapter":1,
                            "payoffChapter":3,"ownerKey":"C1"}],
                  "threads":[{"threadKey":"GM-CLUE-01","kind":"CLUE","openedVolume":1,
                              "payoffVolume":1,"ownerKey":"C1"}],
                  "forbidden":[],"volumeTruth":[{"volumeNo":1,"reveal":"真相","forbidden":[]}]
                }}}
                """);
        return run;
    }
}
