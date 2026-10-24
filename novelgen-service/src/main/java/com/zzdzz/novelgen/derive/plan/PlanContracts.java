package com.zzdzz.novelgen.derive.plan;

import java.util.List;
import java.util.Map;

/** 故事规划 V2 的稳定 JSON 与业务合同。 */
public final class PlanContracts {

    private PlanContracts() {
    }

    public enum PlanUnit {
        SCALE,
        SPINE,
        VOLUMES,
        CHARACTERS,
        SLOT_ROUTE,
        VALIDATE,
        NEED_SCOPE,
        DONE,
        LEGACY
    }

    public enum ScopeDecision {
        SINGLE_VOLUME,
        MULTI_VOLUME,
        NEED_CONFIRMATION
    }

    public enum StoryLayer {
        LOCAL_CASE,
        MAIN_MYSTERY,
        CHARACTER_ARC,
        RELATIONSHIP,
        WORLD
    }

    public enum BeatRole {
        HOOK,
        CASE_SETUP,
        CLUE_PLANT,
        CLUE_ECHO,
        CLUE_PAYOFF,
        SUSPECT,
        FALSE_ANSWER,
        RELATION_CONFLICT,
        FUNCTION_USE,
        FUNCTION_FAILURE,
        CHARACTER_CHOICE,
        HIGHLIGHT,
        COST,
        LOCAL_CLOSURE,
        MAIN_ADVANCE,
        NEXT_HOOK,
        FINAL_REVEAL,
        FINAL_CLOSURE
    }

    public enum AppearanceMode {
        LIVE,
        MEMORY,
        DREAM,
        CORPSE,
        RELIC,
        TESTIMONY,
        LETTER,
        RECORDING
    }

    public enum CharacterFunction {
        DETECTIVE,
        ACCESS,
        KNOWLEDGE,
        SOCIAL,
        ACTION,
        CHALLENGE,
        SUPPORT,
        OPPOSITION
    }

    public enum ThreadKind {
        CLUE,
        RELATIONSHIP,
        OBJECT
    }

    public enum ThreadStatus {
        OPEN,
        ECHOED,
        CONFIRMED,
        PAYOFF,
        CLOSED
    }

    public enum RepairScope {
        NONE,
        DRAFT,
        PLAN_VOLUME,
        SPINE,
        SCALE
    }

    public record ScopeContract(
            ScopeDecision decision,
            int volumeCount,
            int chapterCount,
            int chapterAvg,
            List<String> evidence) {
    }

    public record TruthStep(
            int volumeNo,
            List<String> reveals,
            List<String> forbidden,
            List<String> evidenceThreads) {
    }

    public record ThreadContract(
            String threadKey,
            ThreadKind kind,
            String title,
            Integer openedVolume,
            Integer payoffVolume,
            String ownerCharacterKey,
            ThreadStatus status,
            Map<String, Object> contract) {
    }

    public record StorySpine(
            String mainMystery,
            String finalTruth,
            String finalChoice,
            List<TruthStep> truthLadder,
            List<ThreadContract> globalThreads) {
    }

    public record VolumeContract(
            int volumeNo,
            int fromChapter,
            int toChapter,
            String arc,
            String localQuestion,
            String falseAnswer,
            String trueAnswer,
            String mainMysteryAdvance,
            List<String> focusCharacters,
            List<String> relationshipChanges,
            List<String> requiredThreads,
            List<String> forbiddenReveals,
            String localClosureProof,
            String nextHook,
            String globalRole) {
    }

    public record CharacterContract(
            String characterKey,
            String displayNameHint,
            CharacterFunction function,
            String uniqueContribution,
            String want,
            String flaw,
            List<String> requiredSetup,
            List<String> conflictTargets,
            String functionalConflict,
            String highlightChoice,
            String highlightCost,
            List<String> relationshipChanges,
            String payoffProof,
            Integer spotlightVolume,
            Integer conflictVolume,
            Integer payoffVolume,
            String arcBefore,
            String arcAfter) {
    }

    public record ChapterSlotContract(
            int chapterNo,
            String chapterFunction,
            String localCaseGoal,
            String mainMysteryAdvance,
            String characterGoal,
            String relationshipGoal,
            List<BeatRole> requiredBeatRoles,
            List<String> forbiddenAppearances,
            Map<String, Object> requiredStateBefore,
            Map<String, Object> requiredStateAfter) {
    }

    public record BeatAssignment(
            Integer seq,
            Integer chapterNo,
            StoryLayer storyLayer,
            BeatRole beatRole,
            List<String> focusCharacters,
            List<String> threadRefs,
            List<Integer> dependsOn,
            String payoffTarget,
            AppearanceMode appearanceMode,
            List<String> requiresKnowledge,
            List<String> writesKnowledge,
            Map<String, Object> requiresState,
            Map<String, Object> changesState) {
    }

    public record SkipAssignment(Integer seq, String reason) {
    }

    public record SyntheticAssignment(
            Integer chapterNo,
            StoryLayer storyLayer,
            BeatRole beatRole,
            String beatType,
            String goal,
            List<String> focusCharacters,
            List<String> threadRefs,
            AppearanceMode appearanceMode) {
    }

    public record RoutePlan(
            int volumeNo,
            List<ChapterSlotContract> chapters,
            List<BeatAssignment> assignments,
            List<SyntheticAssignment> synthetic,
            List<SkipAssignment> skipped) {
    }

    public record PlanIssue(
            String level,
            String code,
            RepairScope repairScope,
            Integer volumeNo,
            Integer chapterNo,
            List<Integer> seqs,
            String message) {
    }

    public record PlanV2State(
            PlanUnit currentUnit,
            List<PlanUnit> completedUnits,
            ScopeContract scope,
            Map<Integer, PlanUnit> volumeUnits) {
    }
}
