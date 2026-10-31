package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryObligationPayloadTest {

    @Test
    void mysteryPayloadKeepsClueKeyAndPhase() {
        List<StoryObligation> obligations = StoryObligationProjector.project(
                new MysteryProfile(), contract(), schedule(), List.of(), List.of());

        assertEquals("GM-CLUE-01", payload(obligations, 2, "PLANT_THREAD", "GM-CLUE-01")
                .get("clueKey"));
        assertEquals("PLANT", payload(obligations, 2, "PLANT_THREAD", "GM-CLUE-01")
                .get("phase"));
        assertEquals("PAYOFF", payload(obligations, 4, "PAYOFF_THREAD", "GM-CLUE-01")
                .get("phase"));
        assertEquals("TRUTH", payload(obligations, 4, "FINAL_CLOSURE", "TRUTH")
                .get("closure"));
    }

    @Test
    void characterPayloadKeepsSetupChoiceAndCost() {
        PlanContracts.CharacterContract character = character();
        List<StoryObligation> obligations = StoryObligationProjector.project(
                new MysteryProfile(), contract(), schedule(), List.of(), List.of(character));

        assertEquals(List.of("先建立档案员身份"),
                payload(obligations, 1, "INTRODUCE_ENTITY", "C1").get("setup"));
        assertEquals("选择公开证据",
                payload(obligations, 1, "CHARACTER_CHOICE", "C1").get("proof"));
        assertEquals("被停职",
                payload(obligations, 2, "CHARACTER_COST", "C1").get("proof"));
        assertEquals(1, payload(obligations, 2, "CHARACTER_COST", "C1").get("afterChapter"));
    }

    @Test
    void volumeClosurePayloadKeepsProof() {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 4, "首卷", "旧案", "假答案", "真答案", "揭露异常",
                List.of("C1"), List.of("信任加深"), List.of("GM-CLUE-01"),
                List.of("提前揭露真相"), "换班记录被公开", "旧账仍未清", "FINAL");
        List<StoryObligation> obligations = StoryObligationProjector.project(
                new MysteryProfile(), contract(), schedule(), List.of(volume), List.of());

        assertEquals("换班记录被公开",
                payload(obligations, 4, "LOCAL_CLOSURE", "V1").get("proof"));
        assertEquals("换班记录被公开",
                payload(obligations, 4, "FINAL_CLOSURE", "V1").get("proof"));
    }

    private static java.util.Map<String, Object> payload(List<StoryObligation> obligations,
                                                          int chapterNo, String kind,
                                                          String targetKey) {
        return obligations.stream()
                .filter(item -> item.chapterNo() == chapterNo
                        && item.kind().equals(kind) && item.targetKey().equals(targetKey))
                .findFirst().orElseThrow()
                .payload();
    }

    private static List<ChapterScheduleEntry> schedule() {
        return List.of(
                new ChapterScheduleEntry(1, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(2, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(3, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(4, 1, true, List.of(), List.of("GM-CLUE-01")));
    }

    private static StoryContract contract() {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 4, 2000, List.of("测试"));
        return new StoryContract(1L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙", "真相", "选择", 4,
                        "掩盖旧账", "换班作案"),
                List.of(new StoryContract.Clue("GM-CLUE-01", "纸屑", 2, 4, "C1")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1")),
                List.of(), List.of(), scope);
    }

    private static PlanContracts.CharacterContract character() {
        return new PlanContracts.CharacterContract(
                "C1", "林默", PlanContracts.CharacterFunction.DETECTIVE, "唯一调查者",
                "查明真相", "冲动", List.of("先建立档案员身份"), List.of("C3"),
                "争执", "选择公开证据", "被停职", List.of("取得苏晚信任"), "真相公开",
                1, 1, 1, "孤僻", "学会信任", "DETECTIVE");
    }
}
