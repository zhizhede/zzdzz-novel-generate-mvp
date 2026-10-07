package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ChapterStepDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审校硬伤三档自愈梯子（2026-10-07 自动自愈闭环，用户定调「auto 模式别转人工、99% 自动出厂」）。
 * 契约（docs/交接-内容生成问题与在途改造.md §6）：
 * <ol>
 *   <li>manual 模式钉死转人工，且永不触发档1 保剧情重写；</li>
 *   <li>档1 resetForAutoRewrite 后整章重跑，通过即 DONE（换皮/迁入蓝图不毁）；</li>
 *   <li>自愈穷尽 + review_auto_accept=1 → 自动放行：DONE + digest + 事件 auto_accepted=true；</li>
 *   <li>review_auto_accept=0 → 转人工 PENDING，且绝不 digest（不发没有事实账的章）。</li>
 * </ol>
 * 整章链走真实 runChapter（outline 跳过 → 空场景 → 拼章给稿 → 门禁过 → 评审），只桩 LLM/DAO 边界；
 * reviewAndFix 的多轮行为由 {@link ReviewMultiRoundTest} 锁，此处只关心 disposition 分档。
 */
class ReviewAutoAcceptTest {

    private static final long NOVEL_ID = 63L;
    private static final long CHAPTER_ID = 510L;
    private static final int CHAPTER_NO = 2;
    private static final String TEXT = "夜里的码头闸门合死，两人在檐下站定，谁也没再开口。";

    private NovelDataService novelData;
    private ChapterDataService chapterData;
    private SceneDataService sceneData;
    private ChapterStepDataService stepData;
    private OutlineService outlineService;
    private ContextPackerService packer;
    private GateService gateService;
    private DigestService digestService;
    private ReviewService reviewService;
    private StageLog stageLog;
    private ChapterPipelineService service;
    private ChapterDO chapter;

    @BeforeEach
    void setUp() {
        novelData = mock(NovelDataService.class);
        chapterData = mock(ChapterDataService.class);
        sceneData = mock(SceneDataService.class);
        stepData = mock(ChapterStepDataService.class);
        outlineService = mock(OutlineService.class);
        packer = mock(ContextPackerService.class);
        gateService = mock(GateService.class);
        digestService = mock(DigestService.class);
        reviewService = mock(ReviewService.class);
        stageLog = mock(StageLog.class);
        service = new ChapterPipelineService(
                novelData, chapterData,
                sceneData, stepData, mock(DigestDataService.class),
                outlineService, packer, mock(SceneService.class),
                gateService, digestService, mock(CharacterStateService.class), reviewService,
                mock(VolumePlanService.class), mock(LlmPort.class), stageLog,
                mock(TuningService.class), mock(PromptTemplateService.class), new ObjectMapper());

        NovelDO novel = new NovelDO();
        novel.setTitle("长书验证·离婚后换皮33章");
        when(novelData.getById(NOVEL_ID)).thenReturn(novel);

        chapter = new ChapterDO();
        chapter.setId(CHAPTER_ID);
        chapter.setNovelId(NOVEL_ID);
        chapter.setChapterNo(CHAPTER_NO);
        chapter.setBudgetMin(2000);
        chapter.setBudgetMax(3000);
        chapter.setTitle(null);
        when(chapterData.find(NOVEL_ID, CHAPTER_NO)).thenReturn(Optional.of(chapter));
        when(chapterData.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(outlineService.loadChapter(NOVEL_ID, CHAPTER_NO)).thenReturn(chapter);

        // 状态与正文必须同步到同一对象：approve 守卫读 findById 的 status，markAutoAccepted 读 fullText
        doAnswer(inv -> {
            chapter.setStatus(inv.getArgument(1));
            return null;
        }).when(chapterData).updateStatus(anyLong(), anyString());
        doAnswer(inv -> {
            chapter.setFullText(inv.getArgument(1));
            return null;
        }).when(chapterData).saveFullText(anyLong(), any());
        // stepId 为 null 时 finish(long,…) 拆箱必炸，钉一个真值
        when(stepData.start(anyLong(), anyLong(), anyInt(), anyString(), any(), anyInt())).thenReturn(1L);

        // 整章链快路径：章纲已在（countByChapter>0 跳章纲）→ 场景蓝图空 → 拼章正文给定 → 章级门禁过
        when(sceneData.countByChapter(CHAPTER_ID)).thenReturn(1);
        when(outlineService.loadSpecs(CHAPTER_ID)).thenReturn(List.of());
        when(sceneData.findPassedDrafts(CHAPTER_ID)).thenReturn(List.of(TEXT));
        when(gateService.checkChapter(eq(NOVEL_ID), eq(CHAPTER_ID), eq(CHAPTER_NO), anyString(), anyInt(), anyInt()))
                .thenReturn(new GateService.GateVerdict(true, List.of()));
        // 读者评审恒过（本测试只盯 AI 审校档位）
        when(reviewService.readerReviewAndFix(eq(NOVEL_ID), any(ChapterDO.class), anyString(), any()))
                .thenReturn(new ReviewService.Outcome(null, "pass", false));
        // 自愈开关默认：档1 开、档2 关（默认配置非迁移也不开 replan）、AI 审校重跑仍 BLOCKER（用例可覆盖）
        when(gateService.configValue(eq(NOVEL_ID), eq("review_rewrite"), anyDouble())).thenReturn(1.0);
        when(gateService.configValue(eq(NOVEL_ID), eq("review_blocker_replan"), anyDouble())).thenReturn(0.0);
        when(reviewService.reviewAndFix(eq(NOVEL_ID), any(ChapterDO.class), anyString(), any()))
                .thenReturn(new ReviewService.Outcome(null, "blocker", true));
    }

    /** ① manual 模式钉死转人工：不进档1，作者必须看到硬伤（原实现缺失该判断）。 */
    @Test
    void manualModeGoesStraightToHumanAndNeverRewrites() {
        var outcome = service.reviewBlockedDisposition(NOVEL_ID, CHAPTER_NO, "manual", () -> false, 1);

        assertThat(outcome).isEqualTo(ChapterPipelineService.ChapterOutcome.PENDING);
        verify(chapterData, never()).resetForAutoRewrite(anyLong());
        verify(reviewService, never()).reviewAndFix(anyLong(), any(), any(), any());
    }

    /** ② 档1 保剧情重写：重跑一次通过即 DONE 出厂，不落 PENDING。 */
    @Test
    void tierOneRewritePassesOnSecondAttempt() {
        when(reviewService.reviewAndFix(eq(NOVEL_ID), any(ChapterDO.class), anyString(), any()))
                .thenReturn(new ReviewService.Outcome(null, "pass", false));

        var outcome = service.reviewBlockedDisposition(NOVEL_ID, CHAPTER_NO, "auto", () -> false, 1);

        assertThat(outcome).isEqualTo(ChapterPipelineService.ChapterOutcome.DONE);
        verify(chapterData).resetForAutoRewrite(CHAPTER_ID); // 清草稿正文（蓝图保留语义在实现侧）
        verify(reviewService, times(1)).reviewAndFix(anyLong(), any(), any(), any()); // 重跑只审一轮即过
    }

    /** ③ 自愈穷尽 + review_auto_accept=1：带标放行（DONE + digest + 事件 auto_accepted=true 供抽检）。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exhaustedSelfHealAutoAcceptsWhenSwitchOn() {
        when(gateService.configValue(eq(NOVEL_ID), eq("review_auto_accept"), anyDouble())).thenReturn(1.0);

        var outcome = service.reviewBlockedDisposition(NOVEL_ID, CHAPTER_NO, "auto", () -> false, 1);

        assertThat(outcome).isEqualTo(ChapterPipelineService.ChapterOutcome.DONE);
        verify(chapterData).resetForAutoRewrite(CHAPTER_ID); // 档1 确实跑过仍 BLOCKER
        verify(digestService).digest(eq(NOVEL_ID), eq(CHAPTER_ID), eq(CHAPTER_NO), anyString()); // approve 内补 digest
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass((Class) Map.class);
        verify(stageLog).emit(eq(NOVEL_ID), eq(CHAPTER_NO),
                eq(StageLog.Stage.APPROVE), eq(StageLog.Phase.DONE), payload.capture());
        assertThat(payload.getValue()).containsEntry("auto_accepted", true);
    }

    /** ④ review_auto_accept=0：自愈穷尽转人工，绝不带伤出厂（digest 一次都不能发生）。 */
    @Test
    void exhaustedSelfHealFallsToHumanWhenSwitchOff() {
        when(gateService.configValue(eq(NOVEL_ID), eq("review_auto_accept"), anyDouble())).thenReturn(0.0);

        var outcome = service.reviewBlockedDisposition(NOVEL_ID, CHAPTER_NO, "auto", () -> false, 1);

        assertThat(outcome).isEqualTo(ChapterPipelineService.ChapterOutcome.PENDING);
        verify(chapterData).resetForAutoRewrite(CHAPTER_ID); // 档1 跑过，仍救不回
        verify(digestService, never()).digest(anyLong(), anyLong(), anyInt(), any());
    }
}
