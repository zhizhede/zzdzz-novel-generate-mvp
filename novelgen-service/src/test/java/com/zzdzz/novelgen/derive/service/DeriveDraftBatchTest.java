package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v3.7 ⑥b 分批：切窗/头判定/窗判定/窗体对账/终局对账（纯函数回归钉）。 */
class DeriveDraftBatchTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ChapterDO ch(int no, String outline) {
        ChapterDO c = new ChapterDO();
        c.setChapterNo(no);
        c.setBudget(3000);
        c.setDraftOutline(outline);
        return c;
    }

    private static BeatInstanceDO beat(int seq, int chapterNo, String status, String filled) {
        BeatInstanceDO b = new BeatInstanceDO();
        b.setSeq(seq);
        b.setChapterNo(chapterNo);
        b.setStatus(status);
        b.setFilledText(filled);
        return b;
    }

    @Test
    void windowsSplitDeterministic() {
        List<List<Integer>> w = DeriveDraftService.windows(List.of(1, 2, 3, 8, 9), 3);
        assertEquals(2, w.size());
        assertEquals(List.of(1, 2, 3), w.get(0));
        assertEquals(List.of(8, 9), w.get(1), "尾窗收余");
    }

    @Test
    void headDoneRequiresProtagonist() {
        NovelDO empty = new NovelDO();
        empty.setStoryAssets("{\"v\":1}");
        assertFalse(DeriveDraftService.headDone(empty));

        NovelDO done = new NovelDO();
        done.setStoryAssets("{\"v\":1,\"protagonist\":\"林深\"}");
        assertTrue(DeriveDraftService.headDone(done));
    }

    @Test
    void windowDoneNeedsEveryOutline() {
        List<ChapterDO> chapters = List.of(ch(1, "纲一"), ch(2, ""), ch(3, "纲三"));
        assertFalse(DeriveDraftService.windowDone(List.of(1, 2), chapters), "窗内第2章无纲");
        assertTrue(DeriveDraftService.windowDone(List.of(1, 3), chapters));
    }

    @Test
    void parseBatch_coversWindowExactly() throws Exception {
        String json = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","beats":[{"seq":1,"filled":"x"}]},
                             {"no":2,"title":"b","draft_outline":"…","beats":[{"seq":2,"filled":"y"}]}],
                 "skipped":[3]}
                """;
        DeriveDraftService.BatchContext ctx =
                new DeriveDraftService.BatchContext(List.of(1, 2, 3), List.of(1, 2));
        assertEquals(2, DeriveDraftService.parseBatch(M.readTree(json), ctx).path("chapters").size());
    }

    @Test
    void parseBatch_rejectsLeakOrGapOrNewNames() throws Exception {
        DeriveDraftService.BatchContext ctx =
                new DeriveDraftService.BatchContext(List.of(1, 2), List.of(1, 2));

        String gap = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","beats":[{"seq":1,"filled":"x"}]}],
                 "skipped":[]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBatch(M.readTree(gap), ctx),
                "窗内 seq2 未覆盖必须拒");

        String alien = """
                {"chapters":[{"no":9,"title":"a","draft_outline":"…","beats":[{"seq":1,"filled":"x"}]}],
                 "skipped":[2]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBatch(M.readTree(alien), ctx),
                "窗外表必须拒");

        String named = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","beats":[{"seq":1,"filled":"x"}]},
                             {"no":2,"title":"b","draft_outline":"…","beats":[{"seq":2,"filled":"y"}]}],
                 "skipped":[], "new_names":["路人甲"]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBatch(M.readTree(named), ctx),
                "新造人名必须拒");
    }

    @Test
    void globalReconcile_listsGaps() {
        List<BeatInstanceDO> beats = List.of(
                beat(1, 1, "PLANNED", "有填"),
                beat(2, 2, "SKIPPED", null),
                beat(3, 3, "PLANNED", null));
        List<ChapterDO> chapters = List.of(ch(1, "纲"), ch(2, "纲"), ch(3, null));
        LlmJson.Bad e = assertThrows(LlmJson.Bad.class,
                () -> DeriveDraftService.globalReconcile(beats, chapters));
        assertTrue(e.getMessage().contains("seq=[3]"), "缺填拍 seq3 点名");
        assertTrue(e.getMessage().contains("=[3]"), "无纲章3 点名");

        List<BeatInstanceDO> okBeats = List.of(
                beat(1, 1, "PLANNED", "有填"),
                beat(2, 2, "SKIPPED", null),
                beat(3, 3, "PLANNED", "补齐"));
        DeriveDraftService.globalReconcile(okBeats, List.of(ch(1, "纲"), ch(2, "纲"), ch(3, "纲")));
    }

    @Test
    void parseHead_requiresCastPersonaAndBrief() throws Exception {
        String bad = """
                {"title":"书","summary":"介","protagonist":"甲",
                 "cast":[{"name":"甲"},{"name":"乙"},{"name":"丙"}],
                 "brief":{"premise":"…"}}
                """;
        DeriveDraftService.HeadContext ctx = new DeriveDraftService.HeadContext(
                List.of(1, 2, 3), List.of(), false);
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseHead(M.readTree(bad), ctx),
                "缺 persona 必须拒");
    }
}
