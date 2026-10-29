package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase4：模型重复 seq/错挂章不再让具体稿整轮停摆。 */
class DeriveDraftBatchRepairTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void duplicateSeqIsDeduplicatedAndMovedBackToPlannedChapter() throws Exception {
        var root = M.readTree("""
                {"chapters":[
                  {"no":9,"title":"甲","draft_outline":"甲","time":"当晚","state_changes":[],
                   "beats":[{"seq":9,"filled":"C1第一拍"},{"seq":12,"filled":"C1重复拍"}]},
                  {"no":10,"title":"乙","draft_outline":"乙","time":"次日","state_changes":[],
                   "beats":[{"seq":10,"filled":"C1第二拍"},{"seq":11,"filled":"C1第三拍"},
                            {"seq":12,"filled":"C1计划内的拍"}]}
                ],"skipped":[]}
                """);
        var ctx = new DeriveDraftService.BatchContext(
                List.of(9, 10, 11, 12), List.of(9, 10), Set.of("C1"), Map.of(12, 10));

        var out = DeriveDraftService.parseBatch(root, ctx);

        assertEquals(2, out.path("chapters").size());
        assertEquals(3, out.path("chapters").get(1).path("beats").size());
        long seq12 = 0;
        for (var chapter : out.path("chapters")) {
            for (var beat : chapter.path("beats")) {
                if (beat.path("seq").asInt() == 12) {
                    seq12++;
                    assertEquals(10, chapter.path("no").asInt());
                }
            }
        }
        assertEquals(1, seq12);
    }

    @Test
    void missingChapterIsSynthesizedForWindowCoverage() throws Exception {
        var root = M.readTree("""
                {"chapters":[{"no":9,"title":"甲","draft_outline":"甲","time":"当晚",
                  "state_changes":[],"beats":[{"seq":9,"filled":"C1第一拍"},
                  {"seq":10,"filled":"C1第二拍"},{"seq":11,"filled":"C1第三拍"},
                  {"seq":12,"filled":"C1第四拍"}]}],"skipped":[]}
                """);
        var ctx = new DeriveDraftService.BatchContext(
                List.of(9, 10, 11, 12), List.of(9, 10), Set.of("C1"),
                Map.of(9, 9, 10, 9, 11, 10, 12, 10));

        var out = DeriveDraftService.parseBatch(root, ctx);

        assertEquals(2, out.path("chapters").size());
        assertEquals(2, out.path("chapters").get(1).path("beats").size());
    }

    @Test
    void clueEventsSurviveNormalize() throws Exception {
        var root = M.readTree("""
                {"chapters":[{"no":9,"title":"甲","draft_outline":"甲","time":"当晚",
                  "state_changes":[],
                  "clueEvents":[{"key":"GM-CLUE-01","act":"PLANT"}],
                  "closingActs":[],
                  "beats":[{"seq":9,"filled":"C1第一拍"}]}],"skipped":[]}
                """);
        var ctx = new DeriveDraftService.BatchContext(
                List.of(9), List.of(9), Set.of("C1"), Map.of(9, 9));

        var out = DeriveDraftService.parseBatch(root, ctx);

        assertEquals("GM-CLUE-01", out.path("chapters").get(0)
                .path("clueEvents").get(0).path("key").asText(),
                "归一不得丢掉日程证据");
        assertEquals("PLANT", out.path("chapters").get(0)
                .path("clueEvents").get(0).path("act").asText());
    }

    private static com.zzdzz.novelgen.model.entity.derive.ChapterDO chapter(int no) {
        var chapter = new com.zzdzz.novelgen.model.entity.derive.ChapterDO();
        chapter.setId((long) no);
        chapter.setNovelId(58L);
        chapter.setChapterNo(no);
        chapter.setTitle("第" + no + "章");
        chapter.setDraftOutline("第" + no + "章各自的推进");
        return chapter;
    }

    private static com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO beat(
            int seq, int chapterNo, String route) {
        var beat = new com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO();
        beat.setId((long) seq);
        beat.setRunId(58L);
        beat.setSeq(seq);
        beat.setChapterNo(chapterNo);
        beat.setVolumeNo(1);
        beat.setBeatRole("MAIN_ADVANCE");
        beat.setStatus("PLANNED");
                List<String> varied = List.of(
                "林默检查门缝并拾起纸屑", "苏晚核对被涂改的值班表",
                "陆敬反锁仓库后从后门离开", "旧楼断电导致监控失去记录",
                "三人因旧债在走廊对质", "守卫换班留下十分钟空档",
                "门锁撬痕指向地下室入口", "一封欠条暴露资金去向",
                "监控硬盘被替换成空白盘", "目击者只记得雨衣颜色",
                "失踪者留下折角照片", "卷宗缺页与登记日期冲突",
                "仓库铁门从内侧被胶带封住", "值班表少写一小时形成时间差");
        beat.setFilledText(varied.get(seq % varied.size()) + "·" + seq);
        beat.setRouteContract(route);
        return beat;
    }

    private static java.util.List<com.zzdzz.novelgen.model.entity.derive.ChapterDO> loadChapters() {
        java.util.List<com.zzdzz.novelgen.model.entity.derive.ChapterDO> out =
                new java.util.ArrayList<>();
        for (int no = 1; no <= 15; no++) {
            out.add(chapter(no));
        }
        return out;
    }

    private static java.util.List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> loadBeats() {
        java.util.List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> out =
                new java.util.ArrayList<>();
        int seq = 1;
        for (int no = 1; no <= 13; no++) {
            out.add(beat(seq++, no, "{}"));
        }
        for (int i = 0; i < 5; i++) {
            out.add(beat(i == 4 ? 22 : seq++, 14, "{}"));
        }
        out.add(beat(seq++, 15, "{}"));
        out.add(beat(seq++, 15, "{}"));
        return out;
    }

    private static java.util.List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry>
            loadSchedule() {
        java.util.List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> out =
                new java.util.ArrayList<>();
        for (int no = 1; no <= 15; no++) {
            out.add(new com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry(
                    no, 1, no == 12, List.of(), List.of()));
        }
        return out;
    }

    @Test
    void rebalanceSkippedWhenSchedulePresent() {
        var chapters = loadChapters();
        var beats = loadBeats();

        var repaired = DeriveDraftService.normalizeDraftContent(
                chapters, beats, loadSchedule());

        assertTrue(repaired.changedBeats().isEmpty(), "日程模式不得搬拍");
        assertEquals(14, beats.stream().filter(b -> b.getSeq() == 22)
                .findFirst().orElseThrow().getChapterNo(), "SLOT_ROUTE 章号必须不可变");
        assertFalse(repaired.repairs().stream()
                .anyMatch(r -> "BEAT_LOAD_REBALANCE".equals(r.repairType())));
    }

    @Test
    void legacyNormalizeStillAllowsForwardOverloadMoveWithoutSchedule() {
        var beats = new java.util.ArrayList<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO>();
        beats.add(beat(22, 1, "{}"));
        for (int seq = 30; seq < 34; seq++) {
            beats.add(beat(seq, 1, "{}"));
        }

        var repaired = DeriveDraftService.normalizeDraftContent(
                List.of(chapter(1), chapter(2)), beats);

        assertEquals(2, beats.stream().filter(b -> b.getSeq() == 22)
                .findFirst().orElseThrow().getChapterNo(),
                "legacy 无日程时允许向后搬普通拍");
        assertTrue(repaired.repairs().stream()
                .anyMatch(r -> "BEAT_LOAD_REBALANCE".equals(r.repairType())));
    }

    @Test
    void dedupeDoesNotMergeAcrossChaptersWhenSchedulePresent() {
        var chapters = List.of(chapter(13), chapter(14));
        var left = beat(30, 13, "{}");
        var right = beat(31, 14, "{}");
        right.setFilledText(left.getFilledText());

        var repaired = DeriveDraftService.normalizeDraftContent(
                chapters, new java.util.ArrayList<>(List.of(left, right)),
                List.of(new com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry(
                        13, 1, false, List.of(), List.of()),
                        new com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry(
                                14, 1, false, List.of(), List.of())));

        assertTrue(repaired.changedBeats().isEmpty(), "跨章语义相似不得合并或删拍");
    }
}
