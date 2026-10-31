package com.zzdzz.novelgen.derive.story;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.genre.GenreProfile;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.derive.genre.GenreContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryObligationProjectorTest {

    @Test
    void projectorCreatesCluePlantAndPayoff() {
        List<ChapterScheduleEntry> schedule = schedule(6);
        StoryContract contract = contract(3, 6);

        List<StoryObligation> obligations = StoryObligationProjector.project(
                new MysteryProfile(), contract, schedule, List.of(), List.of());

        assertTrue(has(obligations, 3, "PLANT_THREAD", "GM-CLUE-01"));
        assertTrue(has(obligations, 6, "PAYOFF_THREAD", "GM-CLUE-01"));
    }

    @Test
    void projectorCreatesCharacterChoiceAndCost() {
        PlanContracts.CharacterContract character = new PlanContracts.CharacterContract(
                "C1", "林默", PlanContracts.CharacterFunction.DETECTIVE, "唯一调查者",
                "查明真相", "冲动", List.of("先建立档案员身份"), List.of("C3"),
                "争执", "选择公开证据", "被停职", List.of("取得苏晚信任"), "真相公开",
                1, 1, 1, "孤僻", "学会信任", "DETECTIVE");

        List<StoryObligation> obligations = StoryObligationProjector.project(
                stubProfile(List.of()), contract(), schedule(6), List.of(),
                List.of(character));

        assertTrue(has(obligations, 1, "INTRODUCE_ENTITY", "C1"));
        assertTrue(has(obligations, 1, "CHARACTER_CHOICE", "C1"));
        assertTrue(has(obligations, 2, "CHARACTER_COST", "C1"));
    }

    @Test
    void projectorCreatesLocalAndFinalClosure() {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 6, "首卷", "旧案", "假答案", "真答案", "揭露换班异常",
                List.of("C1"), List.of("信任加深"), List.of("GM-CLUE-01"),
                List.of("提前揭露凶手"), "换班记录被公开", "旧账仍未清", "FINAL");

        List<StoryObligation> obligations = StoryObligationProjector.project(
                stubProfile(List.of()), contract(), schedule(6), List.of(volume), List.of());

        assertTrue(has(obligations, 6, "LOCAL_CLOSURE", "V1"));
        assertTrue(has(obligations, 6, "FINAL_CLOSURE", "V1"));
    }

    @Test
    void projectorRejectsDuplicateObligationKey() {
        StoryObligation duplicate = new StoryObligation(
                StoryObligation.obligationKey(1, "PLANT_THREAD", "GM-CLUE-01"), 1,
                "PLANT_THREAD", "GM-CLUE-01", true, false, "HARD", "TEST", Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StoryObligationProjector.project(stubProfile(List.of(duplicate, duplicate)),
                        contract(), schedule(6), List.of(), List.of()));

        assertTrue(error.getMessage().contains("重复"));
    }

    @Test
    void projectorRejectsObligationOutsideSchedule() {
        StoryObligation outside = new StoryObligation(
                StoryObligation.obligationKey(99, "PLANT_THREAD", "GM-CLUE-01"), 99,
                "PLANT_THREAD", "GM-CLUE-01", true, false, "HARD", "TEST", Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StoryObligationProjector.project(stubProfile(List.of(outside)),
                        contract(), schedule(6), List.of(), List.of()));

        assertTrue(error.getMessage().contains("日程"));
    }

    @Test
    void projectorDoesNotCreateMysterySpecificFields() {
        List<StoryObligation> obligations = StoryObligationProjector.project(
                new MysteryProfile(), contract(), schedule(6), List.of(), List.of());

        assertFalse(obligations.stream().flatMap(item -> item.payload().keySet().stream())
                .anyMatch(key -> List.of("culprit", "motive", "method", "revealChapter")
                        .contains(key)));
    }

    private static boolean has(List<StoryObligation> obligations, int chapterNo, String kind,
                               String targetKey) {
        return obligations.stream().anyMatch(item -> item.chapterNo() == chapterNo
                && kind.equals(item.kind()) && targetKey.equals(item.targetKey()));
    }

    private static GenreProfile<GenreContract> stubProfile(List<StoryObligation> obligations) {
        return new GenreProfile<>() {
            @Override
            public String genre() {
                return "STUB";
            }

            @Override
            public GenreContract parseContract(JsonNode root, PlanContracts.ScopeContract scope) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<StoryObligation> projectObligations(GenreContract contract,
                                                            List<ChapterScheduleEntry> schedule) {
                return obligations;
            }

            @Override
            public List<PlanContracts.PlanIssue> validateContract(GenreContract contract) {
                return List.of();
            }
        };
    }

    private static List<ChapterScheduleEntry> schedule(int chapterCount) {
        List<ChapterScheduleEntry> out = new ArrayList<>();
        for (int chapterNo = 1; chapterNo <= chapterCount; chapterNo++) {
            out.add(new ChapterScheduleEntry(chapterNo, 1, chapterNo == 6, List.of(), List.of()));
        }
        return out;
    }

    private static StoryContract contract(int plant, int payoff) {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 6, 2000, List.of("测试"));
        return new StoryContract(1L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙", "真相", "选择", payoff,
                        "掩盖旧账", "换班作案"),
                List.of(new StoryContract.Clue("GM-CLUE-01", "纸屑", plant, payoff, "C1")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1")),
                List.of(), List.of(), scope);
    }

    private static StoryContract contract() {
        return contract(3, 6);
    }
}
