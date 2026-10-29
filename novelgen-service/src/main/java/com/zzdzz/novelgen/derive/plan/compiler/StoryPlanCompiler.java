package com.zzdzz.novelgen.derive.plan.compiler;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleProjector;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 纯结构计划编译器：合同 + 日程 + 候选拍 → 不可变计划图。
 *
 * <p>本阶段不调用 LLM。goal 使用确定性占位文本；硬问题仍返回结构计划，以便审计器定位到
 * 具体章/拍，只有章节骨架无法构造时才抛 {@link PlanCompilerException}。
 */
@Component
public class StoryPlanCompiler {

    private final BeatAssignmentSolver solver;
    private final ContinuitySimulator simulator;

    /** 创建纯结构编译器。 */
    public StoryPlanCompiler() {
        this(new BeatAssignmentSolver(), new ContinuitySimulator());
    }

    /** 创建可注入求解器与模拟器的编译器。 */
    public StoryPlanCompiler(BeatAssignmentSolver solver, ContinuitySimulator simulator) {
        this.solver = Objects.requireNonNull(solver);
        this.simulator = Objects.requireNonNull(simulator);
    }

    /** 编译完整结构计划。 */
    public PlanCompileResult compile(PlanCompileInput input) {
        Objects.requireNonNull(input, "input");
        validateInput(input);
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (!PlanCompilerSupport.contractHash(input.contract()).equals(input.contractHash())) {
            issues.add(PlanCompilerSupport.blocker(
                    PlanCompilerSupport.CONTRACT_DIGEST_MISMATCH,
                    PlanContracts.RepairScope.CONTRACT,
                    null, null, List.of(),
                    "输入 contractHash 与冻结合同内容不一致"));
        }
        Map<Integer, ChapterScheduleEntry> schedule =
                ChapterScheduleProjector.byChapter(input.schedule());
        if (input.schedule().isEmpty()) {
            issues.add(PlanCompilerSupport.blocker(
                    PlanCompilerSupport.SCHEDULE_MISSING,
                    PlanContracts.RepairScope.CONTRACT,
                    null, null, List.of(), "章级日程为空"));
        } else {
            for (int chapterNo = 1; chapterNo <= input.scope().chapterCount(); chapterNo++) {
                if (!schedule.containsKey(chapterNo)) {
                    issues.add(PlanCompilerSupport.blocker(
                            PlanCompilerSupport.SCHEDULE_GAP,
                            PlanContracts.RepairScope.CONTRACT,
                            null, chapterNo, List.of(), "章级日程缺第 " + chapterNo + " 章"));
                }
            }
        }

        PlanGraphRecords.BeatSolveResult solved = solver.solve(
                input.contract(), input.scope(), input.scaffold(), input.schedule(),
                input.candidates(), input.defaultPace());
        issues.addAll(solved.issues());

        List<WorkingBeat> beats = withFillers(input, solved, issues);
        List<PlanGraphRecords.PlannedChapter> chapters =
                buildChapters(input, beats, schedule, issues);
        List<PlanGraphRecords.PlannedBeat> finalBeats =
                renumber(beats, solved.beats());
        ContinuitySimulator.SimulationResult simulation =
                simulator.simulate(input.contract(), finalBeats);
        issues.addAll(simulation.issues());

        CompiledStoryPlan plan = new CompiledStoryPlan(
                input.runId(),
                input.contractVersion(),
                input.contractHash(),
                input.scope(),
                input.schedule(),
                input.volumes(),
                input.characters(),
                chapters,
                finalBeats);
        List<String> warnings = solved.skipped().stream()
                .map(skip -> skip.beatKey() + "：" + skip.reason())
                .toList();
        return new PlanCompileResult(plan, issues, warnings);
    }

    private static void validateInput(PlanCompileInput input) {
        if (input.scope().chapterCount() <= 0) {
            throw new PlanCompilerException("scope.chapterCount 必须为正数");
        }
        if (input.contract().scope() != null
                && input.contract().scope().chapterCount() != input.scope().chapterCount()) {
            throw new PlanCompilerException("合同规模与编译 scope 不一致");
        }
    }

    private static List<WorkingBeat> withFillers(
            PlanCompileInput input,
            PlanGraphRecords.BeatSolveResult solved,
            List<PlanContracts.PlanIssue> issues) {
        Map<Integer, List<PlanGraphRecords.PlannedBeat>> byChapter = new LinkedHashMap<>();
        for (PlanGraphRecords.PlannedBeat beat : solved.beats()) {
            byChapter.computeIfAbsent(beat.chapterNo(), ignored -> new ArrayList<>()).add(beat);
        }
        List<WorkingBeat> result = new ArrayList<>();
        for (PlanGraphRecords.PlannedBeat beat : solved.beats()) {
            result.add(new WorkingBeat(beat));
        }
        for (int chapterNo = 1; chapterNo <= input.scope().chapterCount(); chapterNo++) {
            if (byChapter.getOrDefault(chapterNo, List.of()).isEmpty()) {
                result.add(WorkingBeat.filler(chapterNo, input.scope().chapterCount()));
                issues.add(PlanCompilerSupport.blocker(
                        PlanCompilerSupport.SCRIPT_TARGET_MISSING,
                        PlanContracts.RepairScope.EXPAND_SCRIPTS,
                        1, chapterNo, List.of(),
                        "第 " + chapterNo + " 章没有可执行 beat，已补结构占位拍"));
            }
        }
        return result;
    }

    private static List<PlanGraphRecords.PlannedChapter> buildChapters(
            PlanCompileInput input,
            List<WorkingBeat> beats,
            Map<Integer, ChapterScheduleEntry> schedule,
            List<PlanContracts.PlanIssue> issues) {
        Map<Integer, List<WorkingBeat>> byChapter = new LinkedHashMap<>();
        for (WorkingBeat beat : beats) {
            byChapter.computeIfAbsent(beat.delegate.chapterNo(), ignored -> new ArrayList<>()).add(beat);
        }
        List<PlanGraphRecords.PlannedChapter> chapters = new ArrayList<>();
        for (int chapterNo = 1; chapterNo <= input.scope().chapterCount(); chapterNo++) {
            List<WorkingBeat> rows = byChapter.getOrDefault(chapterNo, List.of()).stream()
                    .sorted(Comparator.comparing((WorkingBeat beat) -> beat.delegate.beatKey()))
                    .toList();
            List<PlanGraphRecords.PlannedScene> scenes = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                WorkingBeat beat = rows.get(index);
                scenes.add(new PlanGraphRecords.PlannedScene(
                        index + 1,
                        beat.delegate.beatKey(),
                        "推进第" + chapterNo + "章既定目标：" + beat.delegate.beatRole(),
                        List.of(), List.of(), List.of(), List.of()));
            }
            int volumeNo = schedule.getOrDefault(chapterNo,
                    new ChapterScheduleEntry(chapterNo, 1, false, List.of(), List.of()))
                    .volumeNo();
            chapters.add(new PlanGraphRecords.PlannedChapter(
                    chapterNo, volumeNo, "第" + chapterNo + "章",
                    input.scope().chapterAvg(), scenes));
        }
        if (chapters.size() != input.scope().chapterCount()) {
            issues.add(PlanCompilerSupport.blocker(
                    PlanCompilerSupport.CHAPTER_GAP,
                    PlanContracts.RepairScope.COMPILE_PLAN,
                    null, null, List.of(),
                    "章节骨架数量与 scope 不一致"));
        }
        return List.copyOf(chapters);
    }

    private static List<PlanGraphRecords.PlannedBeat> renumber(
            List<WorkingBeat> working,
            List<PlanGraphRecords.PlannedBeat> solvedBeats) {
        List<WorkingBeat> ordered = working.stream()
                .sorted(Comparator
                        .comparingInt((WorkingBeat item) -> item.delegate.chapterNo())
                        .thenComparing((WorkingBeat item) -> item.delegate.beatKey())
                        .thenComparingInt((WorkingBeat item) -> item.delegate.seq()))
                .toList();
        Map<Integer, Integer> oldToNew = new HashMap<>();
        List<PlanGraphRecords.PlannedBeat> result = new ArrayList<>();
        Map<Integer, Integer> sceneCounter = new HashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            PlanGraphRecords.PlannedBeat old = ordered.get(index).delegate;
            int sceneNo = sceneCounter.merge(old.chapterNo(), 1, Integer::sum);
            oldToNew.put(old.seq(), index + 1);
            result.add(new PlanGraphRecords.PlannedBeat(
                    index + 1,
                    old.beatKey(),
                    old.source(),
                    old.assetId(),
                    old.arcId(),
                    old.arcSegNo(),
                    old.beatType(),
                    old.pacing(),
                    old.chapterNo(),
                    old.volumeNo(),
                    sceneNo,
                    old.storyLayer(),
                    old.beatRole(),
                    old.appearanceMode(),
                    old.goal(),
                    old.focusCharacters(),
                    old.threadRefs(),
                    old.knowledgeEffects(),
                    old.stateEffects(),
                    rewriteDependencies(old.dependsOn(), oldToNew),
                    old.payoffTarget(),
                    old.requiresKnowledge(),
                    old.writesKnowledge()));
        }
        return List.copyOf(result);
    }

    private static List<Integer> rewriteDependencies(List<Integer> dependencies,
                                                     Map<Integer, Integer> oldToNew) {
        List<Integer> rewritten = new ArrayList<>();
        for (Integer dependency : dependencies) {
            Integer mapped = oldToNew.get(dependency);
            if (mapped != null) {
                rewritten.add(mapped);
            }
        }
        return List.copyOf(rewritten);
    }

    private static final class WorkingBeat {
        private final PlanGraphRecords.PlannedBeat delegate;

        private WorkingBeat(PlanGraphRecords.PlannedBeat delegate) {
            this.delegate = delegate;
        }

        static WorkingBeat filler(int chapterNo, int volumeNo) {
            String key = "SYN-PLAN-FILLER-" + chapterNo;
            return new WorkingBeat(new PlanGraphRecords.PlannedBeat(
                    Integer.MAX_VALUE, key, "SYNTHETIC", null, null, null, "事件",
                    new BigDecimal("0.50"), chapterNo, volumeNo, 1,
                    PlanContracts.StoryLayer.MAIN_MYSTERY,
                    PlanContracts.BeatRole.MAIN_ADVANCE,
                    PlanContracts.AppearanceMode.LIVE,
                    "推进第" + chapterNo + "章既定目标：MAIN_ADVANCE",
                    List.of(), List.of(), List.of(), List.of(), List.of(), "",
                    List.of(), List.of()));
        }
    }
}


