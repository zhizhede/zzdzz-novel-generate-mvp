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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成品终检关（2026-10-05）：读者评审 / AI 审校的「带清单修订」会绕开章级机械门禁重写正文
 * （第 3 章实测 行均长 17.97→21.50），修订稿必须重过机械门禁；未过时按 gate_recheck_action 处置。
 * 默认 KEEP=放行并标记（内容修订优先级高于指纹，行为与改造前一致），另两支 ROLLBACK / REVISE 按需开。
 */
class ChapterRecheckDispositionTest {

    private static final long NOVEL_ID = 56L;
    private static final long CHAPTER_ID = 441L;
    private static final int CHAPTER_NO = 8;
    private static final String BEFORE = "修订前的正文";
    private static final String AFTER = "评审修订稿（指纹漂移）";

    private NovelDataService novelData;
    private ChapterDataService chapterData;
    private GateService gateService;
    private TuningService tuning;
    private LlmPort llm;
    private ChapterPipelineService service;
    private ChapterDO chapter;

    @BeforeEach
    void setUp() {
        novelData = mock(NovelDataService.class);
        chapterData = mock(ChapterDataService.class);
        gateService = mock(GateService.class);
        tuning = mock(TuningService.class);
        llm = mock(LlmPort.class);
        NovelDO novel = new NovelDO();
        novel.setTitle("换皮测试·环带问真");
        when(novelData.getById(NOVEL_ID)).thenReturn(novel);
        service = new ChapterPipelineService(
                novelData, chapterData,
                mock(SceneDataService.class), mock(ChapterStepDataService.class), mock(DigestDataService.class),
                mock(OutlineService.class), mock(ContextPackerService.class), mock(SceneService.class),
                gateService, mock(DigestService.class), mock(CharacterStateService.class), mock(ReviewService.class),
                mock(VolumePlanService.class), llm, mock(StageLog.class),
                tuning, mock(PromptTemplateService.class), new ObjectMapper());
        chapter = new ChapterDO();
        chapter.setId(CHAPTER_ID);
        chapter.setNovelId(NOVEL_ID);
        chapter.setChapterNo(CHAPTER_NO);
        chapter.setBudgetMin(2000);
        chapter.setBudgetMax(3000);
        chapter.setTitle(null);
    }

    /** 机械判定：按正文内容给结论（用 evaluateChapter，候选稿比较用，不落报告）。 */
    private void mechanicalPassesOn(String passingText) {
        when(gateService.evaluateChapter(eq(NOVEL_ID), eq(CHAPTER_NO), anyString(), anyInt(), anyInt()))
                .thenAnswer(inv -> new GateService.GateVerdict(
                        passingText.equals(inv.getArgument(2)), java.util.List.of()));
    }

    private void action(String value) {
        // 第三参是 tuning 兜底值，mock 下为 null —— 必须用 any() 而非 anyString()（后者不匹配 null）
        when(gateService.configText(eq(NOVEL_ID), eq("gate_recheck_action"), any())).thenReturn(value);
    }

    /** 默认 KEEP：放行并标记（保留评审修订稿，落一份失败报告），与改造前行为一致。 */
    @Test
    void keepIsTheDefaultAndMarksTheDrift() {
        mechanicalPassesOn(BEFORE); // 只有修订前能过 → 修订稿是漂移的
        action("KEEP");

        var r = service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, true, () -> false);

        assertThat(r.fullText()).isEqualTo(AFTER);
        assertThat(r.passed()).isFalse();
        assertThat(r.action()).isEqualTo("KEEP");
        verify(chapterData, never()).saveFullText(CHAPTER_ID, BEFORE); // 不回退
        verify(gateService).checkChapter(NOVEL_ID, CHAPTER_ID, CHAPTER_NO, AFTER, 2000, 3000); // 留痕报告 = 采用的正文
    }

    /** 机械复检过了：不折腾，原样采用。 */
    @Test
    void passesThroughWhenRecheckIsClean() {
        mechanicalPassesOn(AFTER);
        action("ROLLBACK");

        var r = service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, true, () -> false);

        assertThat(r.passed()).isTrue();
        assertThat(r.action()).isEqualTo("KEEP");
        assertThat(r.fullText()).isEqualTo(AFTER);
    }

    /** ROLLBACK：修订前那版过得了机械门禁 → 回退，正文落库与报告都指向回退稿。 */
    @Test
    void rollbackRestoresThePreReviewText() {
        mechanicalPassesOn(BEFORE);
        action("ROLLBACK");

        var r = service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, false, () -> false);

        assertThat(r.fullText()).isEqualTo(BEFORE);
        assertThat(r.passed()).isTrue();
        assertThat(r.action()).isEqualTo("ROLLBACK");
        verify(chapterData).saveFullText(CHAPTER_ID, BEFORE);
        verify(gateService).checkChapter(NOVEL_ID, CHAPTER_ID, CHAPTER_NO, BEFORE, 2000, 3000);
    }

    /** ROLLBACK 不成立（修订前那版也过不了）：退回放行并标记，别把没救的稿当成品。 */
    @Test
    void rollbackFallsBackToKeepWhenNothingPasses() {
        mechanicalPassesOn("谁都不匹配");
        action("ROLLBACK");

        var r = service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, false, () -> false);

        assertThat(r.fullText()).isEqualTo(AFTER);
        assertThat(r.passed()).isFalse();
        assertThat(r.action()).isEqualTo("KEEP");
        verify(chapterData, never()).saveFullText(CHAPTER_ID, BEFORE);
    }

    /** REVISE：再修订一轮，过了才换稿。 */
    @Test
    void reviseAdoptsOnlyWhenTheNewDraftPasses() {
        String candidate = "再修订稿（指纹回正）";
        mechanicalPassesOn(candidate);
        action("REVISE");
        when(tuning.i(eq("gate_recheck_revise_rounds"), anyInt())).thenReturn(1);
        // 长度护栏也得给真值：mock 的 double 默认是 0，lenMax=0 会把候选稿一律当「长度异常」弃掉
        when(tuning.d(eq("chapter_revise_len_min"), anyDouble())).thenReturn(0.5);
        when(tuning.d(eq("chapter_revise_len_max"), anyDouble())).thenReturn(1.15);
        when(gateService.failureSummary(CHAPTER_ID)).thenReturn("行均长超上限");
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResult(1L, candidate, null, null));

        var r = service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, true, () -> false);

        assertThat(r.fullText()).isEqualTo(candidate);
        assertThat(r.passed()).isTrue();
        assertThat(r.action()).isEqualTo("REVISE");
        verify(chapterData).saveFullText(CHAPTER_ID, candidate);
        verify(gateService).checkChapter(NOVEL_ID, CHAPTER_ID, CHAPTER_NO, candidate, 2000, 3000);
    }

    /** REVISE 也没修好：退回放行并标记，仍用评审修订稿（内容修订优先，不给用户更差的稿）。 */
    @Test
    void reviseKeepsReviewDraftWhenNewDraftStillFails() {
        mechanicalPassesOn("谁都不匹配");
        action("REVISE");
        when(tuning.i(eq("gate_recheck_revise_rounds"), anyInt())).thenReturn(1);
        // 长度护栏也得给真值：mock 的 double 默认是 0，lenMax=0 会把候选稿一律当「长度异常」弃掉
        when(tuning.d(eq("chapter_revise_len_min"), anyDouble())).thenReturn(0.5);
        when(tuning.d(eq("chapter_revise_len_max"), anyDouble())).thenReturn(1.15);
        when(gateService.failureSummary(CHAPTER_ID)).thenReturn("行均长超上限");
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResult(1L, "再修订稿（还是不过）", null, null));

        var r = service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, true, () -> false);

        assertThat(r.fullText()).isEqualTo(AFTER);
        assertThat(r.passed()).isFalse();
        assertThat(r.action()).isEqualTo("KEEP");
        verify(chapterData, never()).saveFullText(eq(CHAPTER_ID), eq("再修订稿（还是不过）"));
        ArgumentCaptor<String> reported = ArgumentCaptor.forClass(String.class);
        verify(gateService, org.mockito.Mockito.atLeastOnce())
                .checkChapter(eq(NOVEL_ID), eq(CHAPTER_ID), eq(CHAPTER_NO), reported.capture(), anyInt(), anyInt());
        assertThat(reported.getAllValues()).contains(AFTER); // 最终报告 = 采用的正文
    }

    /** 未知取值按 KEEP（fail-open：调参写错不该让管线变形）。 */
    @Test
    void unknownActionFallsBackToKeep() {
        mechanicalPassesOn(BEFORE);
        action("WHATEVER");

        var r = service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, true, () -> false);

        assertThat(r.fullText()).isEqualTo(AFTER);
        assertThat(r.action()).isEqualTo("KEEP");
    }

    /** 用户终止：不写死结论、交回 interrupt（正文保持当前采用稿）。 */
    @Test
    void stopRequestInterruptsInsteadOfAdopting() {
        mechanicalPassesOn("谁都不匹配");
        action("REVISE");
        when(tuning.i(eq("gate_recheck_revise_rounds"), anyInt())).thenReturn(1);

        var r = service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, true, () -> true);

        assertThat(r.interrupted()).isTrue();
        assertThat(r.passed()).isFalse();
        assertThat(r.fullText()).isEqualTo(AFTER);
        verify(llm, never()).chat(any());
    }

    /** 未使用的构造参数提醒：chapterData.findById 不参与本步（章对象由调用方传入）。 */
    @Test
    void doesNotTouchChapterLookup() {
        mechanicalPassesOn(AFTER);
        action("KEEP");
        service.recheckAfterReview(NOVEL_ID, chapter, BEFORE, AFTER, true, () -> false);
        assertThat(chapterData.findById(CHAPTER_ID)).isEqualTo(Optional.empty());
    }
}
