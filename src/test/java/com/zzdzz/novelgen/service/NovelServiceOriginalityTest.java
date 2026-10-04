package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ImportedSampleDO;
import com.zzdzz.novelgen.model.entity.SampleCardDO;
import com.zzdzz.novelgen.model.entity.SamplePlotNodeDO;
import com.zzdzz.novelgen.model.entity.StylePackDO;
import com.zzdzz.novelgen.model.dto.NovelCreateDTO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.GenerationTaskDataService;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.PromptTemplateDataService;
import com.zzdzz.novelgen.service.data.SampleCardDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 大纲原创性把关闭环（书 9/10/11 悉达多换名复刻实证）：判复刻→带原因重写≤2轮→轮满仍复刻即失败；
 * 评审调用故障 fail-open 放行；无样本/样本无骨架直通不评审。提示词走真实 PromptCatalog 目录回退。
 */
class NovelServiceOriginalityTest {

    private StylePackDataService stylePackData;
    private SampleCardDataService sampleCardData;
    private SamplePlotNodeDataService plotData;
    private ImportedSampleDataService sampleData;
    private LlmPort llm;
    private LlmJson llmJson;
    private NovelService service;

    @BeforeEach
    void setUp() {
        stylePackData = mock(StylePackDataService.class);
        sampleCardData = mock(SampleCardDataService.class);
        plotData = mock(SamplePlotNodeDataService.class);
        sampleData = mock(ImportedSampleDataService.class);
        llm = mock(LlmPort.class);
        llmJson = mock(LlmJson.class);
        // 真实 PromptTemplateService + 空 DAO：全部回退 PromptCatalog 目录正文（顺带验证新提示词条目可用）
        PromptTemplateDataService promptDao = mock(PromptTemplateDataService.class);
        when(promptDao.findAll()).thenReturn(List.of());
        service = new NovelService(mock(NovelDataService.class), stylePackData,
                mock(com.zzdzz.novelgen.service.data.ChapterDataService.class), mock(DigestService.class),
                sampleCardData, plotData,
                sampleData, mock(MaterialCardDataService.class), mock(CanonDocDataService.class),
                mock(GenerationTaskDataService.class), mock(com.zzdzz.novelgen.service.data.EmbeddingDataService.class),
                llm, llmJson,
                new PromptTemplateService(promptDao), new ObjectMapper(),
                mock(OutlineService.class));
    }

    private NovelCreateDTO vo(Long sampleId) {
        return new NovelCreateDTO("新书", "简介", 1L, sampleId, null,
                new NovelCreateDTO.DeriveConfigVO(50, "第三人称限知", null, null, 10, 100, false, 1, List.of(),
                        DeriveSupport.MODE_ORIGINAL, null),
                null, null);
    }

    private void stubHappyPath(String firstOutline, String... rewrites) {
        StylePackDO preset = new StylePackDO();
        preset.setId(1L);
        preset.setName("预设");
        preset.setPreset(true);
        when(stylePackData.getById(1L)).thenReturn(preset);
        ImportedSampleDO sample = new ImportedSampleDO();
        sample.setTitle("原书");
        sample.setTags("[]");
        when(sampleData.getById(7L)).thenReturn(sample);
        SamplePlotNodeDO book = new SamplePlotNodeDO();
        book.setLevel("book");
        book.setSummary("原书剧情骨架：少年离家、苦修、入世、觉悟。");
        when(plotData.listBySample(7L)).thenReturn(List.of(book));
        SampleCardDO c = new SampleCardDO();
        c.setKind("character");
        c.setImportance(3);
        c.setName("原书主角");
        when(sampleCardData.listBySample(7L)).thenReturn(List.of(c));
        LlmPort.ChatResult first = new LlmPort.ChatResult(1L, firstOutline, null, null);
        LlmPort.ChatResult[] rest = Arrays.stream(rewrites)
                .map(r -> new LlmPort.ChatResult(2L, r, null, null))
                .toArray(LlmPort.ChatResult[]::new);
        when(llm.chat(any(LlmPort.ChatRequest.class))).thenReturn(first, rest);
    }

    private NovelService.OriginalityVerdict verdict(boolean copy, String reason) {
        return new NovelService.OriginalityVerdict(copy, copy ? List.of(reason) : List.of());
    }

    @Test
    void noSampleSkipsOriginalityGate() throws Exception {
        stubHappyPath("大纲稿");
        String out = service.draftOutline(vo(null));
        assertThat(out).isEqualTo("大纲稿");
        verify(llmJson, never()).ask(any(), any(), anyInt());
    }

    @Test
    void sampleWithoutBookSkeletonSkipsGate() throws Exception {
        stubHappyPath("大纲稿");
        when(plotData.listBySample(7L)).thenReturn(List.of());
        String out = service.draftOutline(vo(7L));
        assertThat(out).isEqualTo("大纲稿");
        verify(llmJson, never()).ask(any(), any(), anyInt());
    }

    @Test
    void copyOnceThenRewritePasses() throws Exception {
        stubHappyPath("复刻稿", "重写稿");
        when(llmJson.ask(any(), any(), anyInt())).thenReturn(verdict(true, "主角为原书人物对应物"),
                verdict(false, null));
        String out = service.draftOutline(vo(7L));
        assertThat(out).isEqualTo("重写稿");
        verify(llm, times(2)).chat(any());
        verify(llmJson, times(2)).ask(any(), any(), anyInt());
    }

    @Test
    void copyExhaustingRoundsFails() throws Exception {
        stubHappyPath("复刻稿", "重写稿1", "重写稿2");
        when(llmJson.ask(any(), any(), anyInt())).thenReturn(verdict(true, "主线与原书同序"));
        assertThatThrownBy(() -> service.draftOutline(vo(7L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("复刻度过高");
        verify(llm, times(3)).chat(any());
        verify(llmJson, times(3)).ask(any(), any(), anyInt());
    }

    @Test
    void judgeInfraFailurePassesThroughWithInitialDraft() throws Exception {
        stubHappyPath("大纲稿");
        when(llmJson.ask(any(), any(), anyInt())).thenThrow(new IllegalStateException("评审 LLM 不可用"));
        String out = service.draftOutline(vo(7L));
        assertThat(out).isEqualTo("大纲稿");
        verify(llm, times(1)).chat(any());
    }
}
