package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.SamplePlotNodeDO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段二四步链（SPLICE）核心逻辑：
 * ①拼接表结构校验（出库把关：章号连续/来源在池内/跨源≥2/beats 非空/不超目标/种子必填，失败喂回重试）；
 * ②run 全链 mock 跑通——大纲与设定落 canon、逐章 insertPlan + 场景物化、前情种子落 world_states chapter_no=0。
 */
class SpliceServiceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<Long> ALLOWED = List.of(3L, 7L);
    private static final Map<Long, Set<Integer>> POOL = Map.of(3L, Set.of(1, 12), 7L, Set.of(2, 5));

    private static JsonNode node(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String validDraft() {
        return """
                {"settings":"统一设定：旧名→新名 A→甲；时间线统一","outline":"# 全书大纲\\n卷一…",
                 "seed":{"time":"开春","summary":"甲已到镇上，乙在寻人，两线将合。"},
                 "chapters":[
                  {"no":1,"sampleId":3,"seq":12,"vol":1,"arc":"卷一","title":"章一","goal":"甲入镇","hook":"灯下人影","timeNote":"三天",
                   "beats":[{"goal":"入镇","conflict":"城门盘查","outcome":"混进城"}]},
                  {"no":2,"sampleId":7,"seq":2,"vol":1,"arc":"卷一","title":"章二","goal":"乙寻甲","hook":"门后脚步","timeNote":"一天",
                   "beats":[{"goal":"寻人","conflict":"线索中断","outcome":"尾随黑影"}]}
                 ]}
                """;
    }

    // ===== 拼接表结构校验（parseDraft）=====

    @Test
    void validDraftParsesWithProvenanceAndSeed() {
        SpliceService.SpliceDraft d = SpliceService.parseDraft(node(validDraft()), ALLOWED, POOL, 2);
        assertThat(d.chapters()).hasSize(2);
        assertThat(d.chapters().get(0).sampleId()).isEqualTo(3L);
        assertThat(d.chapters().get(0).seq()).isEqualTo(12);
        assertThat(d.chapters().get(1).beatsJson()).contains("寻人");
        assertThat(d.seed().summary()).contains("两线将合");
        assertThat(d.outline()).startsWith("# 全书大纲");
    }

    @Test
    void rejectsBrokenChapterNoSequence() {
        String bad = validDraft().replace("\"no\":2", "\"no\":3");
        assertThatThrownBy(() -> SpliceService.parseDraft(node(bad), ALLOWED, POOL, 2))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("连续");
    }

    @Test
    void rejectsSourceOutsidePoolOrAllowedList() {
        String forged = validDraft().replace("\"seq\":12", "\"seq\":99");
        assertThatThrownBy(() -> SpliceService.parseDraft(node(forged), ALLOWED, POOL, 2))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("素材池");
        String alien = validDraft().replace("\"sampleId\":7", "\"sampleId\":9");
        assertThatThrownBy(() -> SpliceService.parseDraft(node(alien), ALLOWED, POOL, 2))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("取材清单");
    }

    @Test
    void rejectsSingleSourceSampling() {
        String single = validDraft().replace("\"sampleId\":7,\"seq\":2", "\"sampleId\":3,\"seq\":1");
        assertThatThrownBy(() -> SpliceService.parseDraft(node(single), ALLOWED, POOL, 2))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("跨源");
    }

    @Test
    void rejectsOverCapMissingBeatsOrMissingSeed() {
        assertThatThrownBy(() -> SpliceService.parseDraft(node(validDraft()), ALLOWED, POOL, 1))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("恰好输出");
        String noBeats = validDraft().replace("\"beats\":[{\"goal\":\"入镇\",\"conflict\":\"城门盘查\",\"outcome\":\"混进城\"}]", "\"beats\":[]");
        assertThatThrownBy(() -> SpliceService.parseDraft(node(noBeats), ALLOWED, POOL, 2))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("beats");
        String noSeed = validDraft().replace("甲已到镇上，乙在寻人，两线将合。", "");
        assertThatThrownBy(() -> SpliceService.parseDraft(node(noSeed), ALLOWED, POOL, 2))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("seed.summary");
    }

    /** 分批取样（任务 123 RST 实弹后的机制）：后续批只出指定范围 chapters、免头部；范围/条数精确校验。 */
    @Test
    void rangeModeAllowsMissingHeadAndScopedNumbers() {
        String batchJson = """
                {"chapters":[
                 {"no":11,"sampleId":3,"seq":1,"vol":2,"arc":"卷二","title":"甲","goal":"g","hook":"h","timeNote":"t",
                  "beats":[{"goal":"a","conflict":"b","outcome":"c"}]},
                 {"no":12,"sampleId":7,"seq":2,"vol":2,"arc":"卷二","title":"乙","goal":"g2","hook":"h2","timeNote":"t2",
                  "beats":[{"goal":"a","conflict":"b","outcome":"c"}]}]}
                """;
        SpliceService.SpliceDraft d = SpliceService.parseDraftRange(
                node(batchJson), ALLOWED, POOL, 11, 2, false);
        assertThat(d.chapters()).hasSize(2);
        assertThat(d.chapters().get(0).no()).isEqualTo(11);
        assertThat(d.settings()).isEmpty();
        assertThatThrownBy(() -> SpliceService.parseDraftRange(node(batchJson), ALLOWED, POOL, 21, 2, false))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("连续");
        assertThatThrownBy(() -> SpliceService.parseDraftRange(node(batchJson), ALLOWED, POOL, 11, 3, false))
                .isInstanceOf(LlmJson.Bad.class).hasMessageContaining("恰好");
    }

    // ===== run 全链（mock LLM 与数据层）=====

    @Test
    void runMaterializesChaptersAndSeedsWorldState() throws Exception {
        NovelDataService novelData = mock(NovelDataService.class);
        SamplePlotNodeDataService plotData = mock(SamplePlotNodeDataService.class);
        CanonDocDataService canonData = mock(CanonDocDataService.class);
        ChapterDataService chapterData = mock(ChapterDataService.class);
        WorldStateDataService worldStateData = mock(WorldStateDataService.class);
        StylePackDataService stylePackData = mock(StylePackDataService.class);
        OutlineService outlineService = mock(OutlineService.class);
        PromptTemplateService promptTemplates = mock(PromptTemplateService.class);
        LlmJson llmJson = mock(LlmJson.class);

        when(novelData.findDeriveConfig(1L)).thenReturn(
                "{\"preset\":\"SPLICE\",\"sources\":{\"samples\":[3,7]},\"targetChapters\":2}");
        SamplePlotNodeDO n1 = mock(SamplePlotNodeDO.class);
        when(n1.getSampleId()).thenReturn(3L);
        when(n1.getSeq()).thenReturn(12);
        when(n1.getTitle()).thenReturn("章一");
        when(n1.getSummary()).thenReturn("甲入镇");
        when(n1.getBeats()).thenReturn("[{\"goal\":\"入镇\",\"conflict\":\"盘查\",\"outcome\":\"进城\"}]");
        SamplePlotNodeDO n2 = mock(SamplePlotNodeDO.class);
        when(n2.getSampleId()).thenReturn(7L);
        when(n2.getSeq()).thenReturn(2);
        when(n2.getTitle()).thenReturn("章二");
        when(n2.getSummary()).thenReturn("乙寻甲");
        when(n2.getBeats()).thenReturn("[{\"goal\":\"寻人\",\"conflict\":\"断线\",\"outcome\":\"尾随\"}]");
        when(plotData.listChapterNodesBySamples(List.of(3L, 7L))).thenReturn(List.of(n1, n2));

        when(promptTemplates.get(anyString(), anyString())).thenReturn("拼接系统提示");
        when(llmJson.ask(any(LlmPort.ChatRequest.class), any(), anyInt())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<JsonNode, Object> fn = inv.getArgument(1);
            return fn.apply(M.readTree(validDraft()));
        });
        when(canonData.findId(anyLong(), anyString(), anyString())).thenReturn(null);
        ChapterDO c1 = mock(ChapterDO.class);
        when(c1.getId()).thenReturn(11L);
        ChapterDO c2 = mock(ChapterDO.class);
        when(c2.getId()).thenReturn(12L);
        when(chapterData.find(1L, 1)).thenReturn(Optional.of(c1));
        when(chapterData.find(1L, 2)).thenReturn(Optional.of(c2));
        when(stylePackData.findGateConfigByNovel(1L)).thenReturn(null);

        SpliceService service = new SpliceService(novelData, plotData,
                mock(com.zzdzz.novelgen.service.data.ImportedSampleDataService.class),
                canonData, chapterData, worldStateData,
                stylePackData, outlineService, mock(LlmPort.class), llmJson, promptTemplates, new ObjectMapper());
        SpliceService.SpliceResult result = service.run(1L, 2, (no, msg) -> { });

        assertThat(result.chapters()).isEqualTo(2);
        assertThat(result.sources()).isEqualTo(2);
        verify(canonData).insert(eq(1L), eq("misc"), eq("拼接设定"), contains("统一设定"));
        verify(canonData).insert(eq(1L), eq("misc"), eq("大纲"), contains("全书大纲"));
        verify(chapterData).insertPlan(eq(1L), eq(1), eq(1), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(chapterData).insertPlan(eq(1L), eq(2), eq(1), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(outlineService).materializeScenes(eq(11L), any(), eq(false));
        verify(outlineService).materializeScenes(eq(12L), any(), eq(false));
        verify(worldStateData).upsert(eq(1L), eq(0), any());
    }

    private static String contains(String part) {
        return org.mockito.ArgumentMatchers.contains(part);
    }
}
