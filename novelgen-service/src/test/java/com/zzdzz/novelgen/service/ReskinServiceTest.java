package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.SamplePlotNodeDO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 换皮收尾的**设定卡补全**（地点/物品/组织/现象）：
 * 只建人物卡会让「场景按 pinned+别名命中注入」形同虚设（书 56 实跑六张卡全是 character、aliases 全空）。
 * 这里钉两件事：①换皮跑完确实调了补全；②补全炸了不许连累换皮结果。
 */
class ReskinServiceTest {

    private static final long NOVEL_ID = 42L;
    private static final long SAMPLE_ID = 7L;

    private NovelDataService novelData;
    private CanonDocDataService canonData;
    private ChapterDataService chapterData;
    private SamplePlotNodeDataService plotData;
    private ImportedSampleDataService sampleData;
    private MaterialCardDataService cardData;
    private PromptTemplateService promptTemplates;
    private LlmJson llmJson;
    private OutlineService outlineService;
    private BookAssetExtractService bookAssets;
    private ReskinService service;

    /** 一个节点同时满足换皮设定与逐章换皮两个校验器（skin 要 outline，chapter 要 summary+beats）。 */
    private static final String PAYLOAD = """
            {"genre":"星际永夜","world":"永夜城邦","protagonist":"沈砚","tone":"冷",
             "characters":[{"name":"沈砚","note":"灯脉世家独子"}],
             "outline":"## 核心设定\\n永夜",
             "summary":"沈砚离城","time_note":"紧接上一章",
             "beats":[{"goal":"a","conflict":"b","outcome":"c"},{"goal":"d","conflict":"e","outcome":"f"}],
             "hook":"钩子"}
            """;

    @BeforeEach
    void setUp() {
        novelData = mock(NovelDataService.class);
        canonData = mock(CanonDocDataService.class);
        chapterData = mock(ChapterDataService.class);
        plotData = mock(SamplePlotNodeDataService.class);
        sampleData = mock(ImportedSampleDataService.class);
        cardData = mock(MaterialCardDataService.class);
        promptTemplates = mock(PromptTemplateService.class);
        llmJson = mock(LlmJson.class);
        outlineService = mock(OutlineService.class);
        bookAssets = mock(BookAssetExtractService.class);
        service = new ReskinService(novelData, canonData, chapterData, plotData, sampleData, cardData,
                promptTemplates, llmJson, outlineService, new ObjectMapper(), bookAssets);

        when(novelData.findDeriveConfig(anyLong()))
                .thenReturn("{\"sourceSampleId\":" + SAMPLE_ID + ",\"mode\":\"RESKIN\"}");
        SamplePlotNodeDO node = new SamplePlotNodeDO();
        node.setLevel("chapter");
        node.setSeq(1);
        node.setSummary("原书第一章梗概");
        node.setBeats("[{\"goal\":\"g\",\"outcome\":\"o\"}]");
        when(plotData.listBySample(SAMPLE_ID)).thenReturn(List.of(node));
        when(sampleData.getById(SAMPLE_ID)).thenReturn(null);
        when(promptTemplates.format(anyString(), anyString(), any(Object[].class))).thenReturn("prompt");
        when(promptTemplates.get(anyString(), anyString())).thenReturn("system");
        JsonNode payload = readTree(PAYLOAD);
        when(llmJson.ask(any(), any(), anyInt())).thenReturn(payload);

        ChapterDO ch = new ChapterDO();
        ch.setId(100L);
        ch.setChapterNo(1);
        ch.setTitle(null);
        ch.setBudgetMin(2700);
        ch.setBudgetMax(2850);
        when(chapterData.find(NOVEL_ID, 1)).thenReturn(Optional.of(ch));
        when(canonData.findId(anyLong(), anyString(), anyString())).thenReturn(null);
    }

    private static JsonNode readTree(String s) {
        try {
            return new ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reskinFillsSettingCardsAfterChapterLoop() {
        when(bookAssets.extractCards(NOVEL_ID, true))
                .thenReturn(new BookAssetExtractService.CardWriteResult(3, 2));

        ReskinService.ReskinResult r = service.run(NOVEL_ID, 1, 1, null);

        assertEquals(1, r.chapters());
        assertEquals(2, r.beats());
        // 1 张人物卡（换皮设定 characters）+ 新增 3；覆盖的 2 张就是那 1 张里命中的，不重复计
        assertEquals(4, r.cards());
        ArgumentCaptor<Boolean> overwrite = ArgumentCaptor.forClass(Boolean.class);
        verify(bookAssets).extractCards(eq(NOVEL_ID), overwrite.capture());
        assertEquals(true, overwrite.getValue(), "必须用覆盖模式，否则已有人物卡的 aliases 永远填不上");
    }

    @Test
    void cardBackfillFailureDoesNotBreakReskin() {
        when(bookAssets.extractCards(NOVEL_ID, true)).thenThrow(new IllegalStateException("模型抽卡失败"));

        ReskinService.ReskinResult r = assertDoesNotThrow(() -> service.run(NOVEL_ID, 1, 1, null));

        assertEquals(1, r.chapters());
        assertEquals(2, r.beats());
        assertEquals(1, r.cards(), "补卡失败时只算换皮设定那 1 张人物卡，不抛异常");
    }
}
