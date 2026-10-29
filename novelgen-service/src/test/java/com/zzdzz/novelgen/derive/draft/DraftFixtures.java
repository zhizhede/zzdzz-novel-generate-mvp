package com.zzdzz.novelgen.derive.draft;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class DraftFixtures {

    private DraftFixtures() {
    }

    static CompiledStoryPlan plan(long runId, int chapterCount) {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, chapterCount, 1200,
                List.of("draft-fixture"));
        List<ChapterScheduleEntry> schedule = new ArrayList<>();
        List<PlanGraphRecords.PlannedChapter> chapters = new ArrayList<>();
        List<PlanGraphRecords.PlannedBeat> beats = new ArrayList<>();
        int seq = 1;
        for (int chapterNo = 1; chapterNo <= chapterCount; chapterNo++) {
            String beatKey = "B" + chapterNo + "-S1";
            schedule.add(new ChapterScheduleEntry(chapterNo, 1, false,
                    List.of(), List.of()));
            chapters.add(new PlanGraphRecords.PlannedChapter(
                    chapterNo, 1, "第" + chapterNo + "场", 1200,
                    List.of(new PlanGraphRecords.PlannedScene(
                            1, beatKey, "完成第" + chapterNo + "场的明确情节推进",
                            List.of("人物位置一致"), List.of("获得新的事实"),
                            List.of("已知前情"), List.of("新事实-" + chapterNo)))));
            beats.add(new PlanGraphRecords.PlannedBeat(
                    seq++, beatKey, "ASSET", null, null, null, "事件",
                    new BigDecimal("0.5"), chapterNo, 1, 1,
                    PlanContracts.StoryLayer.LOCAL_CASE,
                    PlanContracts.BeatRole.HIGHLIGHT,
                    PlanContracts.AppearanceMode.LIVE,
                    "第" + chapterNo + "场明确推进故事",
                    List.of("C1"), List.of(),
                    List.of(new PlanGraphRecords.KnowledgeEffect(
                            List.of("C1"), "新事实-" + chapterNo)),
                    List.of(), List.of(), null,
                    List.of(), List.of("新事实-" + chapterNo)));
        }
        return new CompiledStoryPlan(runId, 1, "fixture-hash", scope,
                schedule, List.of(), List.of(), chapters, beats);
    }

    static String prose(int marker, int length) {
        String base = "林澈在灯下停住脚步，听见门外传来克制的敲门声。他数到三才开门，"
                + "发现台阶上放着一枚带着潮气的铜钥匙，钥匙柄刻着陌生的船徽。"
                + "他把钥匙收入口袋，转身叫醒已经睡下的同伴，决定天亮前追查送出钥匙的人。";
        String text = base + "场景" + marker + "。";
        if (text.length() >= length) {
            return text.substring(0, length);
        }
        return text + "夜色继续压低，街巷里只剩远处钟声和两人谨慎的脚步。".repeat(
                Math.max(1, (length - text.length()) / 24 + 1));
    }
}


