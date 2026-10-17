package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.6 方案A 剧透门禁：⑦ 按 reveal_chapter 分段注入——
 * 揭示前只有埋设表+禁令（solution/结局段不下凡），揭示章起完整还原。
 */
class DeriveSpoilerGateTest {

    private final DeriveWriteService svc =
            new DeriveWriteService(null, null, null, null, null, null);

    private RunDO run() {
        RunDO run = new RunDO();
        run.setDomainSnapshot("{}");
        run.setParamTags("{\"推理\":1}");
        run.setParamPace(new BigDecimal("0.50"));
        run.setParamOutcome("闭合");
        run.setParamForm("SHORT_STANDALONE");
        run.setParamIntent("海边小镇，主角养了一条金毛。其实金毛一直都是人类假扮的。");
        return run;
    }

    private NovelDO novel(String assetsJson, String settingsJson) {
        NovelDO novel = new NovelDO();
        novel.setTitle("窗边·试");
        novel.setStoryAssets(assetsJson);
        novel.setStorySettings(settingsJson);
        return novel;
    }

    private ChapterDO chapter(int no) {
        ChapterDO ch = new ChapterDO();
        ch.setChapterNo(no);
        ch.setTitle("第" + no + "章");
        ch.setBudget(3000);
        ch.setDraftOutline("本章测试章纲");
        return ch;
    }

    private static final String ASSETS = """
            {"protagonist":"林深",
             "cast":[{"name":"林深","persona":{"desire":"查明真相"}}],
             "brief":{"premise":"海边小镇少年与金毛的日常（设定/氛围/红线）",
                      "ending":"金毛其实是失踪的人类少年假扮，结尾揭穿"},
             "truth":{"reveal_chapter":3,
                      "clues":[{"clue":"金毛夜间独自外出，爪上有湿土","planted_chapter":1},
                               {"clue":"金毛听得懂暗号却不会叼球","planted_chapter":2}],
                      "timeline_anchors":[{"fact":"失踪案报案时刻为21:15","chapter":2}],
                      "solution":{"culprit":"金毛其实一直是人类","motive":"躲避追杀","method":"长期伪装"}}}
            """;

    @Test
    void beforeReveal_injectsScheduleAndBan_notSolutionNorEnding() {
        String user = svc.buildUser(run(), novel(ASSETS, "{}"), chapter(1),
                List.<BeatInstanceDO>of(), List.of(chapter(1)), false);

        assertTrue(user.contains("剧透禁令"), "揭示前必带剧透禁令");
        assertTrue(user.contains("无知之幕"), "禁令含无知之幕叙事规则");
        assertTrue(user.contains("真相卡·埋设表"), "揭示前注入埋设表");
        assertTrue(user.contains("金毛夜间独自外出"), "clues 现象照常下发");
        assertFalse(user.contains("金毛其实一直是人类"), "solution 不得出现在揭示前");
        assertFalse(user.contains("结尾揭穿"), "brief.ending 不得出现在揭示前");
        assertFalse(user.contains("躲避追杀"), "solution.motive 不得出现在揭示前");
    }

    @Test
    void atRevealChapter_injectsFullTruthAndEnding() {
        String user = svc.buildUser(run(), novel(ASSETS, "{}"), chapter(3),
                List.<BeatInstanceDO>of(), List.of(chapter(1), chapter(2), chapter(3)), false);

        assertTrue(user.contains("真相卡（含 solution"), "揭示章起注入完整真相卡");
        assertTrue(user.contains("金毛其实一直是人类"), "solution.culprit 可用");
        assertTrue(user.contains("结尾揭穿"), "brief.ending 本章起可用");
        assertFalse(user.contains("剧透禁令"), "揭示章起不再下禁令");
    }

    @Test
    void legacyRun_withoutBrief_fallsBackToIntentWithSpoilerWarning() {
        RunDO legacy = run();
        legacy.setParamIntent("海边小镇。真相是金毛其实是人类。");
        String user = svc.buildUser(legacy,
                novel("{\"protagonist\":\"林深\",\"truth\":{\"reveal_chapter\":2,"
                        + "\"clues\":[{\"clue\":\"爪上有湿土\",\"planted_chapter\":1}],"
                        + "\"solution\":{\"culprit\":\"X\",\"motive\":\"Y\",\"method\":\"Z\"}}}", "{}"),
                chapter(1), List.<BeatInstanceDO>of(), List.of(chapter(1)), false);

        assertTrue(user.contains("题材意图（含结局真相"), "老 run（无 brief.premise）回退 intent 全文并带警示键");
        assertTrue(user.contains("揭示前只取人物/氛围/红线"), "回退键名自带反暗示警示");
        assertTrue(user.contains("剧透禁令"), "老 run 同样受剧透禁令保护");
    }

    @Test
    void styleInjection_paramStyleOverridesProfile() {
        RunDO withStyle = run();
        withStyle.setParamStyle("西方正剧腔、短句、零成语");
        String a = svc.buildUser(withStyle, novel(ASSETS,
                "{\"style_profile\":{\"v\":1,\"sentence\":\"源书长句\"}}"),
                chapter(1), List.<BeatInstanceDO>of(), List.of(chapter(1)), false);
        assertTrue(a.contains("目标文风"), "param_style 注入为『目标文风』");
        assertTrue(a.contains("西方正剧腔"), "目标文风文本入包");
        assertFalse(a.contains("源书长句"), "有 param_style 时不再注入源书文风卡");

        String b = svc.buildUser(run(), novel(ASSETS,
                "{\"style_profile\":{\"v\":1,\"sentence\":\"源书长句\"}}"),
                chapter(1), List.<BeatInstanceDO>of(), List.of(chapter(1)), false);
        assertTrue(b.contains("文风卡"), "无 param_style 时注入源书 style_profile");
        assertTrue(b.contains("源书长句"), "文风卡内容入包");
    }

    @Test
    void spoilerBanText_isNonBlank() {
        assertNotNull(DeriveWriteService.SPOILER_BAN);
        assertFalse(DeriveWriteService.SPOILER_BAN.isBlank());
    }

    /** 修1 章纲分段（ASSETS reveal=3）：揭示前未来章不给纲、已写章给；揭示章起全给。 */
    @Test
    void outlineOverview_futureChaptersWithheldBeforeReveal() {
        ChapterDO ch1 = chapter(1);
        ch1.setDraftOutline("第1章纲要");
        ChapterDO ch2 = chapter(2);
        ch2.setDraftOutline("第2章纲要");
        ChapterDO ch12 = chapter(12);
        ch12.setDraftOutline("真容揭露：卸下金毛头套露出成年男人脸");
        List<ChapterDO> all = List.of(ch1, ch2, ch12);

        // 写第2章（2 < reveal=3）：已写章1给纲，未来章2/12只留占位
        String at2 = svc.buildUser(run(), novel(ASSETS, "{}"), chapter(2),
                List.<BeatInstanceDO>of(), all, false);
        assertTrue(at2.contains("第1章纲要"), "已写章纲照常注入");
        assertFalse(at2.contains("卸下金毛头套"), "未来章纲（含解法）不得注入");
        assertTrue(at2.contains("剧透禁令：揭示章之前不提供未来章纲"), "未来章以占位说明替代");

        // 写揭示章3（3 = reveal）：全书章纲完整
        String at3 = svc.buildUser(run(), novel(ASSETS, "{}"), chapter(3),
                List.<BeatInstanceDO>of(), all, false);
        assertTrue(at3.contains("第2章纲要"), "揭示章起章纲概览完整");
        assertTrue(at3.contains("卸下金毛头套"), "揭示章起未来章纲不再遮蔽");
    }
}
