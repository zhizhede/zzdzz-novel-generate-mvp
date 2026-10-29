package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.ScaffoldBeat;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompilerSupport;
import com.zzdzz.novelgen.derive.plan.compiler.StoryPlanCompiler;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompileInput;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class PlanCompilerFixtures {

    private PlanCompilerFixtures() {
    }

    static PlanCompileInput input(long runId, int chapterCount) {
        StoryContract contract = contract(runId, chapterCount, "复仇掩盖账目");
        return new PlanCompileInput(
                runId,
                1,
                PlanCompilerSupport.contractHash(contract),
                contract.scope(),
                contract,
                scaffold(chapterCount),
                schedule(chapterCount),
                candidates(chapterCount),
                List.of(),
                List.of(),
                new BigDecimal("0.50"));
    }

    static StoryContract contract(long runId, int chapterCount, String motiveTag) {
        int payoff = chapterCount - 2;
        int reveal = chapterCount - 1;
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, chapterCount, 2000,
                List.of("fixture-" + chapterCount));
        return new StoryContract(
                runId,
                1,
                "FROZEN",
                List.of(
                        new StoryContract.CharacterSlot("C1", "裴野", "侦探", "男", "调查者"),
                        new StoryContract.CharacterSlot("C2", "林遥", "access", "女", "证人"),
                        new StoryContract.CharacterSlot("C3", "周砺", "opposition", "男", "嫌疑人")),
                new StoryContract.Truth("C3", "周砺", "掩盖旧账", "伪造值班记录",
                        "周砺为掩盖旧账制造失踪", "交出证据并自首", reveal,
                        motiveTag, "伪造值班记录"),
                List.of(new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 2, payoff, "C1")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1")),
                List.of("不得出现第二起案件"),
                List.of(new StoryContract.VolumeTruth(1, "周砺在揭示章承认旧账", List.of())),
                scope);
    }

    static ScaffoldContract scaffold(int chapterCount) {
        List<ScaffoldBeat> beats = new ArrayList<>();
        for (int chapterNo = 1; chapterNo <= chapterCount; chapterNo++) {
            beats.add(new ScaffoldBeat(chapterNo, chapterNo, "事件", "中段", "fixture", ""));
        }
        return new ScaffoldContract(chapterCount, 1,
                List.of(new ScaffoldContract.VolumeRange(1, 1, chapterCount)),
                beats, List.of());
    }

    static List<ChapterScheduleEntry> schedule(int chapterCount) {
        int payoff = chapterCount - 2;
        int reveal = chapterCount - 1;
        List<ChapterScheduleEntry> rows = new ArrayList<>();
        for (int chapterNo = 1; chapterNo <= chapterCount; chapterNo++) {
            rows.add(new ChapterScheduleEntry(
                    chapterNo,
                    1,
                    chapterNo == reveal,
                    chapterNo == 2 ? List.of("GM-CLUE-01") : List.of(),
                    chapterNo == payoff ? List.of("GM-CLUE-01") : List.of()));
        }
        return List.copyOf(rows);
    }

    static List<PlanGraphRecords.BeatCandidate> candidates(int chapterCount) {
        List<PlanGraphRecords.BeatCandidate> rows = new ArrayList<>();
        for (int chapterNo = 1; chapterNo <= chapterCount; chapterNo++) {
            rows.add(new PlanGraphRecords.BeatCandidate(
                    "B" + chapterNo, "ASSET", (long) chapterNo, null, null,
                    chapterNo, "事件", "中段", new BigDecimal("0.50"), List.of()));
        }
        return List.copyOf(rows);
    }


    static StoryContract contractWithPayoffOutside(long runId, int chapterCount, String motiveTag) {
        StoryContract base = contract(runId, chapterCount, motiveTag);
        List<StoryContract.Clue> clues = List.of(
                new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 2, chapterCount + 20, "C1"));
        return new StoryContract(base.runId(), base.version(), base.status(), base.cast(),
                base.truth(), clues, base.threads(), base.forbidden(),
                base.volumeTruth(), base.scope());
    }
    static CompiledStoryPlan compiled(PlanCompileInput input) {
        return new StoryPlanCompiler().compile(input).plan();
    }
}


