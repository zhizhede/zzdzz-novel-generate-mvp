package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditReport;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditor;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompileInput;
import com.zzdzz.novelgen.derive.plan.compiler.StoryPlanCompiler;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAuditorTest {

    @Test
    void missingContractPayoffAndDigestMismatchAreBlockersWithPaths() {
        var contract = PlanCompilerFixtures.contractWithPayoffOutside(
                59L, 12, "复仇掩盖账目");
        var input = new PlanCompileInput(
                59L, 1, com.zzdzz.novelgen.derive.plan.compiler.PlanCompilerSupport.contractHash(contract),
                scope(12), contract, PlanCompilerFixtures.scaffold(12),
                PlanCompilerFixtures.schedule(12), List.of(), List.of(), List.of(),
                new BigDecimal("0.50"));
        CompiledStoryPlan plan = new StoryPlanCompiler().compile(input).plan();

        PlanAuditReport report = new PlanAuditor().audit(plan, input.contract());

        assertTrue(hasCode(report, "PLAN_CLUE_PAYOFF_MISSING"));
        assertTrue(report.issues().stream()
                .filter(issue -> "BLOCKER".equals(issue.severity().name()))
                .allMatch(issue -> issue.path() != null && !issue.path().isBlank()));
        assertFalse(report.admitted());
    }

    @Test
    void blankMotiveTagAtRevealIsBlocker() {
        StoryContract contract = PlanCompilerFixtures.contract(59L, 12, "");
        var input = input(contract);
        CompiledStoryPlan plan = new StoryPlanCompiler().compile(input).plan();

        PlanAuditReport report = new PlanAuditor().audit(plan, contract);

        assertTrue(hasCode(report, "PLAN_MOTIVE_MISSING"));
        assertTrue(report.issues().stream()
                .anyMatch(issue -> issue.chapterNo() != null && issue.chapterNo() == 11));
    }

    @Test
    void duplicateCandidateKeyIsBlocker() {
        var base = PlanCompilerFixtures.input(59L, 12);
        var candidates = new java.util.ArrayList<>(base.candidates());
        candidates.add(base.candidates().get(0));
        var input = new PlanCompileInput(
                base.runId(), base.contractVersion(), base.contractHash(), base.scope(),
                base.contract(), base.scaffold(), base.schedule(), candidates,
                base.volumes(), base.characters(), base.defaultPace());
        CompiledStoryPlan plan = new StoryPlanCompiler().compile(input).plan();

        PlanAuditReport report = new PlanAuditor().audit(plan, base.contract());

        assertTrue(hasCode(report, "PLAN_BEAT_DUPLICATE"));
    }

    @Test
    void advisoryStyleIssueDoesNotBlockAdmission() {
        var input = PlanCompilerFixtures.input(59L, 12);
        var base = new StoryPlanCompiler().compile(input).plan();
        List<com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords.PlannedBeat> beats =
                base.beats().stream()
                        .map(beat -> new com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords.PlannedBeat(
                                beat.seq(), beat.beatKey(), beat.source(), beat.assetId(),
                                beat.arcId(), beat.arcSegNo(), beat.beatType(), beat.pacing(),
                                beat.chapterNo(), beat.volumeNo(), beat.sceneNo(), beat.storyLayer(),
                                beat.beatRole(), beat.appearanceMode(), "X",
                                beat.focusCharacters(), beat.threadRefs(), beat.knowledgeEffects(),
                                beat.stateEffects(), beat.dependsOn(), beat.payoffTarget(),
                                beat.requiresKnowledge(), beat.writesKnowledge()))
                        .toList();
        var plan = new com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan(
                base.runId(), base.contractVersion(), base.contractHash(), base.scope(),
                base.schedule(), base.volumes(), base.characters(), base.chapters(), beats);

        PlanAuditReport report = new PlanAuditor().audit(plan, input.contract());

        assertTrue(hasCode(report, "PLAN_STYLE_SUGGESTION"));
        assertTrue(report.admitted());
    }
    @Test
    void run59FiveClassesAreLocatedBeforeAdmission() {
        StoryContract contract = PlanCompilerFixtures.contractWithPayoffOutside(59L, 15, "");
        var baseInput = new PlanCompileInput(
                59L, 1,
                com.zzdzz.novelgen.derive.plan.compiler.PlanCompilerSupport.contractHash(contract),
                PlanCompilerFixtures.input(59L, 15).scope(), contract,
                PlanCompilerFixtures.scaffold(15), PlanCompilerFixtures.schedule(15),
                List.of(), List.of(), List.of(), new BigDecimal("0.50"));
        var base = new StoryPlanCompiler().compile(baseInput).plan();
        var beats = new java.util.ArrayList<com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords.PlannedBeat>();
        for (var beat : base.beats()) {
            if (beat.beatRole() == PlanContracts.BeatRole.FINAL_CLOSURE) {
                continue;
            }
            if (beat.beatRole() == PlanContracts.BeatRole.FINAL_REVEAL) {
                beats.add(new com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords.PlannedBeat(
                        beat.seq(), beat.beatKey(), beat.source(), beat.assetId(), beat.arcId(),
                        beat.arcSegNo(), beat.beatType(), beat.pacing(), 1, 1, 1,
                        beat.storyLayer(), beat.beatRole(), beat.appearanceMode(), beat.goal(),
                        beat.focusCharacters(), beat.threadRefs(), beat.knowledgeEffects(),
                        beat.stateEffects(), beat.dependsOn(), beat.payoffTarget(),
                        beat.requiresKnowledge(), beat.writesKnowledge()));
            } else {
                beats.add(beat);
            }
        }
        var last = beats.get(beats.size() - 1);
        beats.add(new com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords.PlannedBeat(
                last.seq() + 1, "B99", "ASSET", null, null, null, "事件",
                new BigDecimal("0.50"), 99, 1, 1, PlanContracts.StoryLayer.LOCAL_CASE,
                PlanContracts.BeatRole.MAIN_ADVANCE, PlanContracts.AppearanceMode.LIVE,
                "越界拍", List.of(), List.of(), List.of(), List.of(), List.of(), "",
                List.of(), List.of()));
        var plan = new CompiledStoryPlan(
                base.runId(), base.contractVersion(), base.contractHash(), base.scope(),
                base.schedule(), base.volumes(), base.characters(), base.chapters(), beats);

        PlanAuditReport report = new PlanAuditor().audit(plan, contract);

        assertTrue(hasCode(report, "PLAN_MOTIVE_MISSING"));
        assertTrue(hasCode(report, "PLAN_CLUE_PAYOFF_MISSING"));
        assertTrue(hasCode(report, "PLAN_BEAT_OUTSIDE_CHAPTER"));
        assertTrue(hasCode(report, "PLAN_REVEAL_BOUNDARY"));
        assertTrue(hasCode(report, "PLAN_CLOSURE_MISSING"));
        assertTrue(report.issues().stream()
                .anyMatch(issue -> issue.chapterNo() != null
                        && issue.chapterNo() >= 11 && issue.chapterNo() <= 15
                        && issue.path().contains("chapter[" + issue.chapterNo() + "]")));
    }
    private static boolean hasCode(PlanAuditReport report, String code) {
        return report.issues().stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static PlanCompileInput input(StoryContract contract) {
        var base = PlanCompilerFixtures.input(59L, 12);
        return new PlanCompileInput(
                base.runId(), base.contractVersion(),
                com.zzdzz.novelgen.derive.plan.compiler.PlanCompilerSupport.contractHash(contract),
                base.scope(), contract, base.scaffold(), base.schedule(),
                base.candidates(), base.volumes(), base.characters(), base.defaultPace());
    }

    private static PlanContracts.ScopeContract scope(int chapterCount) {
        return new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, chapterCount, 2000,
                List.of("fixture"));
    }

    private static com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords.PlannedBeat beat(
            String key, int chapterNo) {
        return new com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords.PlannedBeat(
                1, key, "ASSET", null, null, null, "事件", new BigDecimal("0.50"),
                chapterNo, 1, 1, PlanContracts.StoryLayer.LOCAL_CASE,
                PlanContracts.BeatRole.CASE_SETUP, PlanContracts.AppearanceMode.LIVE, "短",
                List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of());
    }
}



