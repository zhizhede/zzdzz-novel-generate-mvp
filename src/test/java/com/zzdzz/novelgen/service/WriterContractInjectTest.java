package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.PromptCatalog;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import com.zzdzz.novelgen.service.data.VolumeReviewDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 写侧契约注入（2026-10-07 治本·采样层，分支 10/1007-采样层治本-写侧契约）：
 * 模板「本章目标」槽位此前填的是章题——章纲 goal/hook 只被拿去做卡片匹配与 RAG 查询，
 * 从未作为文本进过场景提示词，写手根本没见过本章目标（审校侧同日已补，写读必须同表）。
 * 顺带给写手两道核销防线：称谓硬约束行 + 输出前三项自查（与审校三项核销同口径）。
 * 三锁：①模板文案与占位（catalog）②arity 22（formatSafe 个数失配静默回退，AGENTS 坑 14 同族）
 * ③调用点参数顺序（title→章题槽、goal→目标槽、hook→钩子槽——槽位错位 bug 的唯一有效锁）。
 */
class WriterContractInjectTest {

    private static final long NOVEL_ID = 63L;

    private PromptTemplateService promptTemplates;
    private ContextPackerService packer;

    @BeforeEach
    void setUp() {
        promptTemplates = mock(PromptTemplateService.class);
        packer = new ContextPackerService(
                mock(StylePackDataService.class), mock(CanonDocDataService.class),
                mock(DigestDataService.class), mock(ForeshadowDataService.class),
                mock(ChapterDataService.class), mock(WorldStateDataService.class),
                mock(MaterialCardService.class), mock(TuningService.class),
                mock(EmbeddingService.class), promptTemplates,
                mock(VolumeReviewDataService.class), mock(NovelDataService.class),
                mock(GateService.class), mock(GateReportDataService.class),
                new ObjectMapper());
    }

    /** 模板侧：章题/章纲目标/章末钩子三行 + 称谓硬约束 + 输出前自查 + arity 22。 */
    @Test
    void sceneDraftTemplateCarriesChapterContract() {
        String user = PromptCatalog.contentOf(LlmNode.SCENE_DRAFT, "user");
        assertThat(user).contains("本章章题：%s");
        assertThat(user).contains("本章目标（章纲，必须落实）：%s");
        assertThat(user).contains("本章钩子（章末收束，不得提前泄掉）：%s");
        assertThat(user).contains("人物称谓硬约束");
        assertThat(user).contains("输出前三项自查");
        int specs = PromptTemplateService.specs(user).stream()
                .filter(s -> !s.equals("%%")).toList().size();
        assertThat(specs).isEqualTo(22);
    }

    /** 调用点侧：title/goal/hook 各就各位——错一位就是这次修的那类槽位错位 bug。 */
    @Test
    void packSceneFeedsTitleGoalHookIntoTheirSlots() {
        ChapterDO ch = new ChapterDO();
        ch.setId(510L);
        ch.setNovelId(NOVEL_ID);
        ch.setChapterNo(1);
        ch.setTitle("被送给陌生男人");
        ch.setGoal("确认岑宛之已无生机、庄票结清尾款");
        ch.setHook("尾款用庄票结，今夜就转");
        // sceneNo=2：首场景 openingSection 会对 mock 模板建 StringBuilder(null) 炸 NPE，本测试与开篇块无关
        OutlineService.SceneSpec spec = new OutlineService.SceneSpec(
                2, "码头封门，贵客现身", List.of("沈宗珩"), List.of("护卫入场"),
                List.of("点破贵客"), 900, "当夜三更");

        packer.packScene(NOVEL_ID, 1, ch, spec, List.of(), null, List.of(), "上场景正文");

        ArgumentCaptor<Object> title = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> goal = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> hook = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> sceneGoal = ArgumentCaptor.forClass(Object.class);
        verify(promptTemplates).format(eq(LlmNode.SCENE_DRAFT), eq("user"),
                any(), any(),
                title.capture(), goal.capture(), hook.capture(), sceneGoal.capture(),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(title.getValue()).isEqualTo("被送给陌生男人");
        assertThat(goal.getValue()).isEqualTo("确认岑宛之已无生机、庄票结清尾款");
        assertThat(hook.getValue()).isEqualTo("尾款用庄票结，今夜就转");
        assertThat(sceneGoal.getValue()).isEqualTo("码头封门，贵客现身");
    }
}
