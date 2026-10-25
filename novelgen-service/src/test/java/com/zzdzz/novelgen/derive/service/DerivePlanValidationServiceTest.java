package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 写作前角色状态、知识、钩子与线程闭环校验。 */
class DerivePlanValidationServiceTest {

    @Test
    void deadCharacterCannotReturnAsLiveButMemoryAppearanceIsAllowed() {
        Map<String, DerivePlanValidationService.CharacterState> state = Map.of(
                "C2", new DerivePlanValidationService.CharacterState(false, Set.of()));

        var live = DerivePlanValidationService.validateAppearances(
                List.of(step(18, "C2", PlanContracts.AppearanceMode.LIVE, false)), state);
        var memory = DerivePlanValidationService.validateAppearances(
                List.of(step(18, "C2", PlanContracts.AppearanceMode.MEMORY, false)), state);

        assertTrue(live.stream().anyMatch(i -> i.code().equals("CHARACTER_NOT_ALIVE")));
        assertTrue(memory.isEmpty());
    }

    @Test
    void characterCannotUseKnowledgeBeforeLearningIt() {
        var steps = List.of(
                step(3, "C1", PlanContracts.AppearanceMode.LIVE, false,
                        List.of("名单缺页"), List.of()),
                step(4, "C1", PlanContracts.AppearanceMode.LIVE, false,
                        List.of(), List.of("名单缺页")),
                step(5, "C1", PlanContracts.AppearanceMode.LIVE, false,
                        List.of("名单缺页"), List.of()));

        var initial = Map.of("C1", new DerivePlanValidationService.CharacterState(
                true, Set.of()));
        var issues = DerivePlanValidationService.validateAppearances(steps, initial);

        assertTrue(issues.stream().anyMatch(i -> i.code().equals("KNOWLEDGE_NOT_ACQUIRED")
                && i.chapterNo() == 3));
        assertTrue(issues.stream().noneMatch(i -> i.chapterNo() != null && i.chapterNo() == 5));
    }

    @Test
    void appearanceIssueRetainsVolumeForPreciseRepairTarget() {
        var steps = List.of(new DerivePlanValidationService.AppearanceStep(
                18, 2, "C1", PlanContracts.AppearanceMode.LIVE,
                List.of("名单缺页"), List.of(), false, null));
        var initial = Map.of("C1", new DerivePlanValidationService.CharacterState(
                true, Set.of()));

        var issue = DerivePlanValidationService.validateAppearances(steps, initial).get(0);

        assertTrue(issue.volumeNo() == 2);
    }

    @Test
    void threadKeysInRequiresKnowledgeAreNotNarrativeKnowledge() {
        var steps = List.of(step(3, "C1", PlanContracts.AppearanceMode.LIVE, false,
                List.of("GM-CLUE-03"), List.of()));
        var initial = Map.of("C1", new DerivePlanValidationService.CharacterState(
                true, Set.of()));

        assertTrue(DerivePlanValidationService.validateAppearances(steps, initial).isEmpty());
    }

    @Test
    void focusGroupCanShareFactDiscoveredByOneMember() {
        var discovery = step(3, "C1", PlanContracts.AppearanceMode.LIVE, false,
                List.of(), List.of("名单缺页"));
        var group = new DerivePlanValidationService.AppearanceStep(
                5, 1, "C2", PlanContracts.AppearanceMode.LIVE,
                List.of("名单缺页"), List.of(), false, 10);
        var groupMate = new DerivePlanValidationService.AppearanceStep(
                5, 1, "C1", PlanContracts.AppearanceMode.LIVE,
                List.of(), List.of(), false, 10);
        var initial = Map.of(
                "C1", new DerivePlanValidationService.CharacterState(true, Set.of()),
                "C2", new DerivePlanValidationService.CharacterState(true, Set.of()));

        var issues = DerivePlanValidationService.validateAppearances(
                List.of(discovery, group, groupMate), initial);

        assertTrue(issues.stream().noneMatch(i -> i.code().equals("KNOWLEDGE_NOT_ACQUIRED")));
    }

    @Test
    void oneCompletedHighLightSatisfiesSharedSpotlightVolume() {
        CharacterPlanDO c1 = character("C1", 1);
        CharacterPlanDO c2 = character("C2", 1);
        Map<String, Set<PlanContracts.BeatRole>> roles = Map.of(
                "C1:1", Set.of(PlanContracts.BeatRole.HIGHLIGHT,
                        PlanContracts.BeatRole.CHARACTER_CHOICE,
                        PlanContracts.BeatRole.COST));

        assertTrue(DerivePlanValidationService.validateCharacterArcs(
                List.of(c1, c2), roles).stream()
                .noneMatch(i -> "HIGHLIGHT_CHAIN_MISSING".equals(i.code())));
    }

    @Test
    void finalBookRejectsOpenRequiredThreadAndMissingNonFinalHook() {
        var volumeIssue = DerivePlanValidationService.validateVolumeHooks(List.of(
                new PlanContracts.VolumeContract(1, 1, 12, "卷", "问", "错", "真",
                        "增量", List.of("C1"), List.of(), List.of(),
                        List.of(), "闭合", "", "LOCAL"),
                new PlanContracts.VolumeContract(2, 13, 24, "卷", "问", "错", "真",
                        "增量", List.of("C1"), List.of(), List.of(),
                        List.of(), "闭合", "", "FINAL")));
        var threadIssue = DerivePlanValidationService.validateThreadClosure(
                List.of(new PlanContracts.ThreadContract(
                        "GM-CLUE-01", PlanContracts.ThreadKind.CLUE, "名单",
                        1, 4, "C1", PlanContracts.ThreadStatus.OPEN, Map.of())),
                Set.of());

        assertTrue(volumeIssue.stream().anyMatch(i -> i.code().equals("NEXT_HOOK_MISSING")));
        assertTrue(threadIssue.stream().anyMatch(i -> i.code().equals("REQUIRED_THREAD_OPEN")));
    }


    @Test
    void missingThreadOwnerOrUnknownRelationIsSpineRepair() {
        var volume = new PlanContracts.VolumeContract(
                1, 1, 12, "卷", "问", "错", "真", "增量",
                List.of("C2"), List.of(), List.of("GM-CLUE-01"), List.of(), "闭合", "", "FINAL");
        var thread = new PlanContracts.ThreadContract(
                "GM-CLUE-01", PlanContracts.ThreadKind.CLUE, "名单",
                1, 1, "C3", PlanContracts.ThreadStatus.OPEN, Map.of());
        var character = new PlanContracts.CharacterContract(
                "C1", "调查者", PlanContracts.CharacterFunction.DETECTIVE, "唯一", "查清", "执着",
                List.of(), List.of("C3"), "冲突", "选择", "代价", List.of("REL-C1-C3"),
                "兑现", 1, 1, 1, "开始", "结束");

        var issues = DerivePlanValidationService.validateContractClosure(
                List.of(volume), List.of(thread), List.of(character));

        assertTrue(issues.stream().anyMatch(i -> "THREAD_OWNER_MISSING".equals(i.code())));
        assertTrue(issues.stream().anyMatch(i -> "VOLUME_CHARACTER_MISSING".equals(i.code())));
        assertTrue(issues.stream().anyMatch(i -> "RELATION_REFERENCE_MISSING".equals(i.code())));
        assertTrue(issues.stream().allMatch(i -> i.repairScope() == PlanContracts.RepairScope.SPINE));
    }    private static DerivePlanValidationService.AppearanceStep step(
            int chapterNo, String characterKey, PlanContracts.AppearanceMode mode, boolean dead) {
        return step(chapterNo, characterKey, mode, dead, List.of(), List.of());
    }

    private static DerivePlanValidationService.AppearanceStep step(
            int chapterNo, String characterKey, PlanContracts.AppearanceMode mode, boolean dead,
            List<String> requires, List<String> writes) {
        return new DerivePlanValidationService.AppearanceStep(
                chapterNo, characterKey, mode, requires, writes, dead, null);
    }

    private static CharacterPlanDO character(String key, int spotlightVolume) {
        CharacterPlanDO row = new CharacterPlanDO();
        row.setCharacterKey(key);
        row.setSpotlightVolume(spotlightVolume);
        return row;
    }
}
