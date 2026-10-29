package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanV2StateCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合同前置的状态机与下游收敛（contract-first 计划 §3/§4）。 */
class PlanContractConsistencyTest {

    private static final PlanContracts.ScopeContract SCOPE = new PlanContracts.ScopeContract(
            PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 12, 2600, List.of("单案短篇"));

    private static StoryContract contract() {
        return new StoryContract(58L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙",
                        "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11),
                List.of(new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 3, 10, "C1"),
                        new StoryContract.Clue("GM-CLUE-02", "值班表涂改", 5, 11, "C1"),
                        new StoryContract.Clue("GM-CLUE-03", "旧债欠条", 7, 11, "C2")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-02", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-03", "CLUE", 1, 1, "C2")),
                List.of("不得出现第二起案件"),
                List.of(new StoryContract.VolumeTruth(1, "陆敬为掩盖旧账制造失踪",
                        List.of("不得出现第二起案件"))),
                SCOPE);
    }

    @Test
    void contractUnitSitsBetweenScaleAndSpine() {
        assertEquals(PlanContracts.PlanUnit.CONTRACT,
                PlanContracts.PlanUnit.values()[PlanContracts.PlanUnit.SCALE.ordinal() + 1],
                "CONTRACT 必须插在 SCALE 与 SPINE 之间");
        PlanContracts.PlanV2State initial = PlanV2StateCodec.initial();
        assertEquals(PlanContracts.PlanUnit.SCALE, initial.currentUnit());
    }

    @Test
    void volumeTrueAnswerMustEqualContractTruth() {
        PlanContracts.VolumeContract mutated = volume("别的案情");

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> DeriveStoryPlanService.requireVolumeTruthContract(mutated, contract()));

        assertTrue(error.getMessage().contains("CONTRACT_DRIFT"), error.getMessage());
    }

    @Test
    void volumeTrueAnswerMatchingContractPasses() {
        DeriveStoryPlanService.requireVolumeTruthContract(
                volume("陆敬为掩盖旧账制造失踪"), contract());
    }

    @Test
    void volumeThreadsMustComeFromContract() {
        PlanContracts.VolumeContract withUnknown = new PlanContracts.VolumeContract(
                1, 1, 12, "卷弧", "问题", "假答", "陆敬为掩盖旧账制造失踪", "推进",
                List.of("C1"), List.of(), List.of("GM-CLUE-01", "GM-CLUE-99"), List.of(),
                "闭合", "", "FINAL");

        List<PlanContracts.VolumeContract> reconciled = DeriveStoryPlanService.reconcileVolumes(
                List.of(withUnknown), contract(), SCOPE);

        assertEquals(List.of("GM-CLUE-01"), reconciled.get(0).requiredThreads(),
                "合同外线程必须被剔除");
        assertEquals("陆敬为掩盖旧账制造失踪", reconciled.get(0).trueAnswer());
        assertEquals(List.of("不得出现第二起案件"), reconciled.get(0).forbiddenReveals());
        assertEquals(List.of("C1"), reconciled.get(0).focusCharacters());
    }

    @Test
    void characterNamesMustMatchContractCast() {
        List<PlanContracts.CharacterContract> characters = List.of(
                character("C1", PlanContracts.CharacterFunction.DETECTIVE),
                character("C2", PlanContracts.CharacterFunction.ACCESS),
                character("C4", PlanContracts.CharacterFunction.SUPPORT));

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> DeriveStoryPlanService.reconcileCharacters(characters, contract(), SCOPE));

        assertTrue(error.getMessage().contains("CONTRACT_CAST_DRIFT"), error.getMessage());
    }

    @Test
    void characterFunctionIsForcedBackToContractSlot() {
        List<PlanContracts.CharacterContract> characters = List.of(
                character("C1", PlanContracts.CharacterFunction.ACCESS),
                character("C2", PlanContracts.CharacterFunction.ACCESS),
                character("C3", PlanContracts.CharacterFunction.OPPOSITION));

        List<PlanContracts.CharacterContract> reconciled =
                DeriveStoryPlanService.reconcileCharacters(characters, contract(), SCOPE);

        assertEquals(PlanContracts.CharacterFunction.DETECTIVE,
                reconciled.get(0).function(), "职能必须回落到合同槽位");
        assertEquals("林默", reconciled.get(0).displayNameHint(), "姓名必须回落到合同姓名");
    }

    @Test
    void spineIsDeterministicFromContract() {
        PlanContracts.StorySpine first = DeriveStoryPlanService.spineFromContract(contract(), SCOPE);
        PlanContracts.StorySpine second = DeriveStoryPlanService.spineFromContract(contract(), SCOPE);

        assertEquals("陆敬为掩盖旧账制造失踪", first.finalTruth());
        assertEquals(first.finalTruth(), first.mainMystery());
        assertEquals(first.finalTruth(), second.finalTruth(), "同一合同必须得到同一根脊柱");
        assertEquals(1, first.truthLadder().size());
        assertEquals(3, first.globalThreads().size());
        assertTrue(first.globalThreads().stream()
                .allMatch(thread -> thread.ownerCharacterKey().startsWith("C")));
    }

    @Test
    void castCharactersCarryContractKeysOnly() {
        List<PlanContracts.CharacterContract> slots = DeriveStoryPlanService.castCharacters(
                contract(), SCOPE);

        assertEquals(List.of("C1", "C2", "C3"),
                slots.stream().map(PlanContracts.CharacterContract::characterKey).toList());
        assertEquals("林默", slots.get(0).displayNameHint());
    }

    @Test
    void threadContractCarriesClueChapters() {
        PlanContracts.StorySpine spine = DeriveStoryPlanService.spineFromContract(contract(), SCOPE);

        PlanContracts.ThreadContract thread = spine.globalThreads().stream()
                .filter(item -> "GM-CLUE-01".equals(item.threadKey()))
                .findFirst().orElseThrow();

        assertEquals(3, thread.contract().get("plantChapter"), "线程必须带合同埋设章");
        assertEquals(10, thread.contract().get("payoffChapter"), "线程必须带合同兑现章");
    }

    @Test
    void volumeRangeMustMatchScaffold() {
        com.zzdzz.novelgen.derive.contract.ScaffoldContract scaffold =
                new com.zzdzz.novelgen.derive.contract.ScaffoldContract(12, 1,
                        List.of(new com.zzdzz.novelgen.derive.contract.ScaffoldContract.VolumeRange(
                                1, 1, 12)), List.of(), List.of());
        PlanContracts.VolumeContract drifted = new PlanContracts.VolumeContract(
                1, 1, 11, "卷弧", "问题", "假答", "陆敬为掩盖旧账制造失踪", "推进",
                List.of("C1"), List.of(), List.of("GM-CLUE-01"), List.of(), "闭合", "", "FINAL");

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.zzdzz.novelgen.derive.plan.PlanJson.Bad.class,
                () -> DeriveStoryPlanService.validateVolumeSchedule(
                        List.of(drifted), contract(), scaffold));

        assertTrue(error.getMessage().contains("CONTRACT_SCHEDULE_DRIFT"), error.getMessage());
        assertTrue(error.getMessage().contains("scaffold"), error.getMessage());
    }

    @Test
    void clueChaptersMustStayInDeclaredVolume() {
        // 线索 01 的第 1 卷线程却把兑现章写到第 10 章 → 与 1..6 的卷范围冲突。
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 6, "卷弧", "问题", "假答", "陆敬为掩盖旧账制造失踪", "推进",
                List.of("C1"), List.of(), List.of("GM-CLUE-01"), List.of(), "闭合", "", "LOCAL");

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.zzdzz.novelgen.derive.plan.PlanJson.Bad.class,
                () -> DeriveStoryPlanService.validateVolumeSchedule(
                        List.of(volume), contract(), null));

        assertTrue(error.getMessage().contains("兑现章"), error.getMessage());
    }

    private static PlanContracts.VolumeContract volume(String trueAnswer) {
        return new PlanContracts.VolumeContract(1, 1, 12, "卷弧", "问题", "假答", trueAnswer,
                "推进", List.of("C1"), List.of(), List.of("GM-CLUE-01"), List.of(), "闭合", "",
                "FINAL");
    }

    private static PlanContracts.CharacterContract character(
            String key, PlanContracts.CharacterFunction function) {
        return new PlanContracts.CharacterContract(key, "名字", function, "不可替代", "想要",
                "缺陷", List.of(), List.of(), "冲突", "选择", "代价", List.of(), "兑现",
                1, 1, 1, "START", "PAYOFF", "身份");
    }
}