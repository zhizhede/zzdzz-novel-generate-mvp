package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.WorldStateDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⑥a 张力密度闸 + ⑧ 状态账注入：审稿实锤（三连命案/终局掏空/酒窖脱困两次）的结构钉。
 */
class DeriveTensionGateTest {

    private static DerivePlanService.InvBeat beat(int seq, String type) {
        return new DerivePlanService.InvBeat(seq, "ARC_SEG", null, 1L, seq - 1,
                type, new BigDecimal("0.50"), "拍" + seq, "中段");
    }

    private static DerivePlanService.PlanDraft draft(int no, String type, int... seqs) {
        List<Integer> list = new ArrayList<>();
        for (int s : seqs) {
            list.add(s);
        }
        return new DerivePlanService.PlanDraft(no, "题" + no, 2500, list);
    }

    private static DerivePlanService.PlanContext ctx(String tags, List<DerivePlanService.InvBeat> inv) {
        return new DerivePlanService.PlanContext(inv.size(), 1, 99999,
                new BigDecimal("0.50"), "SHORT_STANDALONE", "闭合", inv, tags);
    }

    @Test
    void threeConsecutiveHighImpactChapters_rejected() {
        List<DerivePlanService.InvBeat> inv = List.of(
                beat(1, "事件"), beat(2, "事件"), beat(3, "反转"));
        List<DerivePlanService.PlanDraft> drafts = List.of(
                draft(1, "事件", 1),
                draft(2, "事件", 2),
                draft(3, "反转", 3));
        LlmJson.Bad e = assertThrows(LlmJson.Bad.class,
                () -> DerivePlanService.checkTensionDensity(drafts, ctx("推理", inv)));
        assertTrue(e.getMessage().contains("张力沉淀"), "连续 3 章全高冲击必须拒");
    }

    @Test
    void twoConsecutiveHighImpact_thenBreather_passes() {
        List<DerivePlanService.InvBeat> inv = List.of(
                beat(1, "事件"), beat(2, "反转"), beat(3, "情绪"),
                beat(4, "事件"), beat(5, "反转"), beat(6, "收束"));
        List<DerivePlanService.PlanDraft> drafts = List.of(
                draft(1, "事件", 1),
                draft(2, "反转", 2),
                draft(3, "情绪", 3),
                draft(4, "事件", 4),
                draft(5, "反转", 5),
                draft(6, "收束", 6));
        DerivePlanService.checkTensionDensity(drafts, ctx("推理", inv));
    }

    @Test
    void reasoning_allReversalsInFirstHalf_rejected() {
        // 6 章，最后 30% 从第 5 章起；两个反转都在前 3 章 → 拒
        List<DerivePlanService.InvBeat> inv = List.of(
                beat(1, "反转"), beat(2, "反转"), beat(3, "铺垫"),
                beat(4, "铺垫"), beat(5, "事件"), beat(6, "收束"));
        List<DerivePlanService.PlanDraft> drafts = List.of(
                draft(1, "反转", 1),
                draft(2, "反转", 2),
                draft(3, "铺垫", 3),
                draft(4, "事件", 4),
                draft(5, "铺垫", 5),
                draft(6, "收束", 6));
        LlmJson.Bad e = assertThrows(LlmJson.Bad.class,
                () -> DerivePlanService.checkTensionDensity(drafts, ctx("推理", inv)));
        assertTrue(e.getMessage().contains("反转后移"), "终局段无反转必须拒");
    }

    @Test
    void reasoning_oneReversalInTail_passes() {
        List<DerivePlanService.InvBeat> inv = List.of(
                beat(1, "反转"), beat(2, "铺垫"), beat(3, "事件"),
                beat(4, "铺垫"), beat(5, "反转"), beat(6, "收束"));
        List<DerivePlanService.PlanDraft> drafts = List.of(
                draft(1, "反转", 1),
                draft(2, "铺垫", 2),
                draft(3, "事件", 3),
                draft(4, "铺垫", 4),
                draft(5, "反转", 5),
                draft(6, "收束", 6));
        DerivePlanService.checkTensionDensity(drafts, ctx("悬疑", inv));
    }

    @Test
    void nonReasoning_skipReverseRule() {
        List<DerivePlanService.InvBeat> inv = List.of(
                beat(1, "反转"), beat(2, "反转"), beat(3, "铺垫"),
                beat(4, "铺垫"), beat(5, "事件"), beat(6, "收束"));
        List<DerivePlanService.PlanDraft> drafts = List.of(
                draft(1, "反转", 1),
                draft(2, "反转", 2),
                draft(3, "铺垫", 3),
                draft(4, "事件", 4),
                draft(5, "铺垫", 5),
                draft(6, "收束", 6));
        DerivePlanService.checkTensionDensity(drafts, ctx("都市", inv));
    }

    @Test
    void qcUser_injectsWorldStateLedger() {
        DeriveQcService qc = new DeriveQcService(null, null, null, null, null,
                null, null, null, null, null, null, null);
        RunDO run = new RunDO();
        run.setDomainSnapshot("{}");
        run.setParamTags("{}");
        run.setParamPace(new BigDecimal("0.50"));
        run.setParamOutcome("闭合");
        run.setParamForm("SHORT_STANDALONE");
        NovelDO novel = new NovelDO();
        novel.setTitle("账本质检");
        novel.setStoryAssets("{\"protagonist\":\"林深\"}");
        novel.setStorySettings("{}");
        ChapterDO ch = new ChapterDO();
        ch.setChapterNo(1);
        ch.setTitle("一");
        ch.setContent("正文");
        WorldStateDO ws = new WorldStateDO();
        ws.setChapterNo(1);
        ws.setState("{\"time\":\"凌晨两点\",\"open_loops\":[\"下层四人未脱困\"]}");

        String user = qc.buildQcUser(run, novel, List.of(ch), List.<BeatInstanceDO>of(), List.of(ws));
        assertTrue(user.contains("世界状态账"), "质检输入必带状态账");
        assertTrue(user.contains("下层四人未脱困"), "open_loops 供 logic⑥ 对照重复脱困");

        String bare = qc.buildQcUser(run, novel, List.of(ch), List.<BeatInstanceDO>of());
        assertFalse(bare.contains("世界状态账"), "空账不注入");
    }

    @Test
    void qcPrompt_hasLedgerRule() {
        assertTrue(DeriveQcService.QC_PROMPT.contains("世界状态账"));
        assertTrue(DeriveQcService.QC_PROMPT.contains("再演一遍"));
    }
}
