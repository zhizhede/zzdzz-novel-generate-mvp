package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
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
    void windowsSplitWhenBeatLoadWouldOverflow() {
        List<BeatInstanceDO> beats = List.of(
                beat(1, 1, "PLANNED", null),
                beat(2, 2, "PLANNED", null),
                beat(3, 2, "PLANNED", null),
                beat(4, 3, "PLANNED", null),
                beat(5, 4, "PLANNED", null),
                beat(6, 4, "PLANNED", null),
                beat(7, 4, "PLANNED", null),
                beat(8, 4, "PLANNED", null),
                beat(9, 5, "PLANNED", null),
                beat(10, 6, "PLANNED", null),
                beat(11, 6, "PLANNED", null));

        List<List<Integer>> windows = DeriveDraftService.windowsByBeats(
                List.of(1, 2, 3, 4, 5, 6), beats, 4, 6);

        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5), List.of(6)), windows);
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
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚","state_changes":[],
                              "beats":[{"seq":1,"filled":"x"}]},
                             {"no":2,"title":"b","draft_outline":"…","time":"次日","state_changes":[],
                              "beats":[{"seq":2,"filled":"y"}]}],
                 "skipped":[3]}
                """;
        DeriveDraftService.BatchContext ctx =
                new DeriveDraftService.BatchContext(List.of(1, 2, 3), List.of(1, 2));
        assertEquals(2, DeriveDraftService.parseBatch(M.readTree(json), ctx).path("chapters").size());
    }

    @Test
    void parseBatchCoverageErrorNamesMissingSeqs() {
        String json = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚","state_changes":[],
                              "beats":[{"seq":1,"filled":"x"}]},
                             {"no":2,"title":"b","draft_outline":"…","time":"次日","state_changes":[],
                              "beats":[]}],
                 "skipped":[]}
                """;
        DeriveDraftService.BatchContext ctx =
                new DeriveDraftService.BatchContext(List.of(1, 2, 3), List.of(1, 2));

        LlmJson.Bad error = assertThrows(LlmJson.Bad.class,
                () -> DeriveDraftService.parseBatch(M.readTree(json), ctx));

        assertTrue(error.getMessage().contains("缺 seq=[2, 3]"));
    }

    @Test
    void parseBatchAcceptsSkippedObjectsWithReasons() throws Exception {
        String json = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚","state_changes":[],
                              "beats":[{"seq":1,"filled":"x"}]},
                             {"no":2,"title":"b","draft_outline":"…","time":"次日","state_changes":[],
                              "beats":[]}],
                 "skipped":[{"seq":2,"reason":"原剧情含尸体，与无死亡红线冲突"},
                            {"seq":3,"reason":"原剧情含诡异人形，与无超自然红线冲突"}]}
                """;
        DeriveDraftService.BatchContext ctx =
                new DeriveDraftService.BatchContext(List.of(1, 2, 3), List.of(1, 2));

        JsonNode parsed = DeriveDraftService.parseBatch(M.readTree(json), ctx);

        assertTrue(parsed.path("skipped").isArray());
        assertEquals(2, DeriveDraftService.skippedSeq(parsed.path("skipped").get(0)));
        assertEquals(3, DeriveDraftService.skippedSeq(parsed.path("skipped").get(1)));
    }

    @Test
    void parseBatch_ignoresUnknownExtraSeqWhenWindowIsCovered() throws Exception {
        String json = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚","state_changes":[],
                              "beats":[{"seq":1,"filled":"x"},{"seq":99,"filled":"幻觉拍"}]},
                             {"no":2,"title":"b","draft_outline":"…","time":"次日","state_changes":[],
                              "beats":[{"seq":2,"filled":"y"}]}],
                 "skipped":[]}
                """;
        DeriveDraftService.BatchContext ctx =
                new DeriveDraftService.BatchContext(List.of(1, 2), List.of(1, 2));

        assertEquals(2, DeriveDraftService.parseBatch(M.readTree(json), ctx).path("chapters").size());
    }

    @Test
    void parseBatch_requiresTimeAndLegalStateChanges() throws Exception {
        DeriveDraftService.BatchContext ctx = new DeriveDraftService.BatchContext(
                List.of(1), List.of(1), java.util.Set.of("布兰", "薇拉"));

        String noTime = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","state_changes":[],
                              "beats":[{"seq":1,"filled":"薇拉发现线索"}]}], "skipped":[]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBatch(M.readTree(noTime), ctx),
                "缺 time 必须拒");

        String alienWho = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚",
                              "state_changes":[{"who":"路人甲","what":"死亡"}],
                              "beats":[{"seq":1,"filled":"x"}]}], "skipped":[]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBatch(M.readTree(alienWho), ctx),
                "state_changes 的 who 不在 cast 内必须拒");

        String badWhat = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚",
                              "state_changes":[{"who":"布兰","what":"心情不好"}],
                              "beats":[{"seq":1,"filled":"x"}]}], "skipped":[]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBatch(M.readTree(badWhat), ctx),
                "what 不在封闭集合必须拒");

        String knowWithoutDetail = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚",
                              "state_changes":[{"who":"薇拉","what":"得知"}],
                              "beats":[{"seq":1,"filled":"x"}]}], "skipped":[]}
                """;
        assertThrows(LlmJson.Bad.class,
                () -> DeriveDraftService.parseBatch(M.readTree(knowWithoutDetail), ctx),
                "得知必须带 detail");

        String ok = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚",
                              "state_changes":[{"who":"布兰","what":"死亡"},
                                               {"who":"薇拉","what":"得知","detail":"布兰与卧房案有关"}],
                              "beats":[{"seq":1,"filled":"薇拉发现线索"}]}], "skipped":[]}
                """;
        assertEquals(2, DeriveDraftService.countChanges(
                DeriveDraftService.parseBatch(M.readTree(ok), ctx).path("chapters")));
    }

    @Test
    void draftState_accumulatesAcrossWindowsAndClearsOnRework() throws Exception {
        String base = "{\"v\":1,\"steps\":{\"WELD\":{\"status\":\"DONE\"}}}";
        String win1 = """
                [{"no":1,"time":"当晚","state_changes":[{"who":"布兰","what":"死亡"}]}]
                """;
        String after1 = DeriveDraftService.appendDraftState(base, M.readTree(win1));
        assertEquals(1, DeriveDraftService.readDraftState(after1).size());
        assertTrue(after1.contains("WELD"), "其它段保留");

        String win2 = """
                [{"no":11,"time":"三日后","state_changes":[{"who":"薇拉","what":"得知","detail":"旧图在塔内"}]}]
                """;
        String after2 = DeriveDraftService.appendDraftState(after1, M.readTree(win2));
        var all = DeriveDraftService.readDraftState(after2);
        assertEquals(2, all.size(), "跨窗累加");
        assertEquals("布兰", all.get(0).path("who").asText());
        assertEquals("当晚", all.get(0).path("time").asText());
        assertEquals("旧图在塔内", all.get(1).path("detail").asText());

        String cleared = DeriveDraftService.clearDraftState(after2);
        assertEquals(0, DeriveDraftService.readDraftState(cleared).size(), "回炉后清空");
        assertTrue(cleared.contains("WELD"));
    }

    @Test
    void parseBatch_rejectsLeakOrGapOrNewNames() throws Exception {
        DeriveDraftService.BatchContext ctx =
                new DeriveDraftService.BatchContext(List.of(1, 2), List.of(1, 2));

        String gap = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚","state_changes":[],
                              "beats":[{"seq":1,"filled":"x"}]}],
                 "skipped":[]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBatch(M.readTree(gap), ctx),
                "窗内 seq2 未覆盖必须拒");

        String alien = """
                {"chapters":[{"no":9,"title":"a","draft_outline":"…","time":"当晚","state_changes":[],
                              "beats":[{"seq":1,"filled":"x"}]}],
                 "skipped":[2]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBatch(M.readTree(alien), ctx),
                "窗外表必须拒");

        String named = """
                {"chapters":[{"no":1,"title":"a","draft_outline":"…","time":"当晚","state_changes":[],
                              "beats":[{"seq":1,"filled":"x"}]},
                             {"no":2,"title":"b","draft_outline":"…","time":"次日","state_changes":[],
                              "beats":[{"seq":2,"filled":"y"}]}],
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
    void headStageWalksThreeSegments() {
        NovelDO empty = new NovelDO();
        empty.setStoryAssets("{\"v\":1}");
        assertEquals(0, DeriveDraftService.headStage(empty, false), "无主角=未定名");

        NovelDO named = new NovelDO();
        named.setStoryAssets("""
                {"v":1,"protagonist":"甲","title":"书",
                 "cast":[{"name":"甲","role":"会"},{"name":"乙","role":"医"},{"name":"丙","role":"船"}]}
                """);
        assertEquals(1, DeriveDraftService.headStage(named, false), "有人名表但缺人设卡");
        assertFalse(DeriveDraftService.headDone(named));

        NovelDO persona = new NovelDO();
        persona.setStoryAssets("""
                {"v":1,"protagonist":"甲","title":"书",
                 "cast":[{"name":"甲","role":"会","persona":{"temperament":"冷","conduct":"必记","speech":"短句",
                                                          "desire":"复原","flaw":"低估恨"}},
                         {"name":"乙","role":"医","persona":{"temperament":"稳","conduct":"必救","speech":"慢",
                                                          "desire":"活命","flaw":"怕水"}},
                         {"name":"丙","role":"船","persona":{"temperament":"粗","conduct":"必泊","speech":"土话",
                                                          "desire":"还债","flaw":"贪杯"}}]}
                """);
        assertEquals(2, DeriveDraftService.headStage(persona, false), "人设卡齐但缺 brief");

        NovelDO reasoning = new NovelDO();
        reasoning.setStoryAssets("""
                {"v":1,"protagonist":"甲","title":"书","brief":{"premise":"…","ending":""},
                 "cast":[{"name":"甲","role":"会","persona":{"temperament":"冷","conduct":"必记","speech":"短句",
                                                          "desire":"复原","flaw":"低估恨"}},
                         {"name":"乙","role":"医","persona":{"temperament":"稳","conduct":"必救","speech":"慢",
                                                          "desire":"活命","flaw":"怕水"}},
                         {"name":"丙","role":"船","persona":{"temperament":"粗","conduct":"必泊","speech":"土话",
                                                          "desire":"还债","flaw":"贪杯"}}]}
                """);
        assertEquals(3, DeriveDraftService.headStage(reasoning, false));
        assertEquals(2, DeriveDraftService.headStage(reasoning, true), "推理体裁还缺真相卡");
        assertTrue(DeriveDraftService.headDone(reasoning));
    }

    @Test
    void parseHeadBasic_requiresCoreFieldsOnly() throws Exception {
        DeriveDraftService.HeadContext ctx = new DeriveDraftService.HeadContext(
                List.of(1, 2, 3), List.of(), false);
        String ok = """
                {"title":"书","summary":"介","protagonist":"甲",
                 "cast":[{"name":"甲","role":"会长"},{"name":"乙","role":"医生"},{"name":"丙","role":"船夫"}]}
                """;
        assertEquals("书", DeriveDraftService.parseHeadBasic(M.readTree(ok), ctx).path("title").asText());

        String noRole = """
                {"title":"书","summary":"介","protagonist":"甲",
                 "cast":[{"name":"甲"},{"name":"乙","role":"医"},{"name":"丙","role":"船"}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseHeadBasic(M.readTree(noRole), ctx),
                "cast 缺 role 必须拒");

        String banned = """
                {"title":"书","summary":"介","protagonist":"甲",
                 "cast":[{"name":"甲","role":"会"},{"name":"乙","role":"医"},{"name":"丙","role":"船"}]}
                """;
        DeriveDraftService.HeadContext ban = new DeriveDraftService.HeadContext(List.of(1), List.of("甲"), false);
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseHeadBasic(M.readTree(banned), ban),
                "禁用主角名必须拒");
    }

    @Test
    void parsePersona_coversWholeCastExactly() throws Exception {
        var assets = M.readTree("""
                {"protagonist":"甲","cast":[{"name":"甲"},{"name":"乙"}]}
                """);
        String ok = """
                {"persona":[{"name":"甲","temperament":"冷","conduct":"必记","speech":"短",
                             "desire":"复原","flaw":"低估"},
                            {"name":"乙","temperament":"稳","conduct":"必救","speech":"慢",
                             "desire":"活命","flaw":"怕水"}]}
                """;
        assertEquals(2, DeriveDraftService.parsePersona(M.readTree(ok), assets).size());

        String alien = """
                {"persona":[{"name":"甲","temperament":"冷","conduct":"必记","speech":"短",
                             "desire":"复原","flaw":"低估"},
                            {"name":"丁","temperament":"稳","conduct":"必救","speech":"慢",
                             "desire":"活命","flaw":"怕水"}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parsePersona(M.readTree(alien), assets),
                "名单外人名必须拒");

        String missingKey = """
                {"persona":[{"name":"甲","temperament":"冷","conduct":"必记","speech":"短",
                             "desire":"复原"},
                            {"name":"乙","temperament":"稳","conduct":"必救","speech":"慢",
                             "desire":"活命","flaw":"怕水"}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parsePersona(M.readTree(missingKey), assets),
                "缺 flaw 必须拒");
    }

    @Test
    void mergePersonaKeepsCastOrder() throws Exception {
        var cast = M.readTree("""
                [{"name":"甲","role":"会"},{"name":"乙","role":"医"}]
                """);
        var persona = M.readTree("""
                [{"name":"乙","temperament":"稳","conduct":"必救","speech":"慢","desire":"活命","flaw":"怕水"},
                 {"name":"甲","temperament":"冷","conduct":"必记","speech":"短","desire":"复原","flaw":"低估"}]
                """);
        var merged = M.readTree(DeriveDraftService.mergePersona(cast, persona));
        assertEquals("甲", merged.path(0).path("name").asText(), "顺序沿用 cast");
        assertEquals("冷", merged.path(0).path("persona").path("temperament").asText());
        assertEquals("怕水", merged.path(1).path("persona").path("flaw").asText());
    }
}
