package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.DraftVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⑥b 只做合同物化（contract-first 计划 §5）：
 * HEAD A 的人名一律来自合同、HEAD C 的真相卡不得改写合同、分批窗不得出现合同外人名/编号/提前泄底。
 */
class DeriveDraftContractTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static StoryContract contract() {
        return new StoryContract(58L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙",
                        "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11,
                        "掩盖旧账", "换班间隙作案"),
                List.of(new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 3, 10, "C1"),
                        new StoryContract.Clue("GM-CLUE-02", "值班表涂改", 5, 11, "C1"),
                        new StoryContract.Clue("GM-CLUE-03", "旧债欠条", 7, 11, "C2")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-02", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-03", "CLUE", 1, 1, "C2")),
                List.of("不得出现第二起案件"),
                List.of(new StoryContract.VolumeTruth(1, "陆敬为掩盖旧账制造失踪", List.of())),
                new com.zzdzz.novelgen.derive.plan.PlanContracts.ScopeContract(
                        com.zzdzz.novelgen.derive.plan.PlanContracts.ScopeDecision.SINGLE_VOLUME,
                        1, 12, 2600, List.of("单案短篇")));
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(58L);
        return run;
    }

    private static DeriveDraftService service() throws Exception {
        DeriveDraftService service = new DeriveDraftService(null, null, null, null, null,
                null, null, null, null, null);
        DeriveContractService contractService = mock(DeriveContractService.class);
        when(contractService.require(run())).thenReturn(contract());
        when(contractService.load(run())).thenReturn(contract());
        Field field = DeriveDraftService.class.getDeclaredField("contractService");
        field.setAccessible(true);
        field.set(service, contractService);
        return service;
    }

    @Test
    void headACannotRenameOrAddContractCast() throws Exception {
        ObjectNode llmOut = M.createObjectNode();
        llmOut.put("title", "门缝里的钥匙");
        llmOut.put("summary", "一句话概括");
        llmOut.put("protagonist", "陈先生");
        llmOut.set("cast", M.readTree("""
                [{"name":"陈先生","role":"路人"},{"name":"王小姐","role":"配角"},
                 {"name":"李老板","role":"反派"},{"name":"赵女士","role":"路人"}]"""));

        ObjectNode out = service().materializeCastFromContract(run(), llmOut);

        assertEquals("林默", out.path("protagonist").asText(), "主角必须是合同 C1");
        assertEquals(3, out.path("cast").size(), "cast 必须恰好 3 人");
        assertEquals("C1", out.path("cast").get(0).path("characterKey").asText());
        assertEquals("林默", out.path("cast").get(0).path("name").asText());
        assertEquals("DETECTIVE", out.path("cast").get(0).path("function").asText());
        assertFalse(out.toString().contains("王小姐"), "合同外人名必须被丢弃");
    }

    @Test
    void headCPromptNoLongerAsksForTruth() {        assertFalse(DeriveDraftService.HEAD_C_PROMPT.contains("\"truth\""),
                "HEAD C 不再要求模型输出真相卡");
        assertTrue(DeriveDraftService.HEAD_C_PROMPT.contains("冻结合同"),
                "HEAD C 必须声明真相由冻结合同决定");
    }

    @Test
    void headCDoesNotWriteTruth() throws Exception {
        RunDO run = new RunDO();
        run.setId(58L);
        run.setNovelId(120L);
        run.setParamTags("[\"悬疑\"]");
        run.setParamPace(new java.math.BigDecimal("0.75"));
        run.setParamOutcome("闭合");
        run.setParamForm("SHORT_STANDALONE");
        NovelDO novel = new NovelDO();
        novel.setId(120L);
        novel.setStoryAssets("""
                {"protagonist":"林默","title":"门缝",
                 "cast":[
                   {"name":"林默","role":"值班员","persona":{"temperament":"a","conduct":"b",
                     "speech":"c","desire":"d","flaw":"e"}},
                   {"name":"苏晚","role":"档案员","persona":{"temperament":"a","conduct":"b",
                     "speech":"c","desire":"d","flaw":"e"}},
                   {"name":"陆敬","role":"仓管","persona":{"temperament":"a","conduct":"b",
                     "speech":"c","desire":"d","flaw":"e"}}],
                 "brief":{"premise":""}}
                """);
        RunMapper runMapper = mock(RunMapper.class);
        NovelMapper novelMapper = mock(NovelMapper.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        BeatInstanceMapper beatInstanceMapper = mock(BeatInstanceMapper.class);
        LlmJson llmJson = mock(LlmJson.class);
        DeriveDraftService service = new DeriveDraftService(runMapper, novelMapper, chapterMapper,
                beatInstanceMapper, null, null, null, null, null, llmJson);
        DeriveContractService contractService = mock(DeriveContractService.class);
        when(contractService.load(run)).thenReturn(contract());
        JsonNode frozen = M.readTree("""
                {"reveal_chapter":11,"solution":{"culprit":"陆敬","motive":"旧账","method":"利用换班间隙"},
                 "finalTruth":"陆敬为掩盖旧账制造失踪"}
                """);
        when(contractService.frozenTruthCard(run)).thenReturn(frozen);
        Field field = DeriveDraftService.class.getDeclaredField("contractService");
        field.setAccessible(true);
        field.set(service, contractService);
        when(llmJson.ask(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Function<JsonNode, JsonNode> parse =
                    invocation.getArgument(1);
            return parse.apply(M.readTree("{\"brief\":{\"premise\":\"旧楼里的失踪\"}}"));
        });

        var vo = service.headStepForTest(run, novel, 2,
                List.of(chapter(11)), List.of(11), true, "");

        org.mockito.ArgumentCaptor<String> truth = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(novelMapper).updateTruth(
                org.mockito.ArgumentMatchers.eq(120L), truth.capture());
        assertEquals(frozen.toString(), truth.getValue(),
                "落库真相卡必须等于合同投影，而不是 LLM 输出");
        assertEquals("HEAD", vo.unit());
    }

    @Test
    void batchClueEventsMustMatchSchedule() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[{"no":3,"title":"纸屑","draft_outline":"林默发现门缝纸屑",
                  "time":"当晚","state_changes":[],"clueEvents":[],"closingActs":[],
                  "beats":[{"seq":1,"filled":"林默检查门缝"}]}]}""");

        List<String> drift = DeriveDraftService.validateScheduleEvents(
                out, schedule(), contract());

        assertTrue(drift.stream().anyMatch(s -> s.contains("PLANT")), String.join("；", drift));
    }

    @Test
    void batchCannotPlantEarly() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[{"no":3,"title":"纸屑","draft_outline":"林默发现门缝纸屑",
                  "time":"当晚","state_changes":[],
                  "clueEvents":[{"key":"GM-CLUE-02","act":"PLANT"}],"closingActs":[],
                  "beats":[{"seq":1,"filled":"林默检查门缝"}]}]}""");

        List<String> drift = DeriveDraftService.validateScheduleEvents(
                out, schedule(), contract());

        assertTrue(drift.stream().anyMatch(s -> s.contains("CONTRACT_CLUE_DRIFT")),
                String.join("；", drift));
    }

    @Test
    void batchClosingActsRequiredAfterReveal() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[{"no":11,"title":"旧账","draft_outline":"陆敬承认旧账",
                  "time":"三日后","state_changes":[],
                  "clueEvents":[{"key":"GM-CLUE-02","act":"PAYOFF"},
                                {"key":"GM-CLUE-03","act":"PAYOFF"}],"closingActs":[],
                  "beats":[{"seq":1,"filled":"林默锁定陆敬"}]}]}""");

        List<String> drift = DeriveDraftService.validateScheduleEvents(
                out, schedule(), contract());

        assertTrue(drift.stream().anyMatch(s -> s.contains("closingActs")),
                String.join("；", drift));
    }

    @Test
    void batchCulpritNameMustAppear() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[{"no":11,"title":"旧账","draft_outline":"有人承认了旧账",
                  "time":"三日后","state_changes":[],
                  "clueEvents":[{"key":"GM-CLUE-02","act":"PAYOFF"},
                                {"key":"GM-CLUE-03","act":"PAYOFF"}],
                  "closingActs":["CULPRIT","MOTIVE","METHOD"],
                  "beats":[{"seq":1,"filled":"林默锁定陆敬"}]}]}""");

        List<String> drift = DeriveDraftService.validateScheduleEvents(
                out, schedule(), contract());

        assertTrue(drift.stream().noneMatch(s -> s.contains("真凶")), String.join("；", drift));
    }

    @Test
    void batchCleanScheduleEventsHaveNoDrift() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[
                  {"no":3,"title":"纸屑","draft_outline":"林默发现门缝纸屑",
                   "time":"当晚","state_changes":[],
                   "clueEvents":[{"key":"GM-CLUE-01","act":"PLANT"}],"closingActs":[],
                   "beats":[{"seq":1,"filled":"林默检查门缝"}]},
                  {"no":11,"title":"旧账","draft_outline":"陆敬被揭穿利用换班间隙掩盖旧账",
                   "time":"三日后","state_changes":[],
                   "clueEvents":[{"key":"GM-CLUE-02","act":"PAYOFF"},
                                 {"key":"GM-CLUE-03","act":"PAYOFF"}],
                   "closingActs":["CULPRIT","MOTIVE","METHOD"],
                   "beats":[{"seq":2,"filled":"林默锁定陆敬"}]}]}""");

        assertTrue(DeriveDraftService.validateScheduleEvents(out, schedule(), contract()).isEmpty());
    }

    @Test
    void batchCannotIntroduceUnknownName() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[{"no":1,"title":"夜半敲门","draft_outline":"陈先生送来一封旧信",
                  "time":"当晚","state_changes":[],"beats":[{"seq":1,"filled":"林默与陈先生对峙"}]}]}""");

        List<String> drift = DeriveDraftService.validateBatch(out, contract());

        assertTrue(drift.stream().anyMatch(s -> s.startsWith("CONTRACT_NAME_DRIFT")),
                String.join("；", drift));
    }

    @Test
    void batchCannotIntroduceUnknownThreadKey() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[{"no":1,"title":"夜半敲门","draft_outline":"线索 GM-CLUE-99 指向仓库",
                  "time":"当晚","state_changes":[],"beats":[{"seq":1,"filled":"林默核对 GM-CLUE-99"}]}]}""");

        List<String> drift = DeriveDraftService.validateBatch(out, contract());

        assertTrue(drift.stream().anyMatch(s -> s.startsWith("CONTRACT_THREAD_DRIFT")),
                String.join("；", drift));
    }

    @Test
    void batchCannotRevealSolutionBeforeRevealChapter() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[{"no":3,"title":"旧账","draft_outline":"林默查明陆敬利用换班间隙掩盖旧账",
                  "time":"三日后","state_changes":[],"beats":[{"seq":1,"filled":"林默锁定陆敬"}]}]}""");

        List<String> drift = DeriveDraftService.validateBatch(out, contract());

        assertTrue(drift.stream().anyMatch(s -> s.startsWith("CONTRACT_DRIFT")),
                String.join("；", drift));
    }

    @Test
    void batchCleanOutputHasNoDrift() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[{"no":3,"title":"旧账","draft_outline":"林默在值班室发现被涂改的记录",
                  "time":"三日后","state_changes":[{"who":"林默","what":"得知","detail":"记录被涂改"}],
                  "beats":[{"seq":1,"filled":"林默核对值班表并记下疑点"}]}]}""");

        assertTrue(DeriveDraftService.validateBatch(out, contract()).isEmpty());
    }

    private static ChapterDO chapter(int no) {
        ChapterDO chapter = new ChapterDO();
        chapter.setId(100L + no);
        chapter.setNovelId(120L);
        chapter.setChapterNo(no);
        chapter.setTitle("第" + no + "章");
        chapter.setBudget(2600);
        return chapter;
    }

    /** 只覆盖断言用到的章：第 3 章埋 01，第 11 章（揭示）兑 02/03。 */
    private static List<ChapterScheduleEntry> schedule() {
        return List.of(
                new ChapterScheduleEntry(3, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(11, 1, true, List.of(),
                        List.of("GM-CLUE-02", "GM-CLUE-03")));
    }

    @Test
    void unknownPersonNamesFiltersDemonstratives() {
        StoryContract value = contract();

        assertTrue(DeriveDraftService.unknownPersonNames("那位先生走了", value).isEmpty());
        assertTrue(DeriveDraftService.unknownPersonNames("一位先生来访", value).isEmpty());
        assertTrue(DeriveDraftService.unknownPersonNames("我先生不在家", value).isEmpty());
        assertEquals(List.of("陈先生"),
                DeriveDraftService.unknownPersonNames("陈先生送来一封信", value));
        assertTrue(DeriveDraftService.unknownPersonNames("林默先生回来了", value).isEmpty(),
                "合同人名的称谓写法必须放行");
    }

    @Test
    void batchRevealOutlineMustContainMotiveTag() throws Exception {
        JsonNode out = M.readTree("""
                {"chapters":[
                  {"no":11,"title":"旧账","draft_outline":"陆敬被揭穿利用换班间隙实施失踪案",
                   "time":"三日后","state_changes":[],
                   "clueEvents":[{"key":"GM-CLUE-02","act":"PAYOFF"},
                                 {"key":"GM-CLUE-03","act":"PAYOFF"}],
                   "closingActs":["CULPRIT","MOTIVE","METHOD"],
                   "beats":[{"seq":2,"filled":"林默锁定陆敬"}]}]}""");

        List<String> drift = DeriveDraftService.validateScheduleEvents(
                out, schedule(), contract());

        assertTrue(drift.stream().noneMatch(s ->
                        s.startsWith("CONTRACT_CLOSURE_DRIFT")),
                String.join("；", drift));
    }
}
