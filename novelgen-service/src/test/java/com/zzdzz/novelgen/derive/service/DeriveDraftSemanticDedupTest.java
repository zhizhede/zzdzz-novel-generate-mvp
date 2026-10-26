package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveDraftSemanticDedupTest {

    @Test
    void duplicateFilledTextsAreDetectedBySimilarity() {
        String left = "乔晚核验报案函件时发现函件被调换成空白封装，关键证据失效。";
        String right = "乔晚核验报案函件时发现函件被调换成空白封装，关键证据失效，调查方向锁定旧楼火灾区域。";

        assertTrue(DeriveDraftService.semanticSimilarity(left, right) >= 0.65);
        assertFalse(DeriveDraftService.semanticSimilarity(left, "乔晚在走廊询问陆鸣的作息时间") >= 0.65);
    }

    @Test
    void duplicateAdjacentOutlinesAreRewrittenFromBeats() {
        ChapterDO first = chapter(1, "乔晚核验报案函件时发现函件被调换成空白封装，关键证据失效。");
        ChapterDO second = chapter(2, "乔晚核验报案函件时发现函件被调换成空白封装，关键证据失效，调查方向锁定旧楼火灾区域。");
        BeatInstanceDO beat = beat(4, 1, "乔晚核验报案函件时发现函件被调换成空白封装，关键证据失效。");
        BeatInstanceDO other = beat(26, 2, "陆鸣反问函件去向，乔晚当场发现函件已被调换成空白封装，调查方向锁定旧楼火灾区域。");

        var result = DeriveDraftService.normalizeDraftContent(
                List.of(first, second), List.of(beat, other));

        assertTrue(result.changedChapters().contains(second));
        assertFalse(second.getDraftOutline().equals(first.getDraftOutline()));
    }

    @Test
    void requiredClosureBeatIsNotSkipped() {
        ChapterDO first = chapter(1, "a");
        ChapterDO second = chapter(2, "a");
        BeatInstanceDO left = beat(1, 1, "同一事件");
        BeatInstanceDO right = beat(2, 2, "同一事件");
        right.setBeatRole("FINAL_CLOSURE");

        var result = DeriveDraftService.normalizeDraftContent(
                List.of(first, second), List.of(left, right));

        assertTrue(result.changedBeats().isEmpty());
    }

    @Test
    void requiredRoleBeatIsNotSkippedEvenWhenSimilar() {
        ChapterDO first = chapter(1, "a");
        ChapterDO second = chapter(2, "b");
        BeatInstanceDO left = beat(1, 1, "同一段高光情节推进");
        BeatInstanceDO right = beat(2, 2, "同一段高光情节推进");
        right.setBeatRole("HIGHLIGHT");

        var result = DeriveDraftService.normalizeDraftContent(
                List.of(first, second), List.of(left, right));

        assertTrue(result.changedBeats().isEmpty(),
                "required/保护角色拍即使语义重复也不得跳过");
    }

    @Test
    void terminalBeatLoadIsRebalancedWithoutTouchingProtectedRoles() {
        ChapterDO ch1 = chapter(1, "一");
        ChapterDO ch2 = chapter(2, "二");
        java.util.List<BeatInstanceDO> beats = new java.util.ArrayList<>();
        BeatInstanceDO hook = beat(1, 1, "钩子");
        hook.setBeatRole("HOOK");
        beats.add(hook);
        for (int seq = 2; seq <= 8; seq++) {
            beats.add(beat(seq, 1, "推进" + seq));
        }
        BeatInstanceDO closure = beat(9, 2, "闭合");
        closure.setBeatRole("LOCAL_CLOSURE");
        beats.add(closure);
        var changed = new java.util.ArrayList<BeatInstanceDO>();

        var repairs = DeriveDraftService.rebalanceBeatLoad(
                List.of(ch1, ch2), beats, changed);

        assertFalse(changed.isEmpty(), "超载章的非保护拍必须被搬移");
        assertTrue(changed.stream().allMatch(b -> b.getChapterNo() == 2),
                "只能搬向欠载章");
        assertTrue(beats.stream().anyMatch(b -> b.getSeq() == 1 && b.getChapterNo() == 1),
                "HOOK 拍必须留在原章");
        assertFalse(changed.stream().anyMatch(b -> b.getSeq() == 1 || b.getSeq() == 9),
                "保护角色拍不得搬移");
        assertTrue(repairs.stream().anyMatch(r -> "BEAT_LOAD_REBALANCE".equals(r.repairType())),
                "搬移必须写入留痕");
    }
    private static ChapterDO chapter(int no, String outline) {
        ChapterDO chapter = new ChapterDO();
        chapter.setChapterNo(no);
        chapter.setDraftOutline(outline);
        chapter.setTitle("第" + no + "章");
        return chapter;
    }

    private static BeatInstanceDO beat(int seq, int chapterNo, String filled) {
        BeatInstanceDO beat = new BeatInstanceDO();
        beat.setSeq(seq);
        beat.setChapterNo(chapterNo);
        beat.setFilledText(filled);
        beat.setStatus("PLANNED");
        beat.setBeatRole("MAIN_ADVANCE");
        return beat;
    }
}