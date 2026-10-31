package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.genre.MysteryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysteryProfileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mysteryContractParsesTruthAndClues() throws Exception {
        StoryContract contract = new MysteryProfile().parseContract(MAPPER.readTree(contractJson()),
                scope()).story();

        assertEquals("C3", contract.truth().culpritKey());
        assertEquals("陆敬", contract.truth().culpritName());
        assertEquals("GM-CLUE-01", contract.clues().get(0).clueKey());
        assertEquals(3, contract.clues().get(0).plantedChapter());
        assertEquals(6, contract.clues().get(0).payoffChapter());
    }

    @Test
    void mysteryProjectionCreatesPlantAndPayoffObligations() {
        StoryContract contract = contract();

        List<StoryObligation> obligations = new MysteryProfile()
                .projectObligations(new MysteryContract(contract), List.of());

        assertEquals(3, obligations.size());
        assertEquals("PLANT_THREAD", obligation(obligations, 3, "GM-CLUE-01").kind());
        assertEquals("PAYOFF_THREAD", obligation(obligations, 6, "GM-CLUE-01").kind());
        assertEquals("FINAL_CLOSURE", obligation(obligations, 6, "TRUTH").kind());
    }

    @Test
    void mysteryProjectionRejectsPayoffBeforePlant() {
        JsonNode root = MAPPER.createObjectNode()
                .set("clues", MAPPER.createArrayNode().addObject()
                        .put("clueKey", "GM-CLUE-01")
                        .put("clue", "纸屑")
                        .put("plantedChapter", 5)
                        .put("payoffChapter", 4)
                        .put("ownerKey", "C1"));
        StoryContract contract = new StoryContract(1L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙", "真相", "选择", 6,
                        "掩盖旧账", "换班作案"),
                List.of(new StoryContract.Clue("GM-CLUE-01", "纸屑", 5, 4, "C1")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1")),
                List.of(), List.of(), scope());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new MysteryProfile().projectObligations(new MysteryContract(contract),
                        List.of()));

        assertTrue(error.getMessage().contains("GM-CLUE-01"));
    }

    private static StoryObligation obligation(List<StoryObligation> obligations, int chapterNo,
                                              String targetKey) {
        return obligations.stream()
                .filter(item -> item.chapterNo() == chapterNo && targetKey.equals(item.targetKey()))
                .findFirst().orElseThrow();
    }

    private static PlanContracts.ScopeContract scope() {
        return new PlanContracts.ScopeContract(PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 6,
                2000, List.of("单案短篇"));
    }

    private static StoryContract contract() {
        return new StoryContract(1L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙", "真相", "选择", 6,
                        "掩盖旧账", "换班作案"),
                List.of(new StoryContract.Clue("GM-CLUE-01", "纸屑", 3, 6, "C1")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1")),
                List.of(), List.of(), scope());
    }

    private static String contractJson() {
        return """
                {
                  "cast":[
                    {"key":"C1","name":"林默","role":"DETECTIVE","gender":"男","identity":"值班员"},
                    {"key":"C2","name":"苏晚","role":"ACCESS","gender":"女","identity":"档案员"},
                    {"key":"C3","name":"陆敬","role":"OPPOSITION","gender":"男","identity":"仓管"}
                  ],
                  "truth":{
                    "culpritKey":"C3","culpritName":"陆敬","motive":"掩盖旧账",
                    "method":"利用换班间隙","finalTruth":"陆敬制造失踪","finalChoice":"揭穿",
                    "revealChapter":6,"motiveTag":"掩盖旧账","methodTag":"换班作案"
                  },
                  "clues":[
                    {"clueKey":"GM-CLUE-01","clue":"纸屑","plantedChapter":3,"payoffChapter":6,"ownerKey":"C1"},
                    {"clueKey":"GM-CLUE-02","clue":"值班表","plantedChapter":2,"payoffChapter":5,"ownerKey":"C2"},
                    {"clueKey":"GM-CLUE-03","clue":"脚印","plantedChapter":1,"payoffChapter":6,"ownerKey":"C3"}
                  ],
                  "threads":[
                    {"threadKey":"GM-CLUE-01","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"},
                    {"threadKey":"GM-CLUE-02","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C2"},
                    {"threadKey":"GM-CLUE-03","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C3"}
                  ],
                  "forbidden":["不得出现第二起案件"],
                  "volumeTruth":[{"volumeNo":1,"reveal":"真相","forbidden":[]}]
                }
                """;
    }
}
