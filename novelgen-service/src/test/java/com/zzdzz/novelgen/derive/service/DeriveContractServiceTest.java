package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryContractMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryContractDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 合同生成服务（contract-first 计划 §2）：
 * 幂等（同版本不重复插）、结构不合规喂回重试、业务模型合并不产生新版本也不清空冻结合同。
 */
@ExtendWith(MockitoExtension.class)
class DeriveContractServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Mock
    private DeriveStoryContractMapper contractMapper;
    @Mock
    private RunMapper runMapper;
    @Mock
    private LlmJson llmJson;

    private DeriveContractService service() {
        return new DeriveContractService(contractMapper, runMapper, llmJson);
    }

    private static final PlanContracts.ScopeContract SCOPE = new PlanContracts.ScopeContract(
            PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 12, 2600, List.of("单案短篇"));

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(58L);
        run.setStatus("PLANNING");
        run.setParamTags("[\"悬疑\"]");
        run.setParamIntent("单案短篇");
        run.setParamOutcome("HEALING");
        run.setParamForm("短篇");
        return run;
    }

    /** 合同行（constraint_kernel 带 scope，load 才能还原规模边界）。 */
    private static DeriveStoryContractDO row() throws Exception {
        DeriveStoryContractDO row = new DeriveStoryContractDO();
        row.setId(11L);
        row.setRunId(58L);
        row.setVersion(1);
        row.setStatus("FROZEN");
        row.setTruthKernel("""
                {"status":"FROZEN","culpritKey":"C3","culpritName":"陆敬","motive":"旧账",
                 "method":"利用换班间隙","finalTruth":"陆敬为掩盖旧账制造失踪",
                 "finalChoice":"揭穿并承担代价","revealChapter":11}""");
        row.setCharacterSlots("""
                {"maxNamedCharacters":3,"cast":[
                  {"key":"C1","name":"林默","role":"DETECTIVE","gender":"男","identity":"值班员"},
                  {"key":"C2","name":"苏晚","role":"ACCESS","gender":"女","identity":"档案员"},
                  {"key":"C3","name":"陆敬","role":"OPPOSITION","gender":"男","identity":"仓管"}]}""");
        row.setEventKernel("""
                {"volumeTruth":[{"volumeNo":1,"reveal":"陆敬为掩盖旧账制造失踪","forbidden":[]}]}""");
        row.setClueRegistry("""
                {"clues":[
                  {"clueKey":"GM-CLUE-01","clue":"门缝纸屑","plantedChapter":3,"payoffChapter":10,"ownerKey":"C1"},
                  {"clueKey":"GM-CLUE-02","clue":"值班表涂改","plantedChapter":5,"payoffChapter":11,"ownerKey":"C1"},
                  {"clueKey":"GM-CLUE-03","clue":"旧债欠条","plantedChapter":7,"payoffChapter":11,"ownerKey":"C2"}]}""");
        row.setKnowledgeLedger("{\"facts\":[]}");
        row.setConstraintKernel("""
                {"forbidden":["不得出现第二起案件"],
                 "threads":[
                   {"threadKey":"GM-CLUE-01","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"},
                   {"threadKey":"GM-CLUE-02","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"},
                   {"threadKey":"GM-CLUE-03","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C2"}],
                 "volumeTruth":[{"volumeNo":1,"reveal":"陆敬为掩盖旧账制造失踪","forbidden":[]}],
                 "hardLimits":{"maxNamedCharacters":3,"chapterCount":12,"volumeCount":1,"chapterAvg":2600},
                 "scope":{"decision":"SINGLE_VOLUME","volumeCount":1,"chapterCount":12,
                          "chapterAvg":2600,"evidence":["单案短篇"]}}""");
        row.setSourceHash("hash");
        return row;
    }

    @Test
    void contractGenerationIsIdempotent() throws Exception {
        when(contractMapper.selectOne(any())).thenReturn(row());

        StoryContract first = service().generateIfAbsent(run(), SCOPE, null);
        StoryContract second = service().generateIfAbsent(run(), SCOPE, null);

        assertEquals(1, first.version());
        assertEquals(first.truth().culpritName(), second.truth().culpritName());
        verify(llmJson, never()).ask(any(), any(), anyInt());
        verify(contractMapper, never()).insertFull(any());
    }

    @Test
    void contractGenerationRejectsFourNames() throws Exception {
        when(contractMapper.selectOne(any())).thenReturn(null);
        com.fasterxml.jackson.databind.node.ObjectNode fourCast = M.createObjectNode();
        // 4 人 cast：解析能过，结构校验必须把 CONTRACT_CAST_LIMIT 喂回重试。
        fourCast.set("cast", M.readTree("""
                [{"key":"C1","name":"林默","role":"DETECTIVE"},
                 {"key":"C2","name":"苏晚","role":"ACCESS"},
                 {"key":"C3","name":"陆敬","role":"OPPOSITION"},
                 {"key":"C4","name":"路人","role":"SUPPORT"}]"""));
        fourCast.set("truth", M.readTree("""
                {"culpritKey":"C3","culpritName":"陆敬","motive":"旧账","method":"手法",
                 "finalTruth":"真相","finalChoice":"选择","revealChapter":11}"""));
        fourCast.set("clues", M.readTree("""
                [{"clueKey":"GM-CLUE-01","clue":"a","plantedChapter":3,"payoffChapter":4,"ownerKey":"C1"},
                 {"clueKey":"GM-CLUE-02","clue":"b","plantedChapter":5,"payoffChapter":6,"ownerKey":"C1"},
                 {"clueKey":"GM-CLUE-03","clue":"c","plantedChapter":7,"payoffChapter":8,"ownerKey":"C2"}]"""));
        fourCast.set("forbidden", M.readTree("[\"禁令\"]"));

        when(llmJson.ask(any(), any(), anyInt())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<JsonNode, StoryContract> parse = invocation.getArgument(1);
            return parse.apply(fourCast);
        });

        LlmJson.Bad bad = assertThrows(LlmJson.Bad.class,
                () -> service().generateIfAbsent(run(), SCOPE, null));

        assertTrue(bad.getMessage().contains("CONTRACT_CAST_LIMIT"), bad.getMessage());
        verify(contractMapper, never()).insertFull(any());
    }

    @Test
    void frozenContractIsNeverReplacedByBusinessModelMerge() throws Exception {
        DeriveStoryContractDO existing = row();
        when(contractMapper.selectOne(any())).thenReturn(existing);

        service().mergeBusinessModel(58L, "{\"genreCapabilities\":[\"MYSTERY\"],\"fusionDecisions\":[]}");

        ArgumentCaptor<String> kernel = ArgumentCaptor.forClass(String.class);
        verify(contractMapper).updateKernels(eq(existing.getId()), kernel.capture(), anyString());
        verify(contractMapper, never()).insertFull(any());
        assertTrue(kernel.getValue().contains("genreCapabilities"), kernel.getValue());
        assertTrue(kernel.getValue().contains("不得出现第二起案件"), "冻结合同的禁令不得被清掉");

        StoryContract reloaded = service().load(run());
        assertNotNull(reloaded);
        assertEquals("陆敬", reloaded.truth().culpritName());
        assertEquals(1, reloaded.version(), "合并能力包不得产生新合同版本");
    }

    @Test
    void missingContractFailsFastWithContractRequired() {
        when(contractMapper.selectOne(any())).thenReturn(null);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service().require(run()));

        assertTrue(error.getMessage().contains("CONTRACT_REQUIRED"), error.getMessage());
    }

    @Test
    void loadReturnsNullForLegacyStubWithoutTruth() throws Exception {
        DeriveStoryContractDO stub = row();
        stub.setTruthKernel("{\"status\":\"PROVISIONAL\",\"finalTruth\":\"\"}");
        when(contractMapper.selectOne(any())).thenReturn(stub);

        assertNull(service().load(run()));
    }

    @Test
    void legacyRowWithoutTagsStillLoads() throws Exception {
        DeriveStoryContractDO legacy = row();
        when(contractMapper.selectOne(any())).thenReturn(legacy);

        StoryContract loaded = service().load(run());

        assertNotNull(loaded);
        assertEquals("", loaded.truth().motiveTag());
        assertEquals("", loaded.truth().methodTag());
    }

    @Test
    void tagsRoundTripThroughPersistedTruthKernel() throws Exception {
        DeriveStoryContractDO tagged = row();
        tagged.setTruthKernel("""
                {"status":"FROZEN","culpritKey":"C3","culpritName":"陆敬","motive":"旧账",
                 "method":"利用换班间隙","finalTruth":"陆敬为掩盖旧账制造失踪",
                 "finalChoice":"揭穿并承担代价","revealChapter":11,
                 "motiveTag":"掩盖旧账","methodTag":"换班间隙作案"}""");
        when(contractMapper.selectOne(any())).thenReturn(tagged);

        StoryContract loaded = service().load(run());

        assertNotNull(loaded);
        assertEquals("掩盖旧账", loaded.truth().motiveTag());
        assertEquals("换班间隙作案", loaded.truth().methodTag());
    }

    @Test
    void generatedContractCarriesTagsAndUsesFreezeValidation() throws Exception {
        when(contractMapper.selectOne(any())).thenReturn(null);
        var root = M.createObjectNode();
        root.set("cast", M.readTree("""
                [{"key":"C1","name":"林默","role":"DETECTIVE"},
                 {"key":"C2","name":"苏晚","role":"ACCESS"},
                 {"key":"C3","name":"陆敬","role":"OPPOSITION"}]"""));
        root.set("truth", M.readTree("""
                {"culpritKey":"C3","culpritName":"陆敬","motive":"掩盖旧账",
                 "method":"利用换班间隙","finalTruth":"陆敬为掩盖旧账制造失踪",
                 "finalChoice":"揭穿并承担代价","revealChapter":11,
                 "motiveTag":"掩盖旧账","methodTag":"换班间隙作案"}"""));
        root.set("clues", M.readTree("""
                [{"clueKey":"GM-CLUE-01","clue":"a","plantedChapter":3,"payoffChapter":11,"ownerKey":"C1"},
                 {"clueKey":"GM-CLUE-02","clue":"b","plantedChapter":5,"payoffChapter":11,"ownerKey":"C1"},
                 {"clueKey":"GM-CLUE-03","clue":"c","plantedChapter":7,"payoffChapter":9,"ownerKey":"C2"}]"""));
        root.set("threads", M.readTree("""
                [{"threadKey":"GM-CLUE-01","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"},
                 {"threadKey":"GM-CLUE-02","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"},
                 {"threadKey":"GM-CLUE-03","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C2"}]"""));
        root.set("forbidden", M.readTree("[\"禁令\"]"));
        when(llmJson.ask(any(), any(), anyInt())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<JsonNode, StoryContract> parse = invocation.getArgument(1);
            return parse.apply(root);
        });

        StoryContract saved = service().generateIfAbsent(run(), SCOPE, null);

        assertEquals("掩盖旧账", saved.truth().motiveTag());
        assertEquals("换班间隙作案", saved.truth().methodTag());
        ArgumentCaptor<DeriveStoryContractDO> captor =
                ArgumentCaptor.forClass(DeriveStoryContractDO.class);
        verify(contractMapper).insertFull(captor.capture());
        assertTrue(captor.getValue().getTruthKernel().contains("\"motiveTag\":\"掩盖旧账\""),
                captor.getValue().getTruthKernel());
    }

    @Test
    void freezeRejectsRevealWithoutPayoffBeforePersisting() throws Exception {
        when(contractMapper.selectOne(any())).thenReturn(null);
        var root = M.createObjectNode();
        root.set("cast", M.readTree("""
                [{"key":"C1","name":"林默","role":"DETECTIVE"},
                 {"key":"C2","name":"苏晚","role":"ACCESS"},
                 {"key":"C3","name":"陆敬","role":"OPPOSITION"}]"""));
        root.set("truth", M.readTree("""
                {"culpritKey":"C3","culpritName":"陆敬","motive":"掩盖旧账",
                 "method":"利用换班间隙","finalTruth":"真相","finalChoice":"选择",
                 "revealChapter":11,"motiveTag":"掩盖旧账","methodTag":"换班间隙作案"}"""));
        root.set("clues", M.readTree("""
                [{"clueKey":"GM-CLUE-01","clue":"a","plantedChapter":3,"payoffChapter":9,"ownerKey":"C1"},
                 {"clueKey":"GM-CLUE-02","clue":"b","plantedChapter":5,"payoffChapter":9,"ownerKey":"C1"},
                 {"clueKey":"GM-CLUE-03","clue":"c","plantedChapter":7,"payoffChapter":10,"ownerKey":"C2"}]"""));
        root.set("threads", M.readTree("""
                [{"threadKey":"GM-CLUE-01","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"},
                 {"threadKey":"GM-CLUE-02","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"},
                 {"threadKey":"GM-CLUE-03","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C2"}]"""));
        root.set("forbidden", M.readTree("[\"禁令\"]"));
        when(llmJson.ask(any(), any(), anyInt())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<JsonNode, StoryContract> parse = invocation.getArgument(1);
            return parse.apply(root);
        });

        LlmJson.Bad bad = assertThrows(LlmJson.Bad.class,
                () -> service().generateIfAbsent(run(), SCOPE, null));

        assertTrue(bad.getMessage().contains("CONTRACT_REVEAL_NO_PAYOFF"), bad.getMessage());
        verify(contractMapper, never()).insertFull(any());
    }
}
