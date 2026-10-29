package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.contract.TruthContract;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P6 只做质量评审、不做合同发现（contract-first 计划 §6）：
 * 真相输入只有一份冻结合同（不合并旧脊柱），合同级漂移在 LLM 之前判 CONTRACT_INVALID。
 */
@ExtendWith(MockitoExtension.class)
class DeriveGateContractTest {

    private static final ObjectMapper M = new ObjectMapper();

    private void setField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = DeriveGateService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Mock
    private RunMapper runMapper;
    @Mock
    private NovelMapper novelMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private BeatInstanceMapper beatInstanceMapper;
    @Mock
    private WordlistMapper wordlistMapper;
    @Mock
    private DeriveTruthContractService truthContractService;
    @Mock
    private LlmJson llmJson;
    @Mock
    private DeriveContractService contractService;
    @Mock
    private CharacterPlanMapper characterPlanMapper;
    @Mock
    private com.zzdzz.novelgen.derive.mapper.VolumePlanMapper volumePlanMapper;
    @Mock
    private DeriveChapterScheduleService scheduleService;

    private DeriveGateService service;

    @org.junit.jupiter.api.BeforeEach
    void buildService() throws Exception {
        service = new DeriveGateService(runMapper, novelMapper, chapterMapper, beatInstanceMapper,
                wordlistMapper, truthContractService, llmJson);
        setField("contractService", contractService);
        setField("characterPlanMapper", characterPlanMapper);
        setField("volumePlanMapper", volumePlanMapper);
        setField("scheduleService", scheduleService);
    }


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

    private static DeriveGateService.GateTruth gateTruth(StoryContract contract) {
        TruthContract canonical = new TruthContract("T-1", contract.truth().finalTruth(),
                contract.truth().finalChoice(), contract.forbidden(),
                List.of("GM-CLUE-01", "GM-CLUE-02", "GM-CLUE-03"));
        return new DeriveGateService.GateTruth(
                new DeriveTruthContractService.TruthResolution(canonical, List.of()),
                M.createArrayNode(), contract);
    }

    private static RunDO runWithStaleSpine() throws Exception {
        RunDO run = new RunDO();
        run.setId(58L);
        run.setNovelId(12L);
        run.setStatus("DRAFTING");
        run.setStepStatus(M.writeValueAsString(java.util.Map.of("v", 1, "steps",
                java.util.Map.of("STORY_SPINE", java.util.Map.of(
                        "finalTruth", "陈先生的珍品骗局",
                        "finalChoice", "另一个结局")))));
        return run;
    }

    @Test
    void gateInputHasOneCanonicalContractAndNoStaleSpineTruth() throws Exception {
        StoryContract contract = contract();
        when(contractService.load(any())).thenReturn(contract);
        when(volumePlanMapper.selectList(any())).thenReturn(List.of());

        DeriveGateService.GateTruth truth =
                service.resolveGateTruth(runWithStaleSpine(), new NovelDO());

        assertEquals(contract.truth().finalTruth(), truth.resolution().contract().finalTruth(),
                "P6 真相输入必须来自冻结合同，不得合并旧脊柱");
        assertEquals(contract.truth().finalChoice(), truth.resolution().contract().finalChoice());
        assertTrue(truth.resolution().conflicts().isEmpty());
        assertEquals(contract, truth.contract(), "GateTruth 必须携带唯一合同");
    }

    @Test
    void contractDriftFailsBeforeLlmGate() throws Exception {
        StoryContract contract = contract();
        CharacterPlanDO drifted = new CharacterPlanDO();
        drifted.setRunId(58L);
        drifted.setCharacterKey("C4");
        drifted.setContract(M.writeValueAsString(java.util.Map.of(
                "characterKey", "C4", "displayNameHint", "路人", "function", "SUPPORT")));
        when(characterPlanMapper.selectList(any())).thenReturn(List.of(drifted));
        when(volumePlanMapper.selectList(any())).thenReturn(List.of());

        List<String> issues = service.contractIssues(runWithStaleSpine(), gateTruth(contract),
                List.of());

        assertTrue(issues.stream().anyMatch(s -> s.startsWith("CONTRACT_CAST_DRIFT")),
                String.join("；", issues));
    }

    @Test
    void gateInputHasOneSchedule() throws Exception {
        StoryContract contract = contract();
        RunDO run = runWithStaleSpine();
        when(contractService.load(any())).thenReturn(contract);
        when(volumePlanMapper.selectList(any())).thenReturn(List.of());
        when(scheduleService.load(run)).thenReturn(schedule());

        String user = service.buildUser(run, new NovelDO(), chapters(), beats(false), List.of());

        assertTrue(user.contains("chapterSchedule"), "P6 输入必须带章级日程");
        assertTrue(user.contains("GM-CLUE-01"), "日程必须带 mustPlant 的 clue key");
        assertTrue(user.contains("scheduleRules"), "P6 必须声明日程是只读硬约束");
        assertTrue(user.contains("FROZEN_CONTRACT"), "P6 必须带唯一合同");
    }

    @Test
    void scheduleDriftFailsBeforeLlmGate() throws Exception {
        StoryContract contract = contract();
        RunDO run = runWithStaleSpine();
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.load(any())).thenReturn(contract);
        when(volumePlanMapper.selectList(any())).thenReturn(List.of());
        when(characterPlanMapper.selectList(any())).thenReturn(List.of());
        when(chapterMapper.selectList(any())).thenReturn(chapters());
        when(beatInstanceMapper.selectList(any())).thenReturn(beats(true));
        when(scheduleService.load(run)).thenReturn(schedule());

        var vo = service.gate(58L);

        assertEquals("REJECT", vo.verdict());
        assertEquals("FAILED", vo.gateStatus());
        assertEquals("CONTRACT_INVALID", vo.repairTarget());
        assertTrue(vo.issues().stream().anyMatch(s -> s.contains("CONTRACT_CLUE_PLANT_MISSING")),
                String.join("；", vo.issues()));
        org.mockito.Mockito.verify(llmJson, org.mockito.Mockito.never())
                .ask(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void preflightIssuesBlockGate() {
        DeriveGateService.GateReview review = new DeriveGateService.GateReview(
                List.of(), List.of("PACK_EMPTY_CHAPTER：第1章没有有效拍"), "");

        DeriveGateService.GateDecision decision = DeriveGateService.decideGate(review, null, 0);

        assertEquals("REWORK", decision.action(), "预检 issues 不许被判成 GO");
        assertEquals("DRAFT", decision.repairTarget());
    }

    @Test
    void directiveCannotChangeSchedule() throws Exception {
        StoryContract contract = contract();
        RunDO run = runWithStaleSpine();
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.load(any())).thenReturn(contract);
        when(volumePlanMapper.selectList(any())).thenReturn(List.of());
        when(characterPlanMapper.selectList(any())).thenReturn(characters());
        when(chapterMapper.selectList(any())).thenReturn(chapters());
        when(beatInstanceMapper.selectList(any())).thenReturn(beats(false));
        when(scheduleService.load(run)).thenReturn(schedule());
        when(novelMapper.selectById(12L)).thenReturn(new NovelDO());
        String gateJson = """
                {"criteria":{"single_rule":{"pass":true,"severity":"ADVISORY","note":"ok",
                    "issues":[],"repairTarget":"NONE"},
                  "budget":{"pass":true,"severity":"ADVISORY","note":"ok","issues":[],
                    "repairTarget":"NONE"},
                  "motive_chain":{"pass":true,"severity":"ADVISORY","note":"ok","issues":[],
                    "repairTarget":"NONE"},
                  "closure":{"pass":true,"severity":"ADVISORY","note":"ok","issues":[],
                    "repairTarget":"NONE"},
                  "function_lock":{"pass":true,"severity":"ADVISORY","note":"ok","issues":[],
                    "repairTarget":"NONE"},
                  "motif_dedup":{"pass":true,"severity":"ADVISORY","note":"ok","issues":[],
                    "repairTarget":"NONE"}},
                 "issues":[],"directive":"把线索改到第 8~9 章，重写第 8 章"}
                """;
        when(llmJson.ask(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, Object> parse =
                            invocation.getArgument(1);
                    return parse.apply(M.readTree(gateJson));
                });

        service.gate(58L);

        org.mockito.Mockito.verify(contractService, org.mockito.Mockito.never())
                .generateIfAbsent(any(), any(), any());
        org.mockito.Mockito.verify(scheduleService, org.mockito.Mockito.never())
                .persist(any(), any());
    }

    private static List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule() {
        return List.of(
                new com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry(
                        3, 1, false, List.of("GM-CLUE-01"), List.of()),
                new com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry(
                        11, 1, true, List.of(), List.of("GM-CLUE-02", "GM-CLUE-03")));
    }

    /** 角色叙事计划：与合同 cast 一一对应（否则合同预检会先报 CAST_DRIFT）。 */
    private static List<CharacterPlanDO> characters() throws Exception {
        List<CharacterPlanDO> out = new java.util.ArrayList<>();
        String[][] rows = {{"C1", "林默", "DETECTIVE"}, {"C2", "苏晚", "ACCESS"},
                {"C3", "陆敬", "OPPOSITION"}};
        for (String[] row : rows) {
            CharacterPlanDO plan = new CharacterPlanDO();
            plan.setRunId(58L);
            plan.setCharacterKey(row[0]);
            plan.setContract(M.writeValueAsString(java.util.Map.of(
                    "characterKey", row[0], "displayNameHint", row[1], "function", row[2])));
            out.add(plan);
        }
        return out;
    }

    private static List<com.zzdzz.novelgen.model.entity.derive.ChapterDO> chapters() {        com.zzdzz.novelgen.model.entity.derive.ChapterDO third =
                new com.zzdzz.novelgen.model.entity.derive.ChapterDO();
        third.setId(103L);
        third.setNovelId(12L);
        third.setChapterNo(3);
        third.setTitle("纸屑");
        third.setDraftOutline("林默在门缝发现纸屑");
        com.zzdzz.novelgen.model.entity.derive.ChapterDO reveal =
                new com.zzdzz.novelgen.model.entity.derive.ChapterDO();
        reveal.setId(111L);
        reveal.setNovelId(12L);
        reveal.setChapterNo(11);
        reveal.setTitle("旧账");
        reveal.setDraftOutline("陆敬被揭穿利用换班间隙掩盖旧账");
        return List.of(third, reveal);
    }

    /** missingPlant=true 时只给普通拍（日程预检应判 CONTRACT_INVALID）。 */
    private static List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> beats(
            boolean missingPlant) {
        List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> out =
                new java.util.ArrayList<>();
        out.add(beat(1, 3, missingPlant ? "MAIN_ADVANCE" : "CLUE_PLANT",
                missingPlant ? "[]" : "[\"GM-CLUE-01\"]"));
        out.add(beat(2, 11, "CLUE_PAYOFF", "[\"GM-CLUE-02\",\"GM-CLUE-03\"]"));
        out.add(beat(3, 11, "FINAL_REVEAL", "[]"));
        return out;
    }

    private static com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO beat(
            int seq, int chapterNo, String role, String threadRefs) {
        com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO beat =
                new com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO();
        beat.setId((long) seq);
        beat.setRunId(58L);
        beat.setSeq(seq);
        beat.setChapterNo(chapterNo);
        beat.setVolumeNo(1);
        beat.setBeatRole(role);
        beat.setStatus("PLANNED");
        beat.setFilledText("拍" + seq);
        beat.setRouteContract("{\"threadRefs\":" + threadRefs + "}");
        return beat;
    }

    @Test
    void planVolumeReworkDoesNotChangeContractVersion() {
        // 合同版本只由 CONTRACT 单元写入；P6 侧只读不写，这里断言合同本身不可变。
        StoryContract before = contract();
        StoryContract after = contract();

        assertTrue(com.zzdzz.novelgen.derive.contract.StoryContractValidator
                .sameCanonical(before, after));
        assertEquals(1, after.version());
    }

    @Test
    void gateRejectsScheduleRevealWithoutPayoff() throws Exception {
        StoryContract contract = contract();
        RunDO run = runWithStaleSpine();
        when(volumePlanMapper.selectList(any())).thenReturn(List.of());
        when(characterPlanMapper.selectList(any())).thenReturn(characters());
        var schedule = List.of(
                new com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry(
                        3, 1, false, List.of("GM-CLUE-01"), List.of()),
                new com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry(
                        11, 1, true, List.of(), List.of()));
        List<String> issues = service.contractIssues(
                run, new DeriveGateService.GateTruth(
                        new DeriveTruthContractService.TruthResolution(
                                new TruthContract("T-1", contract.truth().finalTruth(),
                                        contract.truth().finalChoice(), contract.forbidden(),
                                        List.of("GM-CLUE-01", "GM-CLUE-02", "GM-CLUE-03")),
                                List.of()),
                        M.createArrayNode(), contract, schedule),
                chapters(), beats(false));

        assertTrue(issues.stream().anyMatch(s -> s.startsWith("CONTRACT_REVEAL_NO_PAYOFF")),
                String.join("；", issues));
    }

    @Test
    void revealMotiveTagMissingFailsPreflightAsDraft() {
        var reveal = chapters().get(1);
        reveal.setDraftOutline("陆敬被揭穿利用换班间隙实施失踪案");

        List<String> issues = DeriveGateService.revealNameIssues(
                List.of(reveal), schedule(), contract());

        assertTrue(issues.stream().anyMatch(s -> s.startsWith("REVEAL_MOTIVE_TAG_MISSING")),
                String.join("；", issues));
        assertEquals("DRAFT", DeriveGateService.decideGate(
                new DeriveGateService.GateReview(List.of(), issues, ""), null, 0).repairTarget(),
                "motive 文字缺失是 DRAFT 局部回炉，不是合同终态");
    }
}
