package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.enums.ChapterStatus;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * digest 输出畸形基线（2026-09-30 探针书第 3 章实测）：模型把根级 new_threads 写进 state 内部，
 * 且末尾漏写一个收口 }，整章 digest 直接解析失败（fail-open 记「2/3 章」，事实账/状态/伏笔全丢）。
 * 修法两层：LlmJson 结构补齐让解析过；本类负责把误嵌的 new_threads 抬回根层并从快照剔除。
 * 原始模型输出见 fixtures/digest_new_threads_nested_in_state.txt（逐字节存证）。
 */
class DigestServiceTest {

    private LlmPort llm;
    private DigestDataService digestData;
    private ForeshadowDataService foreshadowData;
    private ChapterDataService chapterData;
    private WorldStateDataService worldStateData;
    private CharacterStateService characterState;
    private DigestService service;

    @BeforeEach
    void setUp() {
        llm = mock(LlmPort.class);
        digestData = mock(DigestDataService.class);
        foreshadowData = mock(ForeshadowDataService.class);
        chapterData = mock(ChapterDataService.class);
        worldStateData = mock(WorldStateDataService.class);
        characterState = mock(CharacterStateService.class);
        service = new DigestService(llm, new LlmJson(null, null), digestData, foreshadowData,
                chapterData, worldStateData, characterState,
                mock(PromptTemplateService.class), mock(TuningService.class));
        // Mockito 对 String 返回型默认给 null：不显式打桩的话 insertProposal 的编码位是 null，
        // anyString() 匹配不上（同族坑：Long 返回型默认 0）
        when(foreshadowData.nextCode(anyLong())).thenReturn("F001");
    }

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/digest_new_threads_nested_in_state.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void llmReturns(String content) {
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResult(1L, content, null, new LlmPort.Usage(0, 0, 0)));
    }

    @Test
    void nestedNewThreadsLiftedOutOfStateAndProposed() throws Exception {
        llmReturns(fixture());

        service.digest(9L, 301L, 3, "本章正文");

        // 状态快照落库且不含误嵌的 new_threads——否则该键会随世界状态注入后续章节
        ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
        verify(worldStateData).upsert(eq(9L), eq(3), state.capture());
        JsonNode snapshot = (JsonNode) state.getValue();
        assertThat(snapshot.has("new_threads")).isFalse();
        assertThat(snapshot.path("unresolved").size()).isEqualTo(5);

        // 两条提议抬回根层后照常落库
        ArgumentCaptor<String> contents = ArgumentCaptor.forClass(String.class);
        verify(foreshadowData, times(2)).insertProposal(eq(9L), anyString(), contents.capture(), eq(3));
        assertThat(contents.getAllValues().get(0)).startsWith("黑潮递补：");
        assertThat(contents.getAllValues().get(1)).startsWith("灯数水面：");

        verify(digestData).insert(eq(301L), anyString(), any());
        verify(chapterData).updateStatus(301L, ChapterStatus.DIGESTED.wire());
    }

    @Test
    void rootLevelNewThreadsStillWork() throws Exception {
        llmReturns("{\"summary_md\":\"摘要\",\"facts\":[\"事实\"],\"state\":{\"time\":\"夜\"},"
                + "\"new_threads\":[{\"name\":\"暗线\",\"content\":\"一条跨章长线\"}]}");

        service.digest(9L, 301L, 3, "本章正文");

        ArgumentCaptor<String> contents = ArgumentCaptor.forClass(String.class);
        verify(foreshadowData).insertProposal(eq(9L), anyString(), contents.capture(), eq(3));
        assertThat(contents.getValue()).isEqualTo("暗线：一条跨章长线");
        verify(chapterData).updateStatus(301L, ChapterStatus.DIGESTED.wire());
    }

    @Test
    void rootLevelWinsWhenBothPresent() throws Exception {
        llmReturns("{\"summary_md\":\"摘要\",\"facts\":[],\"state\":{\"time\":\"夜\","
                + "\"new_threads\":[{\"name\":\"误嵌\",\"content\":\"不该用的副本\"}]},"
                + "\"new_threads\":[{\"name\":\"正主\",\"content\":\"根层那条\"}]}");

        service.digest(9L, 301L, 3, "本章正文");

        ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
        verify(worldStateData).upsert(eq(9L), eq(3), state.capture());
        assertThat(((JsonNode) state.getValue()).has("new_threads")).isFalse();

        ArgumentCaptor<String> contents = ArgumentCaptor.forClass(String.class);
        verify(foreshadowData).insertProposal(eq(9L), anyString(), contents.capture(), eq(3));
        assertThat(contents.getValue()).isEqualTo("正主：根层那条");
    }

    /** 人物账（V39）投影钩子：快照落库后必须把同一份状态交给 CharacterStateService 投影。 */
    @Test
    void projectsCharacterStateFromTheSameSnapshot() throws Exception {
        llmReturns(fixture());

        service.digest(9L, 301L, 3, "本章正文");

        ArgumentCaptor<JsonNode> state = ArgumentCaptor.forClass(JsonNode.class);
        verify(characterState).project(eq(9L), eq(3), state.capture());
        assertThat(state.getValue().path("new_threads").isMissingNode()).isTrue(); // 抬升后的快照才投影
    }

    /** 投影失败不许连累 digest（账缺这一章，下次回填或重投影补上）。 */
    @Test
    void projectFailureDoesNotBreakDigest() throws Exception {
        llmReturns(fixture());
        org.mockito.Mockito.doThrow(new IllegalStateException("投影炸了"))
                .when(characterState).project(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());

        boolean computed = service.digest(9L, 301L, 3, "本章正文");

        assertThat(computed).isTrue();
        verify(chapterData).updateStatus(301L, ChapterStatus.DIGESTED.wire());
    }

    @Test
    void existingDigestSkipsWithoutCallingLlm() {
        when(digestData.existsByChapter(301L)).thenReturn(true);

        boolean computed = service.digest(9L, 301L, 3, "本章正文");

        assertThat(computed).isFalse();   // 返回 false＝本次没算（步消息据此区分「已补」与「跳过」）
        verify(llm, times(0)).chat(any());
        verify(chapterData).updateStatus(301L, ChapterStatus.DIGESTED.wire());
    }

    @Test
    void forceRecomputesInPlaceWithoutSecondRow() throws Exception {
        // 「覆盖已有」：重算并原地更新那一行事实账（不是插出第二行）
        when(digestData.findIdByChapter(301L)).thenReturn(77L);
        llmReturns("{\"summary_md\":\"新摘要\",\"facts\":[\"新事实\"],\"state\":{\"time\":\"夜\"},"
                + "\"new_threads\":[{\"name\":\"新线\",\"content\":\"跨章长线\"}]}");

        boolean computed = service.digest(9L, 301L, 3, "本章正文", true);

        assertThat(computed).isTrue();   // 算了＝返回 true
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(digestData).updateContent(eq(77L), summary.capture(), anyString());
        assertThat(summary.getValue()).isEqualTo("新摘要");
        verify(digestData, times(0)).insert(anyLong(), anyString(), anyString());
        verify(worldStateData).upsert(eq(9L), eq(3), any());
        verify(foreshadowData).insertProposal(eq(9L), anyString(), anyString(), eq(3));
    }

    @Test
    void forceWithoutExistingRowStillInserts() throws Exception {
        when(digestData.findIdByChapter(301L)).thenReturn(null);
        llmReturns("{\"summary_md\":\"摘要\",\"facts\":[],\"state\":{\"time\":\"夜\"}}");

        service.digest(9L, 301L, 3, "本章正文", true);

        verify(digestData).insert(eq(301L), anyString(), anyString());
        verify(digestData, times(0)).updateContent(anyLong(), anyString(), anyString());
    }
}
