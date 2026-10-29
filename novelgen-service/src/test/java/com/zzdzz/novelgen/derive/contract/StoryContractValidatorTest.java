package com.zzdzz.novelgen.derive.contract;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合同纯校验器：结构规则、漂移判定与下游一致性（contract-first 计划 §1/§4/§6）。 */
class StoryContractValidatorTest {

    private static final PlanContracts.ScopeContract SCOPE = new PlanContracts.ScopeContract(
            PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 12, 2600, List.of("单案短篇"));

    private static StoryContract contract() {
        return contract(
                List.of(slot("C1", "林默", "DETECTIVE"), slot("C2", "苏晚", "ACCESS"),
                        slot("C3", "陆敬", "OPPOSITION")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙",
                        "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11),
                clues(), threads(), List.of("不得出现第二起案件"), volumeTruth());
    }

    private static StoryContract.CharacterSlot slot(String key, String name, String role) {
        return new StoryContract.CharacterSlot(key, name, role, "男", "身份" + key);
    }

    private static List<StoryContract.Clue> clues() {
        return List.of(
                new StoryContract.Clue("GM-CLUE-01", "门缝里的纸屑", 3, 10, "C1"),
                new StoryContract.Clue("GM-CLUE-02", "值班表被涂改", 5, 11, "C1"),
                new StoryContract.Clue("GM-CLUE-03", "旧债欠条", 7, 11, "C2"));
    }

    private static List<StoryContract.ThreadRef> threads() {
        return List.of(
                new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1"),
                new StoryContract.ThreadRef("GM-CLUE-02", "CLUE", 1, 1, "C1"),
                new StoryContract.ThreadRef("GM-CLUE-03", "CLUE", 1, 1, "C2"));
    }

    private static List<StoryContract.VolumeTruth> volumeTruth() {
        return List.of(new StoryContract.VolumeTruth(1, "陆敬为掩盖旧账制造失踪",
                List.of("不得出现第二起案件")));
    }

    private static StoryContract contract(List<StoryContract.CharacterSlot> cast,
                                          StoryContract.Truth truth,
                                          List<StoryContract.Clue> clues,
                                          List<StoryContract.ThreadRef> threads,
                                          List<String> forbidden,
                                          List<StoryContract.VolumeTruth> volumeTruth) {
        return new StoryContract(58L, 1, "FROZEN", cast, truth, clues, threads, forbidden,
                volumeTruth, SCOPE);
    }

    private static List<String> errors(StoryContract value) {
        return StoryContractValidator.validate(value, SCOPE);
    }

    @Test
    void validContractHasNoErrors() {
        assertTrue(errors(contract()).isEmpty(), String.join("；", errors(contract())));
    }

    @Test
    void exactlyThreeNamedCharactersRequired() {
        List<StoryContract.CharacterSlot> cast = new ArrayList<>(contract().cast());
        cast.add(slot("C4", "路人甲", "SUPPORT"));
        StoryContract broken = contract(cast, contract().truth(), contract().clues(),
                contract().threads(), contract().forbidden(), contract().volumeTruth());

        assertTrue(errors(broken).stream().anyMatch(e -> e.startsWith("CONTRACT_CAST_LIMIT")),
                String.join("；", errors(broken)));
    }

    @Test
    void culpritMustBeInCast() {
        StoryContract.Truth truth = new StoryContract.Truth("C9", "陆敬", "旧账", "利用换班间隙",
                "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11);
        StoryContract broken = contract(contract().cast(), truth, contract().clues(),
                contract().threads(), contract().forbidden(), contract().volumeTruth());

        assertTrue(errors(broken).stream()
                .anyMatch(e -> e.startsWith("CONTRACT_CULPRIT_NOT_IN_CAST")));
    }

    @Test
    void clueChaptersMustBeInScopeAndPayoffAfterPlant() {
        List<StoryContract.Clue> clues = new ArrayList<>();
        clues.add(new StoryContract.Clue("GM-CLUE-01", "门缝里的纸屑", 0, 10, "C1"));
        clues.add(contract().clues().get(1));
        clues.add(contract().clues().get(2));
        StoryContract broken = contract(contract().cast(), contract().truth(), clues,
                contract().threads(), contract().forbidden(), contract().volumeTruth());

        assertTrue(errors(broken).stream().anyMatch(e -> e.startsWith("CONTRACT_CLUE_RANGE")),
                String.join("；", errors(broken)));
    }

    @Test
    void forbiddenMustBeJsonStringArrayNotEncodedString() {
        StoryContract broken = contract(contract().cast(), contract().truth(), contract().clues(),
                contract().threads(), List.of("[\"不得出现死亡\"]"), contract().volumeTruth());

        assertTrue(errors(broken).stream()
                .anyMatch(e -> e.startsWith("CONTRACT_FORBIDDEN_SHAPE")));
    }

    @Test
    void multiVolumeContractRequiresEveryVolumeSlice() {
        PlanContracts.ScopeContract multi = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.MULTI_VOLUME, 3, 36, 2600, List.of("三卷"));
        StoryContract broken = new StoryContract(58L, 1, "FROZEN", contract().cast(),
                contract().truth(), contract().clues(), contract().threads(),
                contract().forbidden(), volumeTruth(), multi);

        List<String> errs = StoryContractValidator.validate(broken, multi);
        assertTrue(errs.stream().anyMatch(e -> e.startsWith("CONTRACT_VOLUME_TRUTH_MISSING")),
                String.join("；", errs));
    }

    @Test
    void frozenContractCannotChangeCulpritOrMethod() {
        StoryContract.Truth changed = new StoryContract.Truth("C3", "陆敬", "旧账",
                "利用消防通道", "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11);
        StoryContract other = contract(contract().cast(), changed, contract().clues(),
                contract().threads(), contract().forbidden(), contract().volumeTruth());

        assertFalse(StoryContractValidator.sameCanonical(contract(), other),
                "method 变化必须判为合同漂移");
        assertTrue(StoryContractValidator.sameCanonical(contract(), contract()));
    }

    @Test
    void characterPlanMustMatchContractSlot() {
        List<PlanContracts.CharacterContract> characters = List.of(
                character("C1", PlanContracts.CharacterFunction.DETECTIVE),
                character("C2", PlanContracts.CharacterFunction.ACCESS),
                character("C4", PlanContracts.CharacterFunction.SUPPORT));

        List<String> errors = StoryContractValidator.validateCharacters(characters, contract());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("CONTRACT_CAST_DRIFT")),
                String.join("；", errors));
    }

    @Test
    void characterFunctionMustMatchContractSlot() {
        List<PlanContracts.CharacterContract> characters = List.of(
                character("C1", PlanContracts.CharacterFunction.ACCESS),
                character("C2", PlanContracts.CharacterFunction.ACCESS),
                character("C3", PlanContracts.CharacterFunction.OPPOSITION));

        List<String> errors = StoryContractValidator.validateCharacters(characters, contract());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("CONTRACT_ROLE_DRIFT")),
                String.join("；", errors));
    }

    @Test
    void planWithContractThreadOutsideContractIsDrift() {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 12, "卷弧", "问题", "假答", "别的案情", "推进", List.of("C1"), List.of(),
                List.of("GM-CLUE-99"), List.of(), "闭合", "", "FINAL");

        List<String> errors = StoryContractValidator.validateAgainstPlan(contract(),
                List.of(volume), List.of(), List.of());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("CONTRACT_THREAD_DRIFT")),
                String.join("；", errors));
    }

    @Test
    void spoilerBeforeRevealIsContractInvalid() {
        ChapterDO chapter = new ChapterDO();
        chapter.setChapterNo(3);
        chapter.setDraftOutline("林默发现陆敬利用换班间隙进入仓库");

        List<String> errors = StoryContractValidator.validateAgainstPlan(contract(),
                List.of(), List.of(), List.of(chapter));
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("CONTRACT_SPOILER")),
                String.join("；", errors));
    }

    private static PlanContracts.CharacterContract character(
            String key, PlanContracts.CharacterFunction function) {
        return new PlanContracts.CharacterContract(key, "名字", function, "不可替代", "想要",
                "缺陷", List.of(), List.of(), "冲突", "选择", "代价", List.of(), "兑现",
                1, 1, 1, "START", "PAYOFF", "身份");
    }

    @Test
    void errorCodesAreStableForCallers() {
        List<StoryContract.CharacterSlot> cast = new ArrayList<>(contract().cast());
        cast.add(slot("C4", "路人甲", "SUPPORT"));
        StoryContract broken = contract(cast, contract().truth(), contract().clues(),
                contract().threads(), contract().forbidden(), contract().volumeTruth());

        assertEquals("CONTRACT_CAST_LIMIT", errors(broken).get(0).split("：")[0]);
    }

    private static StoryContract freezeContract(
            StoryContract.Truth truth, List<StoryContract.Clue> clues) {
        return new StoryContract(58L, 1, "FROZEN", contract().cast(), truth, clues,
                contract().threads(), contract().forbidden(), contract().volumeTruth(), SCOPE);
    }

    @Test
    void freezeRejectsRevealWithoutPayoff() {
        List<StoryContract.Clue> clues = List.of(
                new StoryContract.Clue("GM-CLUE-01", "门缝里的纸屑", 3, 10, "C1"),
                new StoryContract.Clue("GM-CLUE-02", "值班表被涂改", 5, 10, "C1"),
                new StoryContract.Clue("GM-CLUE-03", "旧债欠条", 7, 10, "C2"));
        StoryContract.Truth truth = new StoryContract.Truth("C3", "陆敬", "旧账",
                "利用换班间隙", "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11,
                "掩盖旧账", "换班间隙作案");

        List<String> errors = StoryContractValidator.validateForFreeze(
                freezeContract(truth, clues), SCOPE);

        assertTrue(errors.stream().anyMatch(e -> e.startsWith("CONTRACT_REVEAL_NO_PAYOFF")),
                String.join("；", errors));
    }

    @Test
    void freezeRejectsBlankTags() {
        StoryContract.Truth truth = new StoryContract.Truth("C3", "陆敬", "旧账",
                "利用换班间隙", "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11, "", "");
        StoryContract value = freezeContract(truth, contract().clues());

        List<String> errors = StoryContractValidator.validateForFreeze(value, SCOPE);

        assertTrue(errors.stream().filter(e -> e.startsWith("CONTRACT_TAG_MISSING")).count() == 2,
                String.join("；", errors));
    }

    @Test
    void loadStillAcceptsLegacyContractWithoutTags() {
        assertTrue(StoryContractValidator.validate(contract(), SCOPE).isEmpty(),
                "旧合同缺 tags 必须继续可读");
        assertTrue(StoryContractValidator.validateForFreeze(contract(), SCOPE).stream()
                .anyMatch(e -> e.startsWith("CONTRACT_TAG_MISSING")));
    }

    @Test
    void tagsAreNotCanonicalForDriftDetection() {
        StoryContract.Truth tagged = new StoryContract.Truth("C3", "陆敬", "旧账",
                "利用换班间隙", "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11,
                "掩盖旧账", "换班间隙作案");
        StoryContract other = freezeContract(tagged, contract().clues());

        assertTrue(StoryContractValidator.sameCanonical(contract(), other),
                "tags 是校验辅助字段，不参与案情漂移判定");
    }
}
