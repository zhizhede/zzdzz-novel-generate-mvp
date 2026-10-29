package com.zzdzz.novelgen.derive.plan.compiler;

import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对计划做逐章知识、状态、线索与线程回放。
 *
 * <p>模拟只读，不生成叙事、不写库；所有硬问题都追加进结果，绝不因单个问题中断整批检查。
 */
@Component
public class ContinuitySimulator {

    /** 一次完整连续性模拟的可重复结果。 */
    public record SimulationResult(
            List<PlanGraphRecords.StateSnapshot> chapterStates,
            List<PlanGraphRecords.KnowledgeEvent> knowledgeEvents,
            List<PlanGraphRecords.ThreadEvent> threadEvents,
            List<PlanContracts.PlanIssue> issues) {

        public SimulationResult {
            chapterStates = copy(chapterStates);
            knowledgeEvents = copy(knowledgeEvents);
            threadEvents = copy(threadEvents);
            issues = copy(issues);
        }

        private static <T> List<T> copy(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    /** 按合同与最终拍序模拟知识/状态/线索/线程。 */
    public SimulationResult simulate(StoryContract contract, List<PlanGraphRecords.PlannedBeat> beats) {
        Objects.requireNonNull(contract, "contract");
        List<PlanGraphRecords.PlannedBeat> ordered =
                beats == null ? List.of() : beats.stream()
                        .sorted(Comparator
                                .comparingInt(PlanGraphRecords.PlannedBeat::chapterNo)
                                .thenComparingInt(PlanGraphRecords.PlannedBeat::sceneNo)
                                .thenComparing(PlanGraphRecords.PlannedBeat::beatKey)
                                .thenComparingInt(PlanGraphRecords.PlannedBeat::seq))
                        .toList();

        Map<String, CharacterState> states = new LinkedHashMap<>();
        Map<String, ThreadState> threads = new LinkedHashMap<>();
        Map<String, Integer> plantCounts = new HashMap<>();
        Map<String, Integer> payoffCounts = new HashMap<>();
        Map<String, String> plantChapters = new HashMap<>();
        Map<String, String> payoffChapters = new HashMap<>();
        List<PlanGraphRecords.StateSnapshot> snapshots = new ArrayList<>();
        List<PlanGraphRecords.KnowledgeEvent> knowledgeEvents = new ArrayList<>();
        List<PlanGraphRecords.ThreadEvent> threadEvents = new ArrayList<>();
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        Set<Integer> emittedChapters = new HashSet<>();

        for (PlanGraphRecords.PlannedBeat beat : ordered) {
            CharacterState.validateAppearance(beat, states, issues);
            validateKnowledgeReads(beat, states, issues);

            for (PlanGraphRecords.KnowledgeEffect effect : beat.knowledgeEffects()) {
                if (!beat.focusCharacters().containsAll(effect.characterKeys())) {
                    issues.add(issue(PlanCompilerSupport.KNOWLEDGE_SOURCE_MISSING, beat,
                            "KnowledgeEffect 接收者必须属于 focusCharacters: " + effect.characterKeys()));
                }
                for (String character : effect.characterKeys()) {
                    states.computeIfAbsent(character, ignored -> new CharacterState())
                            .knowledge.add(effect.fact());
                }
                knowledgeEvents.add(new PlanGraphRecords.KnowledgeEvent(
                        beat.chapterNo(), beat.beatKey(), effect.characterKeys(), effect.fact()));
            }
            for (String fact : beat.writesKnowledge()) {
                if (beat.focusCharacters().isEmpty()) {
                    issues.add(issue(PlanCompilerSupport.KNOWLEDGE_SOURCE_MISSING, beat,
                            "写出知识的拍必须有 focusCharacters: " + fact));
                }
                for (String character : beat.focusCharacters()) {
                    states.computeIfAbsent(character, ignored -> new CharacterState())
                            .knowledge.add(fact);
                }
                knowledgeEvents.add(new PlanGraphRecords.KnowledgeEvent(
                        beat.chapterNo(), beat.beatKey(), beat.focusCharacters(), fact));
            }

            for (PlanGraphRecords.StateEffect effect : beat.stateEffects()) {
                CharacterState state = states.computeIfAbsent(
                        effect.characterKey(), ignored -> new CharacterState());
                state.apply(effect, beat, issues);
            }

            trackClues(contract, beat, plantCounts, payoffCounts,
                    plantChapters, payoffChapters, issues);
            trackThreads(contract, beat, threads, threadEvents, issues);

            if (!emittedChapters.add(beat.chapterNo())) {
                continue;
            }
            snapshots.add(snapshotOf(beat.chapterNo(), states));
        }

        validateClueCompleteness(contract, plantCounts, payoffCounts, issues);
        validateThreadCompleteness(contract, threads, issues);
        validateRevealBoundary(contract, ordered, issues);
        return new SimulationResult(snapshots, knowledgeEvents, threadEvents, issues);
    }

    private static void validateKnowledgeReads(
            PlanGraphRecords.PlannedBeat beat,
            Map<String, CharacterState> states,
            List<PlanContracts.PlanIssue> issues) {
        if (beat.requiresKnowledge().isEmpty()) {
            return;
        }
        for (String required : beat.requiresKnowledge()) {
            boolean known = beat.focusCharacters().stream()
                    .map(character -> states.get(character))
                    .filter(Objects::nonNull)
                    .anyMatch(state -> state.knows(required));
            if (!known) {
                issues.add(issue(PlanCompilerSupport.KNOWLEDGE_SOURCE_MISSING, beat,
                        "读取知识早于写入来源: " + required));
            }
        }
    }

    private static void trackClues(
            StoryContract contract,
            PlanGraphRecords.PlannedBeat beat,
            Map<String, Integer> plantCounts,
            Map<String, Integer> payoffCounts,
            Map<String, String> plantChapters,
            Map<String, String> payoffChapters,
            List<PlanContracts.PlanIssue> issues) {
        if (beat.beatRole() == PlanContracts.BeatRole.CLUE_PLANT) {
            for (String clue : beat.threadRefs()) {
                plantCounts.merge(clue, 1, Integer::sum);
                plantChapters.putIfAbsent(clue, beat.beatKey());
            }
        }
        if (beat.beatRole() == PlanContracts.BeatRole.CLUE_PAYOFF
                || beat.beatRole() == PlanContracts.BeatRole.FINAL_REVEAL) {
            List<String> clues = beat.threadRefs().isEmpty()
                    ? (beat.payoffTarget().isBlank() ? List.of() : List.of(beat.payoffTarget()))
                    : beat.threadRefs();
            for (String clue : clues) {
                if (clue != null && !clue.isBlank()) {
                    payoffCounts.merge(clue, 1, Integer::sum);
                    payoffChapters.putIfAbsent(clue, beat.beatKey());
                }
            }
        }
    }

    private static void validateClueCompleteness(
            StoryContract contract,
            Map<String, Integer> plantCounts,
            Map<String, Integer> payoffCounts,
            List<PlanContracts.PlanIssue> issues) {
        for (StoryContract.Clue clue : contract.clues()) {
            int plants = plantCounts.getOrDefault(clue.clueKey(), 0);
            int payoffs = payoffCounts.getOrDefault(clue.clueKey(), 0);
            if (plants != 1) {
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.CLUE_PLANT_MISSING,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        null, clue.plantedChapter(), List.of(),
                        "线索必须恰好埋设一次: " + clue.clueKey() + "，实际 " + plants));
            }
            if (payoffs != 1) {
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.CLUE_PAYOFF_MISSING,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        null, clue.payoffChapter(), List.of(),
                        "线索必须恰好兑现一次: " + clue.clueKey() + "，实际 " + payoffs));
            }
        }
    }

    private static void trackThreads(
            StoryContract contract,
            PlanGraphRecords.PlannedBeat beat,
            Map<String, ThreadState> threads,
            List<PlanGraphRecords.ThreadEvent> events,
            List<PlanContracts.PlanIssue> issues) {
        PlanContracts.ThreadEffectKind effect = threadEffect(beat);
        if (effect == null) {
            return;
        }
        for (String threadKey : beat.threadRefs()) {
            ThreadState state = threads.computeIfAbsent(threadKey, ignored -> new ThreadState());
            if (!state.accept(effect, beat)) {
                String code = effect == PlanContracts.ThreadEffectKind.OPEN
                        || (effect == PlanContracts.ThreadEffectKind.ECHO && !state.opened)
                        ? PlanCompilerSupport.THREAD_OPEN
                        : PlanCompilerSupport.THREAD_PAYOFF_MISSING;
                issues.add(issue(code, beat,
                        "线程状态非法: " + threadKey + " -> " + effect));
            } else {
                events.add(new PlanGraphRecords.ThreadEvent(
                        beat.chapterNo(), beat.beatKey(), threadKey, effect));
            }
        }
    }

    private static PlanContracts.ThreadEffectKind threadEffect(PlanGraphRecords.PlannedBeat beat) {
        return switch (beat.beatRole()) {
            case CLUE_PLANT -> PlanContracts.ThreadEffectKind.OPEN;
            case CLUE_ECHO -> PlanContracts.ThreadEffectKind.ECHO;
            case CLUE_PAYOFF, FINAL_REVEAL -> PlanContracts.ThreadEffectKind.PAYOFF;
            case FINAL_CLOSURE -> PlanContracts.ThreadEffectKind.CLOSE;
            default -> null;
        };
    }

    private static void validateThreadCompleteness(
            StoryContract contract,
            Map<String, ThreadState> threads,
            List<PlanContracts.PlanIssue> issues) {
        Set<String> contractKeys = new HashSet<>();
        contract.threads().forEach(thread -> contractKeys.add(thread.threadKey()));
        contract.clues().forEach(clue -> contractKeys.add(clue.clueKey()));
        for (String threadKey : contractKeys) {
            ThreadState state = threads.get(threadKey);
            if (state != null && state.opened && !state.resolved) {
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.THREAD_PAYOFF_MISSING,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        null, null, List.of(),
                        "线程打开后必须 payoff/close: " + threadKey));
            }
        }
    }

    private static void validateRevealBoundary(
            StoryContract contract,
            List<PlanGraphRecords.PlannedBeat> beats,
            List<PlanContracts.PlanIssue> issues) {
        if (contract.truth() == null || contract.truth().revealChapter() <= 0) {
            return;
        }
        for (PlanGraphRecords.PlannedBeat beat : beats) {
            if (beat.beatRole() == PlanContracts.BeatRole.FINAL_REVEAL
                    && beat.chapterNo() != contract.truth().revealChapter()) {
                issues.add(issue(PlanCompilerSupport.REVEAL_BOUNDARY, beat,
                        "FINAL_REVEAL 只能发生在合同 revealChapter="
                                + contract.truth().revealChapter()));
            }
        }
    }

    private static PlanGraphRecords.StateSnapshot snapshotOf(
            int chapterNo,
            Map<String, CharacterState> states) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        states.forEach((character, state) -> {
            List<String> facts = state.knowledge.stream().sorted().toList();
            values.put(character, List.copyOf(facts));
            values.put(character + ":alive", List.of(String.valueOf(state.alive)));
            values.put(character + ":location",
                    state.location == null ? List.of() : List.of(state.location));
            values.put(character + ":present", List.of(String.valueOf(!state.departed)));
        });
        Set<String> allKnowledge = new LinkedHashSet<>();
        states.values().forEach(state -> allKnowledge.addAll(state.knowledge));
        return new PlanGraphRecords.StateSnapshot(chapterNo, values, allKnowledge);
    }

    private static PlanContracts.PlanIssue issue(
            String code,
            PlanGraphRecords.PlannedBeat beat,
            String message) {
        return PlanCompilerSupport.blocker(
                code,
                PlanContracts.RepairScope.COMPILE_PLAN,
                beat.volumeNo(),
                beat.chapterNo(),
                List.of(beat.seq()),
                message);
    }

    private static final class CharacterState {
        private final Set<String> knowledge = new LinkedHashSet<>();
        private boolean alive = true;
        private boolean departed;
        private String location = "";
        private final Set<String> possessions = new LinkedHashSet<>();

        private boolean knows(String fact) {
            if (knowledge.contains(fact)) {
                return true;
            }
            return knowledge.stream().anyMatch(known ->
                    known.contains(fact) || fact.contains(known));
        }

        private void apply(
                PlanGraphRecords.StateEffect effect,
                PlanGraphRecords.PlannedBeat beat,
                List<PlanContracts.PlanIssue> issues) {
            switch (effect.kind()) {
                case DEAD -> alive = false;
                case PRESENT -> departed = false;
                case ABSENT -> departed = true;
                case LOCATION -> {
                    if (departed) {
                        issues.add(issue(PlanCompilerSupport.STATE_DEPENDENCY, beat,
                                "离场角色不得再改 location: " + effect.characterKey()));
                    } else {
                        location = effect.detail();
                    }
                }
                case POSSESSION_GAIN -> possessions.add(effect.detail());
                case POSSESSION_LOSS -> possessions.remove(effect.detail());
                case IDENTITY, RELATIONSHIP -> {
                    if (!alive) {
                        issues.add(issue(PlanCompilerSupport.STATE_DEPENDENCY, beat,
                                "死亡角色不得继续状态变化: " + effect.characterKey()));
                    }
                }
            }
        }

        private static void validateAppearance(
                PlanGraphRecords.PlannedBeat beat,
                Map<String, CharacterState> states,
                List<PlanContracts.PlanIssue> issues) {
            if (beat.appearanceMode() != PlanContracts.AppearanceMode.LIVE) {
                return;
            }
            for (String character : beat.focusCharacters()) {
                CharacterState state = states.get(character);
                if (state != null && !state.alive) {
                    issues.add(issue(PlanCompilerSupport.STATE_DEPENDENCY, beat,
                            "死亡角色只能以合同允许的非 LIVE 方式出现: " + character));
                }
            }
        }
    }

    private static final class ThreadState {
        private boolean opened;
        private boolean echoed;
        private boolean resolved;

        private boolean accept(PlanContracts.ThreadEffectKind effect,
                               PlanGraphRecords.PlannedBeat beat) {
            return switch (effect) {
                case OPEN -> {
                    if (opened) {
                        yield false;
                    }
                    opened = true;
                    yield true;
                }
                case ECHO -> {
                    if (!opened || echoed) {
                        yield false;
                    }
                    echoed = true;
                    yield true;
                }
                case PAYOFF, CLOSE -> {
                    if (!opened || resolved) {
                        yield false;
                    }
                    resolved = true;
                    yield true;
                }
            };
        }
    }
}


