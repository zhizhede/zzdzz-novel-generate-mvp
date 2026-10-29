package com.zzdzz.novelgen.derive.plan.audit;

import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.ContinuitySimulator;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompilerSupport;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 计划的只读确定性审计器。
 *
 * <p>只产生结构化 code/severity/path，不修改合同、日程或计划，也不返回自由模型文案。
 */
@Component
public class PlanAuditor {

    private final ContinuitySimulator simulator;

    /** 创建默认连续性模拟器。 */
    public PlanAuditor() {
        this(new ContinuitySimulator());
    }

    /** 创建可注入模拟器的审计器。 */
    public PlanAuditor(ContinuitySimulator simulator) {
        this.simulator = simulator;
    }

    /** 审计已编译计划与冻结合同。 */
    public PlanAuditReport audit(CompiledStoryPlan plan, StoryContract contract) {
        List<PlanAuditIssue> issues = new ArrayList<>();
        auditDigest(plan, contract, issues);
        auditSchedule(plan, issues);
        auditBeatIdentity(plan, issues);
        auditDependencies(plan, issues);
        auditScenes(plan, issues);
        auditClues(plan, contract, issues);
        auditReveal(plan, contract, issues);
        auditClosure(plan, issues);
        auditContinuity(plan, contract, issues);
        auditStyle(plan, issues);
        return new PlanAuditReport(issues);
    }

    private static void auditDigest(
            CompiledStoryPlan plan,
            StoryContract contract,
            List<PlanAuditIssue> issues) {
        if (!PlanCompilerSupport.contractHash(contract).equals(plan.contractHash())) {
            issues.add(blocker(PlanCompilerSupport.CONTRACT_DIGEST_MISMATCH,
                    PlanContracts.RepairScope.CONTRACT,
                    "plan.contractHash", null, null,
                    "冻结合同 digest 不一致", plan.contractHash(),
                    PlanCompilerSupport.contractHash(contract)));
        }
    }

    private static void auditSchedule(CompiledStoryPlan plan, List<PlanAuditIssue> issues) {
        if (plan.schedule().isEmpty()) {
            issues.add(blocker(PlanCompilerSupport.SCHEDULE_MISSING,
                    PlanContracts.RepairScope.CONTRACT,
                    "plan.schedule", null, null, "章级日程缺失", "schedule", "empty"));
            return;
        }
        Set<Integer> expected = new HashSet<>();
        for (int chapterNo = 1; chapterNo <= plan.scope().chapterCount(); chapterNo++) {
            expected.add(chapterNo);
        }
        Set<Integer> actual = new HashSet<>();
        plan.schedule().forEach(row -> actual.add(row.chapterNo()));
        if (!actual.equals(expected)) {
            issues.add(blocker(PlanCompilerSupport.SCHEDULE_GAP,
                    PlanContracts.RepairScope.CONTRACT,
                    "plan.schedule", null, null, "章级日程未覆盖全部 scope 章",
                    expected.toString(), actual.toString()));
        }
    }

    private static void auditBeatIdentity(CompiledStoryPlan plan, List<PlanAuditIssue> issues) {
        Set<String> keys = new HashSet<>();
        Set<Integer> schedule = new HashSet<>();
        plan.schedule().forEach(row -> schedule.add(row.chapterNo()));
        for (PlanGraphRecords.PlannedBeat beat : plan.beats()) {
            if (!keys.add(beat.beatKey())) {
                issues.add(blocker(PlanCompilerSupport.BEAT_DUPLICATE,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        beatPath(beat), beat.chapterNo(), beat.beatKey(),
                        "beatKey 重复", beat.beatKey(), "duplicate"));
            }
            if (!schedule.contains(beat.chapterNo())) {
                issues.add(blocker(PlanCompilerSupport.BEAT_OUTSIDE_CHAPTER,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        beatPath(beat), beat.chapterNo(), beat.beatKey(),
                        "beat 落在日程外章节", schedule.toString(),
                        String.valueOf(beat.chapterNo())));
            }
        }
    }

    private static void auditDependencies(CompiledStoryPlan plan, List<PlanAuditIssue> issues) {
        for (PlanGraphRecords.PlannedBeat beat : plan.beats()) {
            for (Integer dependency : beat.dependsOn()) {
                if (dependency == null || dependency <= 0 || dependency >= beat.seq()) {
                    issues.add(blocker(PlanCompilerSupport.BEAT_DEPENDENCY_MISSING,
                            PlanContracts.RepairScope.COMPILE_PLAN,
                            beatPath(beat), beat.chapterNo(), beat.beatKey(),
                            "dependsOn 必须指向更早 final seq",
                            "< " + beat.seq(), String.valueOf(dependency)));
                }
            }
        }
    }

    private static void auditScenes(CompiledStoryPlan plan, List<PlanAuditIssue> issues) {
        Set<String> beatKeys = new HashSet<>();
        plan.beats().forEach(beat -> beatKeys.add(beat.beatKey()));
        for (PlanGraphRecords.PlannedChapter chapter : plan.chapters()) {
            if (chapter.scenes().isEmpty()) {
                issues.add(blocker(PlanCompilerSupport.SCRIPT_TARGET_MISSING,
                        PlanContracts.RepairScope.EXPAND_SCRIPTS,
                        "chapter[" + chapter.chapterNo() + "]", chapter.chapterNo(), null,
                        "章内没有可执行场景", ">=1", "0"));
                continue;
            }
            Set<String> sceneKeys = new HashSet<>();
            for (PlanGraphRecords.PlannedScene scene : chapter.scenes()) {
                if (!beatKeys.contains(scene.beatKey()) || !sceneKeys.add(scene.beatKey())) {
                    issues.add(blocker(PlanCompilerSupport.BEAT_DUPLICATE,
                            PlanContracts.RepairScope.COMPILE_PLAN,
                            "chapter[" + chapter.chapterNo() + "].scene[" + scene.sceneNo() + "]",
                            chapter.chapterNo(), scene.beatKey(),
                            "场景 beatKey 必须唯一且存在于计划拍",
                            "unique existing beatKey", scene.beatKey()));
                }
            }
        }
    }

    private static void auditClues(
            CompiledStoryPlan plan,
            StoryContract contract,
            List<PlanAuditIssue> issues) {
        for (StoryContract.Clue clue : contract.clues()) {
            long plants = plan.beats().stream()
                    .filter(beat -> beat.beatRole() == PlanContracts.BeatRole.CLUE_PLANT)
                    .filter(beat -> beat.threadRefs().contains(clue.clueKey())
                            || ("SYN-CLUE-PLANT-" + clue.clueKey()).equals(beat.beatKey()))
                    .count();
            long payoffs = plan.beats().stream()
                    .filter(beat -> beat.beatRole() == PlanContracts.BeatRole.CLUE_PAYOFF
                            || beat.beatRole() == PlanContracts.BeatRole.FINAL_REVEAL)
                    .filter(beat -> beat.threadRefs().contains(clue.clueKey())
                            || ("SYN-CLUE-PAYOFF-" + clue.clueKey()).equals(beat.beatKey())
                            || clue.clueKey().equals(beat.payoffTarget()))
                    .count();
            if (plants != 1) {
                issues.add(blocker(PlanCompilerSupport.CLUE_PLANT_MISSING,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        "chapter[" + clue.plantedChapter() + "]", clue.plantedChapter(), null,
                        "线索 plant 必须恰好一次", clue.clueKey(), String.valueOf(plants)));
            }
            if (payoffs != 1) {
                issues.add(blocker(PlanCompilerSupport.CLUE_PAYOFF_MISSING,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        "chapter[" + clue.payoffChapter() + "]", clue.payoffChapter(), null,
                        "线索 payoff 必须恰好一次", clue.clueKey(), String.valueOf(payoffs)));
            }
        }
    }

    private static void auditReveal(
            CompiledStoryPlan plan,
            StoryContract contract,
            List<PlanAuditIssue> issues) {
        if (contract.truth() == null || contract.truth().revealChapter() <= 0) {
            return;
        }
        List<PlanGraphRecords.PlannedBeat> reveals = plan.beats().stream()
                .filter(beat -> beat.beatRole() == PlanContracts.BeatRole.FINAL_REVEAL)
                .toList();
        if (reveals.isEmpty()) {
            issues.add(blocker(PlanCompilerSupport.REVEAL_BOUNDARY,
                    PlanContracts.RepairScope.COMPILE_PLAN,
                    "chapter[" + contract.truth().revealChapter() + "]",
                    contract.truth().revealChapter(), null,
                    "合同揭示章缺少 FINAL_REVEAL", "SYN-FINAL-REVEAL", "missing"));
        }
        for (PlanGraphRecords.PlannedBeat beat : reveals) {
            if (beat.chapterNo() != contract.truth().revealChapter()) {
                issues.add(blocker(PlanCompilerSupport.REVEAL_BOUNDARY,
                        PlanContracts.RepairScope.COMPILE_PLAN,
                        beatPath(beat), beat.chapterNo(), beat.beatKey(),
                        "FINAL_REVEAL 越出合同揭示章",
                        String.valueOf(contract.truth().revealChapter()),
                        String.valueOf(beat.chapterNo())));
            }
        }
        if (contract.truth().motiveTag() == null || contract.truth().motiveTag().isBlank()) {
            Integer chapter = reveals.isEmpty() ? contract.truth().revealChapter()
                    : reveals.get(0).chapterNo();
            String beatKey = reveals.isEmpty() ? null : reveals.get(0).beatKey();
            issues.add(blocker(PlanCompilerSupport.MOTIVE_MISSING,
                    PlanContracts.RepairScope.COMPILE_PLAN,
                    "chapter[" + chapter + "]", chapter, beatKey,
                    "揭示章缺少合同 motiveTag", "motiveTag", "blank"));
        }
    }

    private static void auditClosure(CompiledStoryPlan plan, List<PlanAuditIssue> issues) {
        int last = plan.scope().chapterCount();
        boolean closure = plan.beats().stream()
                .anyMatch(beat -> beat.beatRole() == PlanContracts.BeatRole.FINAL_CLOSURE
                        && beat.chapterNo() == last);
        if (!closure) {
            issues.add(blocker(PlanCompilerSupport.CLOSURE_MISSING,
                    PlanContracts.RepairScope.COMPILE_PLAN,
                    "chapter[" + last + "]", last, null,
                    "最后一章缺少 FINAL_CLOSURE", "SYN-FINAL-CLOSURE-" + last, "missing"));
        }
    }

    private void auditContinuity(
            CompiledStoryPlan plan,
            StoryContract contract,
            List<PlanAuditIssue> issues) {
        ContinuitySimulator.SimulationResult result =
                simulator.simulate(contract, plan.beats());
        for (PlanContracts.PlanIssue issue : result.issues()) {
            PlanGraphRecords.PlannedBeat beat = issue.seqs().isEmpty()
                    ? null
                    : plan.beats().stream()
                            .filter(row -> row.seq() == issue.seqs().get(0))
                            .findFirst().orElse(null);
            issues.add(PlanAuditIssue.from(issue,
                    beat == null ? (issue.chapterNo() == null
                            ? "plan" : "chapter[" + issue.chapterNo() + "]")
                            : beatPath(beat),
                    beat == null ? null : beat.beatKey(), "", issue.message()));
        }
    }

    private static void auditStyle(CompiledStoryPlan plan, List<PlanAuditIssue> issues) {
        for (PlanGraphRecords.PlannedBeat beat : plan.beats()) {
            if (beat.goal() == null || beat.goal().isBlank() || beat.goal().length() < 8) {
                issues.add(new PlanAuditIssue(
                        PlanCompilerSupport.STYLE_SUGGESTION,
                        Severity.ADVISORY,
                        beatPath(beat),
                        beat.chapterNo(),
                        beat.beatKey(),
                        PlanContracts.RepairScope.NONE,
                        "beat goal 过短，正文前建议补充",
                        "nonblank goal", "blank"));
            }
        }
    }

    private static String beatPath(PlanGraphRecords.PlannedBeat beat) {
        return "chapter[" + beat.chapterNo() + "].beat[seq=" + beat.seq()
                + ",key=" + beat.beatKey() + "]";
    }

    private static PlanAuditIssue blocker(
            String code,
            PlanContracts.RepairScope scope,
            String path,
            Integer chapterNo,
            String beatKey,
            String message,
            String expected,
            String actual) {
        return new PlanAuditIssue(code, Severity.BLOCKER, path, chapterNo, beatKey,
                scope, message, expected, actual);
    }
}


