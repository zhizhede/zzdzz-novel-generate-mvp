package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ⑥a 逐窗化：规模校验 / 窗内拍校验 / 状态往返（纯函数回归钉）。 */
class DerivePlanWindowTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static RunDO run(int minWords, int maxWords) {
        RunDO r = new RunDO();
        r.setParamWordsMin(minWords);
        r.setParamWordsMax(maxWords);
        r.setParamPace(new BigDecimal("1.00"));
        r.setParamOutcome("闭合");
        r.setParamForm("SHORT_STANDALONE");
        r.setParamTags("{\"推理\":1}");
        r.setLenCheck(true);
        return r;
    }

    private static DerivePlanService.InvBeat beat(int seq, String type) {
        return new DerivePlanService.InvBeat(seq, "ARC_SEG", null, 1L, seq,
                type, new BigDecimal("0.80"), "文案" + seq, "开头");
    }

    @Test
    void parseScale_keepsAiDecidedCountWithinBands() throws Exception {
        RunDO r = run(10000, 30000);
        String ok = """
                {"chapterCount":12,"chapterAvg":2500,"reason":"快热短篇"}
                """;
        assertEquals(12, DerivePlanService.parseScale(M.readTree(ok), r, 24).path("chapterCount").asInt());

        String tooMany = """
                {"chapterCount":200,"chapterAvg":2500,"reason":"贪大"}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseScale(M.readTree(tooMany), r, 24),
                "章数超合理带必须拒");

        String tinyChapter = """
                {"chapterCount":40,"chapterAvg":800,"reason":"碎章"}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseScale(M.readTree(tinyChapter), r, 24),
                "每章字数低于合理带必须拒");

        String offBudget = """
                {"chapterCount":40,"chapterAvg":4000,"reason":"超字数"}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseScale(M.readTree(offBudget), r, 24),
                "章数×章均超字数区间必须拒");

        String moreChaptersThanBeats = """
                {"chapterCount":18,"chapterAvg":1600,"reason":"章多于拍"}
                """;
        assertThrows(LlmJson.Bad.class,
                () -> DerivePlanService.parseScale(M.readTree(moreChaptersThanBeats), r, 10),
                "章数超过拍数必须拒（每章至少 1 拍）");

        String tooShort = """
                {"chapterCount":8,"chapterAvg":2500,"reason":"只够下限"}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseScale(M.readTree(tooShort), r, 40),
                "总量只到上限的 67% 必须拒（章数配不上字数）");
    }

    @Test
    void parseWindow_mustLeaveBeatsForLaterChapters() throws Exception {
        List<DerivePlanService.InvBeat> all = List.of(
                beat(1, "事件"), beat(2, "反转"), beat(3, "铺垫"), beat(4, "情绪"));
        DerivePlanService.PlanContext plan = new DerivePlanService.PlanContext(
                4, 8000, 24000, new BigDecimal("1.00"), "SHORT_STANDALONE", "闭合", all, "{\"推理\":1}",
                true);
        DerivePlanService.PlanState state = new DerivePlanService.PlanState(
                4, 2500, List.of(), List.of());
        // 本窗只做第 1 章，后面还有 3 章等着分拍
        DerivePlanService.WindowContext ctx = new DerivePlanService.WindowContext(
                plan, state, 1, 1, all);

        String greedy = """
                {"chapters":[{"no":1,"title_hint":"一","budget":2500,"beats":[1,2,3,4]}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseWindow(M.readTree(greedy), ctx),
                "本窗把拍用光、后面章无拍可分会必须拒");

        String thrifty = """
                {"chapters":[{"no":1,"title_hint":"一","budget":2500,"beats":[1]}]}
                """;
        assertEquals(1, DerivePlanService.parseWindow(M.readTree(thrifty), ctx).size());
    }

    @Test
    void parseWindow_lastWindowMustConsumeAllRemainingBeats() throws Exception {
        List<DerivePlanService.InvBeat> all = List.of(
                beat(1, "事件"), beat(2, "反转"), beat(3, "铺垫"), beat(4, "情绪"));
        DerivePlanService.PlanContext plan = new DerivePlanService.PlanContext(
                2, 4000, 12000, new BigDecimal("1.00"), "SHORT_STANDALONE", "闭合", all, "{\"推理\":1}");
        DerivePlanService.PlanState state = new DerivePlanService.PlanState(
                2, 2500, List.of(1, 2),
                List.of(new DerivePlanService.PlanDraft(1, "一", 2500, List.of(1, 2))));
        DerivePlanService.WindowContext ctx = new DerivePlanService.WindowContext(
                plan, state, 2, 2, List.of(all.get(2), all.get(3)));

        String leftover = """
                {"chapters":[{"no":2,"title_hint":"二","budget":2500,"beats":[3]}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseWindow(M.readTree(leftover), ctx),
                "最后一窗必须把剩余拍全部用掉");

        String full = """
                {"chapters":[{"no":2,"title_hint":"二","budget":2500,"beats":[3,4]}]}
                """;
        assertEquals(1, DerivePlanService.parseWindow(M.readTree(full), ctx).size());
    }

    @Test
    void parseWindow_takesBeatsOnlyFromRemainAndCoversEveryChapter() throws Exception {
        List<DerivePlanService.InvBeat> all = List.of(
                beat(1, "事件"), beat(2, "反转"), beat(3, "铺垫"), beat(4, "情绪"));
        DerivePlanService.PlanContext plan = new DerivePlanService.PlanContext(
                2, 4000, 12000, new BigDecimal("1.00"), "SHORT_STANDALONE", "闭合", all, "{\"推理\":1}");
        DerivePlanService.PlanState state = new DerivePlanService.PlanState(
                2, 2500, List.of(), List.of());
        DerivePlanService.WindowContext ctx = new DerivePlanService.WindowContext(
                plan, state, 1, 2, all);

        String ok = """
                {"chapters":[{"no":1,"title_hint":"登岛","budget":2500,"beats":[1,2]},
                             {"no":2,"title_hint":"暗道","budget":2500,"beats":[3,4]}]}
                """;
        assertEquals(2, DerivePlanService.parseWindow(M.readTree(ok), ctx).size());

        String gap = """
                {"chapters":[{"no":1,"title_hint":"登岛","budget":2500,"beats":[1,2,3,4]}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseWindow(M.readTree(gap), ctx),
                "本窗章数不足必须拒");

        String outsideRemain = """
                {"chapters":[{"no":1,"title_hint":"a","budget":2500,"beats":[1,9]},
                             {"no":2,"title_hint":"b","budget":2500,"beats":[3,4]}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseWindow(M.readTree(outsideRemain), ctx),
                "拍不在剩余清单必须拒");

        String dupInWindow = """
                {"chapters":[{"no":1,"title_hint":"a","budget":2500,"beats":[1,1]},
                             {"no":2,"title_hint":"b","budget":2500,"beats":[3,4]}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseWindow(M.readTree(dupInWindow), ctx),
                "本窗拍重复必须拒");
    }

    @Test
    void parseWindow_rejectsBeatAlreadyUsedInEarlierWindow() throws Exception {
        List<DerivePlanService.InvBeat> all = List.of(
                beat(1, "事件"), beat(2, "反转"), beat(3, "铺垫"), beat(4, "情绪"));
        DerivePlanService.PlanContext plan = new DerivePlanService.PlanContext(
                4, 8000, 24000, new BigDecimal("1.00"), "SHORT_STANDALONE", "闭合", all, "{\"推理\":1}",
                true);
        DerivePlanService.PlanState state = new DerivePlanService.PlanState(
                4, 2500, List.of(1, 2, 3, 4),
                List.of(new DerivePlanService.PlanDraft(1, "一", 2500, List.of(1, 2)),
                        new DerivePlanService.PlanDraft(2, "二", 2500, List.of(3, 4))));
        DerivePlanService.WindowContext ctx = new DerivePlanService.WindowContext(
                plan, state, 3, 4, List.of());

        String reused = """
                {"chapters":[{"no":3,"title_hint":"a","budget":2500,"beats":[1]},
                             {"no":4,"title_hint":"b","budget":2500,"beats":[2]}]}
                """;
        assertThrows(LlmJson.Bad.class, () -> DerivePlanService.parseWindow(M.readTree(reused), ctx),
                "前面窗用过的拍不得再用（每拍全书恰好一次）");
    }

    @Test
    void planStateSurvivesStepStatusRoundTrip() {
        RunDO r = run(10000, 30000);
        String base = "{\"v\":1,\"steps\":{\"WELD\":{\"status\":\"DONE\"}}}";
        DerivePlanService.PlanState st = new DerivePlanService.PlanState(
                12, 2500, List.of(1, 2, 3),
                List.of(new DerivePlanService.PlanDraft(1, "登岛", 2500, List.of(1, 2))));
        String merged = DerivePlanService.mergePlanState(base, st);
        assertTrue(merged.contains("WELD"), "其它段保留");

        DerivePlanService.PlanState back = DerivePlanService.readPlanState(merged);
        assertNotNull(back);
        assertEquals(12, back.chapterCount());
        assertEquals(2500, back.chapterAvg());
        assertEquals(List.of(1, 2, 3), back.usedSeqs());
        assertEquals(1, back.drafts().size());
        assertEquals("登岛", back.drafts().get(0).titleHint());

        assertNull(DerivePlanService.readPlanState("{\"v\":1,\"steps\":{}}"), "未定规模返回 null");
        assertNull(DerivePlanService.readPlanState(null));
    }

    @Test
    void totalWindowsCeilsByTenChapters() {
        assertEquals(2, DerivePlanService.totalWindows(12));
        assertEquals(3, DerivePlanService.totalWindows(21));
        assertEquals(6, DerivePlanService.totalWindows(60));
    }

    @Test
    void volumeBoundariesCoverContinuousChapters() {
        assertEquals(1, DerivePlanService.volumeNoForChapter(1, 45, 4));
        assertEquals(1, DerivePlanService.volumeNoForChapter(12, 45, 4));
        assertEquals(2, DerivePlanService.volumeNoForChapter(13, 45, 4));
        assertEquals(4, DerivePlanService.volumeNoForChapter(45, 45, 4));
        assertEquals(1, DerivePlanService.defaultVolumeCount(12));
        assertEquals(4, DerivePlanService.defaultVolumeCount(45));
    }
}
