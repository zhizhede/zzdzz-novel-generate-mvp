package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审校多轮闭环（2026-10-07 自动自愈）：原 2 轮下「第 2 轮才冒出的新问题」直接耗尽预算转人工。
 * 契约：① reviewAndFix 最多 ai_review_rounds 轮（默认 3，含首审），blocker 期间逐轮修订+复审；
 * ② 修订轮的问题清单**连 minor 一起修**（原先只修 blocker，首轮 minor 次轮升级成 blocker 时已无预算——
 * 结构性漏洞）；③ 复审通过即停，不空耗轮次。
 */
class ReviewMultiRoundTest {

    private static final long NOVEL_ID = 62L;
    private static final ObjectMapper M = new ObjectMapper();

    private LlmPort llmPort;
    private LlmJson llmJson;
    private PromptTemplateService promptTemplates;
    private GateService gateService;
    private ReviewService service;
    private ChapterDO chapter;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        llmJson = mock(LlmJson.class);
        promptTemplates = mock(PromptTemplateService.class);
        gateService = mock(GateService.class);
        service = new ReviewService(llmPort, llmJson, mock(ContextPackerService.class),
                mock(GateReportDataService.class), mock(ChapterDataService.class),
                mock(SceneDataService.class), M,
                mock(TuningService.class), promptTemplates, gateService, mock(StageLog.class));
        chapter = new ChapterDO();
        chapter.setId(501L);
        chapter.setNovelId(NOVEL_ID);
        chapter.setChapterNo(7);
        chapter.setBudgetMin(2000);
        chapter.setBudgetMax(3000);
        chapter.setFullText("原稿正文");
        // 轮数走 perNovel → gateService.configValue：不钉死 mock 会落到 0→夹紧2，测不到默认3轮
        when(gateService.configValue(eq(NOVEL_ID), eq("ai_review_rounds"), anyDouble())).thenReturn(3.0);
    }

    /** 审校 JSON：verdict + 问题清单（severity 可控）。 */
    private static JsonNode aiReview(String verdict, String... severities) {
        var obj = M.createObjectNode();
        obj.put("verdict", verdict);
        obj.put("summary", "总评");
        var issues = obj.putArray("issues");
        for (int i = 0; i < severities.length; i++) {
            var it = issues.addObject();
            it.put("type", "continuity");
            it.put("severity", severities[i]);
            it.put("quote", "原句" + i);
            it.put("explanation", "问题" + i);
            it.put("suggestion", "建议" + i);
        }
        return obj;
    }

    private static final String REVISED = "修订后的正文";

    /** 一直 BLOCKER：轮数打满即停（3 轮审校 + 2 次修订），Outcome 仍 blocked。 */
    @Test
    void runsUpToMaxRoundsWhenAlwaysBlocked() {
        when(llmJson.ask(any(), any(), anyInt(), any()))
                .thenReturn(aiReview("blocker", "blocker"), aiReview("blocker", "blocker"),
                        aiReview("blocker", "minor"));
        when(llmPort.chat(any())).thenReturn(new LlmPort.ChatResult(1L, REVISED, null, null));

        var outcome = service.reviewAndFix(NOVEL_ID, chapter, "原稿正文", null);

        verify(llmJson, times(3)).ask(any(), any(), anyInt(), any()); // 3 轮封顶，不无限烧
        verify(llmPort, times(2)).chat(any());                        // 轮间修订 2 次
        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.verdict()).isEqualTo("blocker");
        assertThat(outcome.revised()).isEqualTo(REVISED); // 修订稿如实上交管线
    }

    /** 第二轮通过即停：不空耗第三轮。 */
    @Test
    void stopsAsSoonAsReviewPasses() {
        when(llmJson.ask(any(), any(), anyInt(), any()))
                .thenReturn(aiReview("blocker", "blocker"), aiReview("minor"));
        when(llmPort.chat(any())).thenReturn(new LlmPort.ChatResult(1L, REVISED, null, null));

        var outcome = service.reviewAndFix(NOVEL_ID, chapter, "原稿正文", null);

        verify(llmJson, times(2)).ask(any(), any(), anyInt(), any());
        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.verdict()).isEqualTo("minor");
    }

    /** 首轮即过：一次 LLM 都不花修订。 */
    @Test
    void passesWithoutAnyRevisionWhenFirstRoundClean() {
        when(llmJson.ask(any(), any(), anyInt(), any())).thenReturn(aiReview("minor"));

        var outcome = service.reviewAndFix(NOVEL_ID, chapter, "原稿正文", null);

        verify(llmJson, times(1)).ask(any(), any(), anyInt(), any());
        verify(llmPort, times(0)).chat(any());
        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.revised()).isNull();
    }

    /** minor 同修（结构性漏洞的锁）：问题清单必须含次要条目——只修 blocker 的旧实现这里必挂。 */
    @Test
    void revisionListIncludesMinorIssues() {
        when(llmJson.ask(any(), any(), anyInt(), any()))
                .thenReturn(aiReview("blocker", "blocker", "minor"), aiReview("minor"));
        when(llmPort.chat(any())).thenReturn(new LlmPort.ChatResult(1L, REVISED, null, null));

        service.reviewAndFix(NOVEL_ID, chapter, "原稿正文", null);

        ArgumentCaptor<Object> fb = ArgumentCaptor.forClass(Object.class);
        verify(promptTemplates).format(eq(LlmNode.AI_REVIEW_REVISE), anyString(), anyInt(),
                fb.capture(), any(), anyInt(), anyString());
        String list = String.valueOf(fb.getValue());
        assertThat(list).contains("原句0");   // blocker 在清单里
        assertThat(list).contains("原句1");   // minor 也在（旧实现会跳过）
        assertThat(list).contains("次要问题");
    }
}
