package com.zzdzz.novelgen.derive.plan.compiler;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 计划图的稳定记录类型；只表达计划，不执行编译、准入或正文生成。 */
public final class PlanGraphRecords {

    private PlanGraphRecords() {
    }

    /** 结构计划中的一条候选拍。 */
    public record BeatCandidate(
            String beatKey,
            String source,
            Long assetId,
            Long arcId,
            Integer arcSegNo,
            int preferredChapter,
            String beatType,
            String arcSlot,
            BigDecimal pacing,
            List<Integer> dependsOn) {

        public BeatCandidate {
            Objects.requireNonNull(beatKey, "beatKey");
            dependsOn = immutable(dependsOn);
        }
    }

    /** 已计划的一场戏。 */
    public record PlannedScene(
            int sceneNo,
            String beatKey,
            String goal,
            List<String> entryState,
            List<String> exitState,
            List<String> carryKnowledge,
            List<String> revealKnowledge) {

        public PlannedScene {
            if (sceneNo <= 0) {
                throw new IllegalArgumentException("sceneNo must be positive");
            }
            Objects.requireNonNull(beatKey, "beatKey");
            entryState = immutable(entryState);
            exitState = immutable(exitState);
            carryKnowledge = immutable(carryKnowledge);
            revealKnowledge = immutable(revealKnowledge);
        }
    }

    /** 已计划的一章；sceneNo 必须从 1 开始连续。 */
    public record PlannedChapter(
            int chapterNo,
            int volumeNo,
            String title,
            int budget,
            List<PlannedScene> scenes) {

        public PlannedChapter {
            if (chapterNo <= 0 || volumeNo <= 0 || budget <= 0) {
                throw new IllegalArgumentException("chapterNo/volumeNo/budget must be positive");
            }
            scenes = immutable(scenes);
            requireContinuous(scenes, PlannedScene::sceneNo, "sceneNo");
        }
    }

    /** 已计划的一拍；seq 全局连续，章/场号由结构计划冻结。 */
    public record PlannedBeat(
            int seq,
            String beatKey,
            String source,
            Long assetId,
            Long arcId,
            Integer arcSegNo,
            String beatType,
            BigDecimal pacing,
            int chapterNo,
            int volumeNo,
            int sceneNo,
            PlanContracts.StoryLayer storyLayer,
            PlanContracts.BeatRole beatRole,
            PlanContracts.AppearanceMode appearanceMode,
            String goal,
            List<String> focusCharacters,
            List<String> threadRefs,
            List<KnowledgeEffect> knowledgeEffects,
            List<StateEffect> stateEffects,
            List<Integer> dependsOn,
            String payoffTarget,
            List<String> requiresKnowledge,
            List<String> writesKnowledge) {

        public PlannedBeat {
            if (seq <= 0 || chapterNo <= 0 || volumeNo <= 0 || sceneNo <= 0) {
                throw new IllegalArgumentException("seq/chapterNo/volumeNo/sceneNo must be positive");
            }
            Objects.requireNonNull(beatKey, "beatKey");
            Objects.requireNonNull(storyLayer, "storyLayer");
            Objects.requireNonNull(beatRole, "beatRole");
            Objects.requireNonNull(appearanceMode, "appearanceMode");
            focusCharacters = immutable(focusCharacters);
            threadRefs = immutable(threadRefs);
            knowledgeEffects = immutable(knowledgeEffects);
            stateEffects = immutable(stateEffects);
            dependsOn = immutable(dependsOn);
            requiresKnowledge = immutable(requiresKnowledge);
            writesKnowledge = immutable(writesKnowledge);
        }
    }

    /** 拍写入的角色知识。 */
    public record KnowledgeEffect(List<String> characterKeys, String fact) {

        public KnowledgeEffect {
            characterKeys = immutable(characterKeys);
            Objects.requireNonNull(fact, "fact");
        }
    }

    /** 拍引起的角色状态变化。 */
    public record StateEffect(
            String characterKey,
            PlanContracts.StateChangeKind kind,
            String detail) {

        public StateEffect {
            Objects.requireNonNull(characterKey, "characterKey");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** 拍对故事线的作用。 */
    public record ThreadEffect(
            String threadKey,
            PlanContracts.ThreadEffectKind effect,
            String detail) {

        public ThreadEffect {
            Objects.requireNonNull(threadKey, "threadKey");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** 被明确跳过的拍。 */
    public record SkippedBeat(String beatKey, String reason) {

        public SkippedBeat {
            Objects.requireNonNull(beatKey, "beatKey");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 跳拍求解结果。 */
    public record BeatSolveResult(
            List<PlannedBeat> beats,
            List<SkippedBeat> skipped,
            List<PlanContracts.PlanIssue> issues) {

        public BeatSolveResult {
            beats = immutable(beats);
            skipped = immutable(skipped);
            issues = immutable(issues);
        }
    }

    /** 某章结束时的状态快照。 */
    public record StateSnapshot(
            int chapterNo,
            Map<String, List<String>> states,
            Set<String> knowledge) {

        public StateSnapshot {
            if (chapterNo <= 0) {
                throw new IllegalArgumentException("chapterNo must be positive");
            }
            states = states == null ? Map.of() : Map.copyOf(states);
            knowledge = knowledge == null ? Set.of() : Set.copyOf(knowledge);
        }
    }

    /** 知识事件索引。 */
    public record KnowledgeEvent(
            int chapterNo,
            String beatKey,
            List<String> characterKeys,
            String fact) {

        public KnowledgeEvent {
            characterKeys = immutable(characterKeys);
            Objects.requireNonNull(beatKey, "beatKey");
            Objects.requireNonNull(fact, "fact");
        }
    }

    /** 线程事件索引。 */
    public record ThreadEvent(
            int chapterNo,
            String beatKey,
            String threadKey,
            PlanContracts.ThreadEffectKind effect) {

        public ThreadEvent {
            Objects.requireNonNull(beatKey, "beatKey");
            Objects.requireNonNull(threadKey, "threadKey");
            Objects.requireNonNull(effect, "effect");
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> void requireContinuous(List<T> values,
                                              java.util.function.ToIntFunction<T> number,
                                              String label) {
        for (int index = 0; index < values.size(); index++) {
            if (number.applyAsInt(values.get(index)) != index + 1) {
                throw new IllegalArgumentException(label + " must be continuous from 1");
            }
        }
    }
}