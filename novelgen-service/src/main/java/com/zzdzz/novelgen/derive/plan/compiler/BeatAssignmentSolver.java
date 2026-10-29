package com.zzdzz.novelgen.derive.plan.compiler;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleProjector;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.math.BigDecimal;
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
 * 把合同固定拍与候选拍确定性编译为全局 seq。
 *
 * <p>合同线索 plant/payoff、最终 reveal 与最终 closure 是不可移动事实；候选拍只允许在
 * 合同日程范围内选择章节，依赖、红线和负载都使用整数成本，不引入随机数。
 */
@Component
public class BeatAssignmentSolver {

    private static final BigDecimal DEFAULT_PACE = new BigDecimal("0.50");

    /** 固定结构与剩余候选拍的完整求解。 */
    public PlanGraphRecords.BeatSolveResult solve(
            StoryContract contract,
            PlanContracts.ScopeContract scope,
            ScaffoldContract scaffold,
            List<ChapterScheduleEntry> schedule,
            List<PlanGraphRecords.BeatCandidate> candidates) {
        return solve(contract, scope, scaffold, schedule, candidates, DEFAULT_PACE);
    }

    /** 固定结构与剩余候选拍的完整求解。 */
    public PlanGraphRecords.BeatSolveResult solve(
            StoryContract contract,
            PlanContracts.ScopeContract scope,
            ScaffoldContract scaffold,
            List<ChapterScheduleEntry> schedule,
            List<PlanGraphRecords.BeatCandidate> candidates,
            BigDecimal defaultPace) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(scaffold, "scaffold");
        List<ChapterScheduleEntry> rows =
                schedule == null ? List.of() : List.copyOf(schedule);
        Map<Integer, ChapterScheduleEntry> byChapter = ChapterScheduleProjector.byChapter(rows);
        List<PlanGraphRecords.BeatCandidate> input =
                candidates == null ? List.of() : List.copyOf(candidates);
        BigDecimal pace = PlanCompilerSupport.validPacing(defaultPace, DEFAULT_PACE);
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        List<WorkingBeat> working = new ArrayList<>();
        Map<String, Integer> candidateTempSeq = sortedTempSeq(input, issues);

        addFixedBeats(contract, scope, byChapter, pace, working, issues);

        List<PlanGraphRecords.BeatCandidate> ordered = new ArrayList<>(input);
        ordered.sort(Comparator.comparing(PlanGraphRecords.BeatCandidate::beatKey));
        Set<String> seenKeys = new HashSet<>();
        Map<Integer, Integer> preferredCount = new HashMap<>();
        for (PlanGraphRecords.BeatCandidate candidate : ordered) {
            if (!seenKeys.add(candidate.beatKey())) {
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.BEAT_DUPLICATE,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        scope.volumeCount() > 0 ? 1 : null,
                        candidate.preferredChapter(),
                        List.of(),
                        "候选拍 beatKey 重复: " + candidate.beatKey()));
            }
            String skipReason = PlanCompilerSupport.conflictSkipReason(candidate);
            if (!skipReason.isBlank()) {
                working.add(WorkingBeat.skipped(candidate, skipReason));
                continue;
            }
            int chapter = chooseChapter(candidate, rows, working, preferredCount,
                    scaffold, issues);
            if (chapter <= 0) {
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.BEAT_UNASSIGNED,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        null, candidate.preferredChapter(), List.of(),
                        "候选拍无法分配到合法章: " + candidate.beatKey()));
                working.add(WorkingBeat.skipped(candidate, "无法分配到合法章"));
                continue;
            }
            preferredCount.merge(chapter, 1, Integer::sum);
            WorkingBeat assigned = WorkingBeat.assigned(candidate, chapter,
                    PlanCompilerSupport.roleForBeatType(candidate.beatType(), candidate.arcSlot()),
                    pace);
            assigned.candidateTempSeq = candidateTempSeq.getOrDefault(candidate.beatKey(), 0);
            working.add(assigned);
        }

        rewriteDependencies(working, candidateTempSeq, issues);
        List<PlanGraphRecords.PlannedBeat> beats = finalizeBeats(working);
        List<PlanGraphRecords.SkippedBeat> skipped = working.stream()
                .filter(item -> item.skipped)
                .map(item -> new PlanGraphRecords.SkippedBeat(item.beatKey, item.skipReason))
                .toList();
        return new PlanGraphRecords.BeatSolveResult(beats, skipped, issues);
    }

    private static Map<String, Integer> sortedTempSeq(
            List<PlanGraphRecords.BeatCandidate> input,
            List<PlanContracts.PlanIssue> issues) {
        Map<String, Integer> result = new HashMap<>();
        List<PlanGraphRecords.BeatCandidate> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(PlanGraphRecords.BeatCandidate::beatKey));
        for (int index = 0; index < sorted.size(); index++) {
            result.putIfAbsent(sorted.get(index).beatKey(), index + 1);
        }
        return result;
    }

    private static void addFixedBeats(
            StoryContract contract,
            PlanContracts.ScopeContract scope,
            Map<Integer, ChapterScheduleEntry> schedule,
            BigDecimal pace,
            List<WorkingBeat> working,
            List<PlanContracts.PlanIssue> issues) {
        Set<Integer> chapters = schedule.keySet();
        if (contract.truth() != null) {
            int reveal = contract.truth().revealChapter();
            if (reveal > 0 && chapters.contains(reveal)) {
                working.add(WorkingBeat.fixed(
                        "SYN-FINAL-REVEAL", reveal, "反转",
                        PlanContracts.BeatRole.FINAL_REVEAL,
                        PlanContracts.StoryLayer.MAIN_MYSTERY,
                        contract.truth().culpritKey() == null || contract.truth().culpritKey().isBlank()
                                ? List.of("C1") : List.of(contract.truth().culpritKey()),
                        List.of(), pace, "在合同揭示章完成最终揭示"));
            } else if (reveal > 0) {
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.REVEAL_BOUNDARY,
                        PlanContracts.RepairScope.CONTRACT,
                        null, reveal, List.of(),
                        "合同 revealChapter 不在日程内: " + reveal));
            }
        }
        for (StoryContract.Clue clue : contract.clues()) {
            if (schedule.containsKey(clue.plantedChapter())) {
                working.add(WorkingBeat.fixed(
                        "SYN-CLUE-PLANT-" + clue.clueKey(), clue.plantedChapter(), "铺垫",
                        PlanContracts.BeatRole.CLUE_PLANT,
                        PlanContracts.StoryLayer.MAIN_MYSTERY,
                        clue.ownerKey() == null || clue.ownerKey().isBlank()
                                ? List.of("C1") : List.of(clue.ownerKey()),
                        List.of(clue.clueKey()), pace, "按合同埋设线索 " + clue.clueKey()));
            } else {
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.CLUE_PLANT_MISSING,
                        PlanContracts.RepairScope.CONTRACT,
                        null, clue.plantedChapter(), List.of(),
                        "线索埋设章缺失: " + clue.clueKey()));
            }
            if (schedule.containsKey(clue.payoffChapter())) {
                working.add(WorkingBeat.fixed(
                        "SYN-CLUE-PAYOFF-" + clue.clueKey(), clue.payoffChapter(), "反转",
                        PlanContracts.BeatRole.CLUE_PAYOFF,
                        PlanContracts.StoryLayer.MAIN_MYSTERY,
                        clue.ownerKey() == null || clue.ownerKey().isBlank()
                                ? List.of("C1") : List.of(clue.ownerKey()),
                        List.of(clue.clueKey()), pace, "按合同兑现线索 " + clue.clueKey()));
            } else {
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.CLUE_PAYOFF_MISSING,
                        PlanContracts.RepairScope.CONTRACT,
                        null, clue.payoffChapter(), List.of(),
                        "线索兑现章缺失: " + clue.clueKey()));
            }
        }
        if (!schedule.isEmpty()) {
            int last = schedule.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            working.add(WorkingBeat.fixed(
                    "SYN-FINAL-CLOSURE-" + last, last, "收束",
                    PlanContracts.BeatRole.FINAL_CLOSURE,
                    PlanContracts.StoryLayer.MAIN_MYSTERY,
                    contract.castKeys().isEmpty() ? List.of("C1") : List.of(contract.castKeys().get(0)),
                    List.of(), pace, "在最后一章完成最终闭合"));
        }
    }

    private static int chooseChapter(
            PlanGraphRecords.BeatCandidate candidate,
            List<ChapterScheduleEntry> schedule,
            List<WorkingBeat> working,
            Map<Integer, Integer> preferredCount,
            ScaffoldContract scaffold,
            List<PlanContracts.PlanIssue> issues) {
        if (schedule.isEmpty()) {
            return -1;
        }
        int preferred = candidate.preferredChapter();
        int targetLoad = Math.max(1,
                (int) Math.ceil((double) scaffold.beats().size() / Math.max(1, schedule.size())));
        int best = -1;
        long bestCost = Long.MAX_VALUE;
        for (ChapterScheduleEntry row : schedule) {
            int chapter = row.chapterNo();
            long dependencyPenalty = dependencyPenalty(candidate, chapter, working);
            long preferredDistance = Math.abs(chapter - preferred);
            long roleMismatch = roleMismatch(candidate, chapter, scaffold) ? 1 : 0;
            long load = working.stream().filter(item -> !item.skipped && item.chapterNo == chapter).count()
                    + preferredCount.getOrDefault(candidate.beatKey(), 0);
            long loadPenalty = Math.max(0, load - targetLoad) * 1000;
            long cost = dependencyPenalty * 10000 + preferredDistance * 100
                    + roleMismatch * 10 + loadPenalty;
            if (cost < bestCost || (cost == bestCost && chapter < best)) {
                best = chapter;
                bestCost = cost;
            }
        }
        if (best < 0) {
            issues.add(PlanCompilerSupport.blocker(
                    PlanCompilerSupport.BEAT_UNASSIGNED,
                    PlanContracts.RepairScope.COMPILE_PLAN,
                    null, preferred, List.of(),
                    "候选拍没有可用章节: " + candidate.beatKey()));
        }
        return best;
    }

    private static long dependencyPenalty(
            PlanGraphRecords.BeatCandidate candidate,
            int chapter,
            List<WorkingBeat> working) {
        long penalty = 0;
        for (int dependency : candidate.dependsOn()) {
            WorkingBeat source = working.stream()
                    .filter(item -> item.candidate != null
                            && item.candidateTempSeq == dependency)
                    .findFirst().orElse(null);
            if (source != null && !source.skipped && source.chapterNo > chapter) {
                penalty++;
            }
        }
        return penalty;
    }

    private static boolean roleMismatch(
            PlanGraphRecords.BeatCandidate candidate,
            int chapter,
            ScaffoldContract scaffold) {
        String expected = scaffold.beats().stream()
                .filter(beat -> beat.chapterNo() == chapter)
                .map(ScaffoldBeatType::of)
                .findFirst().orElse("");
        return !expected.isBlank() && !expected.equals(candidate.beatType());
    }

    private static void rewriteDependencies(
            List<WorkingBeat> working,
            Map<String, Integer> candidateTempSeq,
            List<PlanContracts.PlanIssue> issues) {
        Map<Integer, WorkingBeat> byTemp = new HashMap<>();
        for (WorkingBeat beat : working) {
            if (beat.candidate != null) {
                byTemp.putIfAbsent(beat.candidateTempSeq, beat);
            }
        }
        for (WorkingBeat beat : working) {
            if (beat.candidate == null || beat.skipped) {
                continue;
            }
            LinkedHashSet<Integer> rewritten = new LinkedHashSet<>();
            for (int dependency : beat.candidate.dependsOn()) {
                WorkingBeat source = byTemp.get(dependency);
                if (source == null) {
                    issues.add(PlanCompilerSupport.blocker(
                            PlanCompilerSupport.BEAT_DEPENDENCY_MISSING,
                            PlanContracts.RepairScope.COMPILE_PLAN,
                            null, beat.chapterNo, List.of(),
                            "依赖不存在: " + beat.beatKey + " -> " + dependency));
                    continue;
                }
                if (source.skipped) {
                    issues.add(PlanCompilerSupport.blocker(
                            PlanCompilerSupport.BEAT_DEPENDENCY_SKIPPED,
                            PlanContracts.RepairScope.COMPILE_PLAN,
                            null, beat.chapterNo, List.of(),
                            "依赖拍已被跳过: " + beat.beatKey + " -> " + source.beatKey));
                    continue;
                }
                rewritten.add(dependency);
            }
            beat.dependencies = rewritten;
        }
    }

    private static List<PlanGraphRecords.PlannedBeat> finalizeBeats(List<WorkingBeat> working) {
        List<WorkingBeat> assigned = working.stream()
                .filter(item -> !item.skipped)
                .sorted(Comparator
                        .comparingInt((WorkingBeat item) -> item.chapterNo)
                        .thenComparing((WorkingBeat item) -> item.beatKey)
                        .thenComparingInt(item -> item.candidateTempSeq))
                .toList();
        Map<Integer, Integer> tempToFinal = new HashMap<>();
        for (int index = 0; index < assigned.size(); index++) {
            assigned.get(index).finalSeq = index + 1;
            if (assigned.get(index).candidate != null) {
                tempToFinal.put(assigned.get(index).candidateTempSeq, index + 1);
            }
        }
        Map<Integer, Integer> sceneCounter = new HashMap<>();
        List<PlanGraphRecords.PlannedBeat> result = new ArrayList<>();
        for (WorkingBeat item : assigned) {
            int sceneNo = sceneCounter.merge(item.chapterNo, 1, Integer::sum);
            List<Integer> dependencies = new ArrayList<>();
            for (int dependency : item.dependencies) {
                Integer finalSeq = tempToFinal.get(dependency);
                if (finalSeq != null && finalSeq < item.finalSeq) {
                    dependencies.add(finalSeq);
                }
            }
            result.add(new PlanGraphRecords.PlannedBeat(
                    item.finalSeq,
                    item.beatKey,
                    item.source,
                    item.assetId,
                    item.arcId,
                    item.arcSegNo,
                    item.beatType,
                    item.pacing,
                    item.chapterNo,
                    item.volumeNo,
                    sceneNo,
                    item.storyLayer,
                    item.beatRole,
                    PlanContracts.AppearanceMode.LIVE,
                    item.goal,
                    item.focusCharacters,
                    item.threadRefs,
                    List.of(),
                    List.of(),
                    dependencies,
                    item.payoffTarget,
                    List.of(),
                    item.writesKnowledge));
        }
        return List.copyOf(result);
    }

    private static final class WorkingBeat {
        private final String beatKey;
        private final String source;
        private final Long assetId;
        private final Long arcId;
        private final Integer arcSegNo;
        private final String beatType;
        private final BigDecimal pacing;
        private final int chapterNo;
        private final int volumeNo;
        private final PlanContracts.StoryLayer storyLayer;
        private final PlanContracts.BeatRole beatRole;
        private final String goal;
        private final List<String> focusCharacters;
        private final List<String> threadRefs;
        private final List<String> writesKnowledge;
        private final String payoffTarget;
        private final PlanGraphRecords.BeatCandidate candidate;
        private int candidateTempSeq;
        private final boolean skipped;
        private final String skipReason;
        private Set<Integer> dependencies = new LinkedHashSet<>();
        private int finalSeq;

        private WorkingBeat(
                String beatKey,
                String source,
                Long assetId,
                Long arcId,
                Integer arcSegNo,
                String beatType,
                BigDecimal pacing,
                int chapterNo,
                int volumeNo,
                PlanContracts.StoryLayer storyLayer,
                PlanContracts.BeatRole beatRole,
                String goal,
                List<String> focusCharacters,
                List<String> threadRefs,
                List<String> writesKnowledge,
                String payoffTarget,
                PlanGraphRecords.BeatCandidate candidate,
                int candidateTempSeq,
                boolean skipped,
                String skipReason,
                Set<Integer> dependencies) {
            this.beatKey = beatKey;
            this.source = source;
            this.assetId = assetId;
            this.arcId = arcId;
            this.arcSegNo = arcSegNo;
            this.beatType = beatType;
            this.pacing = pacing;
            this.chapterNo = chapterNo;
            this.volumeNo = volumeNo;
            this.storyLayer = storyLayer;
            this.beatRole = beatRole;
            this.goal = goal;
            this.focusCharacters = List.copyOf(focusCharacters);
            this.threadRefs = List.copyOf(threadRefs);
            this.writesKnowledge = List.copyOf(writesKnowledge);
            this.payoffTarget = payoffTarget;
            this.candidate = candidate;
            this.candidateTempSeq = candidateTempSeq;
            this.skipped = skipped;
            this.skipReason = skipReason;
            this.dependencies.addAll(dependencies);
        }

        static WorkingBeat assigned(
                PlanGraphRecords.BeatCandidate candidate,
                int chapter,
                PlanContracts.BeatRole role,
                BigDecimal pace) {
            return new WorkingBeat(
                    candidate.beatKey(), candidate.source(), candidate.assetId(),
                    candidate.arcId(), candidate.arcSegNo(), candidate.beatType(),
                    PlanCompilerSupport.validPacing(candidate.pacing(), pace),
                    chapter, 1, PlanContracts.StoryLayer.LOCAL_CASE, role,
                    "推进候选拍：" + candidate.beatKey(), List.of(), List.of(),
                    List.of(), "", candidate, 0, false, "", new java.util.LinkedHashSet<>(candidate.dependsOn()));
        }

        static WorkingBeat fixed(
                String key,
                int chapter,
                String beatType,
                PlanContracts.BeatRole role,
                PlanContracts.StoryLayer layer,
                List<String> focus,
                List<String> threadRefs,
                BigDecimal pace,
                String goal) {
            return new WorkingBeat(
                    key, "SYNTHETIC", null, null, null, beatType, pace,
                    chapter, 1, layer, role, goal, focus, threadRefs,
                    List.of(), threadRefs.isEmpty() ? "" : threadRefs.get(0),
                    null, 0, false, "", Set.of());
        }

        static WorkingBeat skipped(PlanGraphRecords.BeatCandidate candidate, String reason) {
            return new WorkingBeat(
                    candidate.beatKey(), candidate.source(), candidate.assetId(),
                    candidate.arcId(), candidate.arcSegNo(), candidate.beatType(),
                    candidate.pacing(), candidate.preferredChapter(), 1,
                    PlanContracts.StoryLayer.LOCAL_CASE,
                    PlanCompilerSupport.roleForBeatType(candidate.beatType(), candidate.arcSlot()),
                    "", List.of(), List.of(), List.of(), "", candidate, 0,
                    true, reason, Set.of());
        }
    }

    private static final class ScaffoldBeatType {
        private ScaffoldBeatType() {
        }

        static String of(com.zzdzz.novelgen.derive.contract.ScaffoldBeat beat) {
            return beat.beatType() == null ? "" : beat.beatType();
        }
    }
}








