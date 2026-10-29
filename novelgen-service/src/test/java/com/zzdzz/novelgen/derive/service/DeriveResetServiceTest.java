package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.*;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryContractDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanV2StateCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P6 根因回炉的状态保留口径与合同冻结口径（contract-first 计划 §7）。 */
@ExtendWith(MockitoExtension.class)
class DeriveResetServiceTest {

    @Mock
    private RunMapper runMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private NovelMapper novelMapper;
    @Mock
    private BeatInstanceMapper beatInstanceMapper;
    @Mock
    private QcReportMapper qcReportMapper;
    @Mock
    private CalibrationMapper calibrationMapper;
    @Mock
    private DraftHistoryMapper draftHistoryMapper;
    @Mock
    private WorldStateMapper worldStateMapper;
    @Mock
    private WorkFingerprintMapper workFingerprintMapper;
    @Mock
    private VolumePlanMapper volumePlanMapper;
    @Mock
    private CharacterPlanMapper characterPlanMapper;
    @Mock
    private StoryThreadMapper storyThreadMapper;
    @Mock
    private DeriveStoryContractMapper storyContractMapper;
    @Mock
    private DeriveChapterScheduleService scheduleService;
    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks
    private DeriveResetService service;

    private static final ObjectMapper M = new ObjectMapper();
    @org.junit.jupiter.api.BeforeEach
    void injectOptionalFields() throws Exception {
        java.lang.reflect.Field contractField = DeriveResetService.class
                .getDeclaredField("storyContractMapper");
        contractField.setAccessible(true);
        contractField.set(service, storyContractMapper);
        java.lang.reflect.Field scheduleField = DeriveResetService.class
                .getDeclaredField("scheduleService");
        scheduleField.setAccessible(true);
        scheduleField.set(service, scheduleService);
    }

    @Test
    void spineResetKeepsScaleAndGateRoundButDropsDownstreamPlan() throws Exception {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 12, 2600,
                List.of("单案短篇"));
        PlanContracts.PlanV2State done = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.DONE,
                List.of(PlanContracts.PlanUnit.SCALE, PlanContracts.PlanUnit.SPINE),
                scope, Map.of(1, PlanContracts.PlanUnit.VALIDATE));
        String status = M.writeValueAsString(Map.of("v", 1, "steps", Map.of(
                "PLAN_V2", done,
                "STORY_SPINE", Map.of("mainMystery", "旧脊柱"),
                "TRUTH_CONTRACT", Map.of("truthId", "T-old"),
                "DRAFT", Map.of("batch", 2),
                "GATE", Map.of(
                        "status", "REWORK",
                        "round", 1,
                        "directive", "重建角色弧",
                        "repairTarget", "SPINE",
                        "issues", List.of("主线矛盾"),
                        "criteria", Map.of()))));

        String reset = DeriveResetService.resetPlanState(status, "SPINE");
        PlanContracts.PlanV2State state = PlanV2StateCodec.read(
                M.readTree(reset).path("steps").path("PLAN_V2").toString());

        assertEquals(PlanContracts.PlanUnit.SPINE, state.currentUnit());
        assertEquals(scope, state.scope());
        assertTrue(state.volumeUnits().isEmpty());
        assertEquals(1, M.readTree(reset).path("steps").path("GATE").path("round").asInt(), "根因回炉保留轮次，禁止无限重置");
        assertEquals("重建角色弧",
                M.readTree(reset).path("steps").path("GATE").path("directive").asText());
        assertTrue(M.readTree(reset).path("steps").path("STORY_SPINE").isMissingNode());
        assertEquals("T-old", M.readTree(reset).path("steps").path("TRUTH_CONTRACT").path("truthId").asText(),
                "真相合同跨根因回炉保留");
        assertTrue(M.readTree(reset).path("steps").path("DRAFT").isMissingNode());
    }

    @Test
    void scaleResetDropsScope() throws Exception {
        String status = """
                {"v":1,"steps":{"PLAN_V2":{
                  "currentUnit":"DONE","completedUnits":["SCALE"],
                  "scope":{"decision":"SINGLE_VOLUME","volumeCount":1,
                    "chapterCount":12,"chapterAvg":2600,"evidence":[]},
                  "volumeUnits":{"1":"VALIDATE"}
                }}}
                """;

        String reset = DeriveResetService.resetPlanState(status, "SCALE");
        PlanContracts.PlanV2State state = PlanV2StateCodec.read(
                M.readTree(reset).path("steps").path("PLAN_V2").toString());

        assertEquals(PlanContracts.PlanUnit.SCALE, state.currentUnit());
        assertEquals(null, state.scope());
    }

    @Test
    void spineResetKeepsContractCaches() throws Exception {
        String status = """
                {"v":1,"steps":{
                  "PLAN_V2":{"currentUnit":"DONE","completedUnits":["SCALE","CONTRACT","SPINE"],
                    "scope":{"decision":"SINGLE_VOLUME","volumeCount":1,"chapterCount":12,
                             "chapterAvg":2600,"evidence":[]},"volumeUnits":{"1":"VALIDATE"}},
                  "STORY_SPINE":{"finalTruth":"旧脊柱"},
                  "STORY_CONTRACT":{"truth":{"finalTruth":"冻结合同"}},
                  "TRUTH_CONTRACT":{"truthId":"T-1","finalTruth":"冻结合同"}}}
                """;
        String reset = DeriveResetService.resetPlanState(status, "SPINE");

        assertEquals("冻结合同",
                M.readTree(reset).path("steps").path("TRUTH_CONTRACT").path("finalTruth").asText());
        assertTrue(M.readTree(reset).path("steps").path("STORY_CONTRACT").isObject(),
                "SPINE 回炉不得清合同快照");
    }

    @Test
    void scaleResetDropsContractCaches() throws Exception {
        String status = """
                {"v":1,"steps":{
                  "PLAN_V2":{"currentUnit":"DONE","completedUnits":["SCALE"],
                    "scope":{"decision":"SINGLE_VOLUME","volumeCount":1,"chapterCount":12,
                             "chapterAvg":2600,"evidence":[]},"volumeUnits":{"1":"VALIDATE"}},
                  "STORY_CONTRACT":{"truth":{"finalTruth":"冻结合同"}},
                  "TRUTH_CONTRACT":{"truthId":"T-1","finalTruth":"冻结合同"}}}
                """;
        String reset = DeriveResetService.resetPlanState(status, "SCALE");

        assertTrue(M.readTree(reset).path("steps").path("STORY_CONTRACT").isMissingNode());
        assertTrue(M.readTree(reset).path("steps").path("TRUTH_CONTRACT").isMissingNode());
    }

    private static RunDO reworkRun(String target) throws Exception {
        RunDO run = new RunDO();
        run.setId(58L);
        run.setStatus("REWORK");
        run.setStepStatus(M.writeValueAsString(Map.of("v", 1, "steps", Map.of(
                "PLAN_V2", Map.of(
                        "currentUnit", "DONE",
                        "completedUnits", List.of("SCALE", "CONTRACT", "SPINE"),
                        "scope", Map.of("decision", "SINGLE_VOLUME", "volumeCount", 1,
                                "chapterCount", 12, "chapterAvg", 2600, "evidence", List.of()),
                        "volumeUnits", Map.of("1", "VALIDATE")),
                "GATE", Map.of("status", "REWORK", "round", 1, "directive", "指令",
                        "repairTarget", target, "issues", List.of(), "criteria", Map.of())))));
        return run;
    }

    @Test
    void replanSpineKeepsContractRow() throws Exception {
        when(runMapper.selectById(58L)).thenReturn(reworkRun("SPINE"));

        service.replanFrom(58L, "SPINE");

        verify(storyContractMapper, never()).delete(any());
        verify(runMapper).updateStepStatus(eq(58L), anyString());
    }

    @Test
    void replanScaleDeletesContractRow() throws Exception {
        when(runMapper.selectById(58L)).thenReturn(reworkRun("SCALE"));

        service.replanFrom(58L, "SCALE");

        verify(storyContractMapper).delete(any());
        verify(runMapper).updateStepStatus(eq(58L), anyString());
    }

    @Test
    void clearPlanningDeletesContractRow() throws Exception {
        RunDO run = reworkRun("SPINE");
        run.setStatus("PLANNING");
        when(runMapper.selectById(58L)).thenReturn(run);
        com.fasterxml.jackson.databind.ObjectMapper real = new com.fasterxml.jackson.databind.ObjectMapper();
        when(objectMapper.readTree(anyString()))
                .thenAnswer(invocation -> real.readTree(invocation.getArgument(0, String.class)));

        service.clearPlanning(58L);

        verify(storyContractMapper).delete(any());
    }

    @Test
    void clearPlanningDeletesScheduleRows() throws Exception {
        RunDO run = reworkRun("SPINE");
        run.setStatus("PLANNING");
        when(runMapper.selectById(58L)).thenReturn(run);
        com.fasterxml.jackson.databind.ObjectMapper real = new com.fasterxml.jackson.databind.ObjectMapper();
        when(objectMapper.readTree(anyString()))
                .thenAnswer(invocation -> real.readTree(invocation.getArgument(0, String.class)));

        service.clearPlanning(58L);

        verify(scheduleService).deleteByRun(58L);
    }

    @Test
    void scaleReplanDeletesScheduleRows() throws Exception {
        when(runMapper.selectById(58L)).thenReturn(reworkRun("SCALE"));

        service.replanFrom(58L, "SCALE");

        verify(scheduleService).deleteByRun(58L);
    }

    @Test
    void spineReplanKeepsScheduleRows() throws Exception {
        when(runMapper.selectById(58L)).thenReturn(reworkRun("SPINE"));

        service.replanFrom(58L, "SPINE");

        verify(scheduleService, never()).deleteByRun(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void scaleResetDropsScheduleSnapshot() throws Exception {
        String status = """
                {"v":1,"steps":{
                  "PLAN_V2":{"currentUnit":"DONE","completedUnits":["SCALE"],
                    "scope":{"decision":"SINGLE_VOLUME","volumeCount":1,"chapterCount":12,
                             "chapterAvg":2600,"evidence":[]},"volumeUnits":{"1":"VALIDATE"}},
                  "CHAPTER_SCHEDULE":{"contractVersion":1,"entries":[]}}}
                """;

        String reset = DeriveResetService.resetPlanState(status, "SCALE");

        assertTrue(M.readTree(reset).path("steps").path("CHAPTER_SCHEDULE").isMissingNode());
    }

    @Test
    void spineResetKeepsScheduleSnapshot() throws Exception {
        String status = """
                {"v":1,"steps":{
                  "PLAN_V2":{"currentUnit":"DONE","completedUnits":["SCALE","CONTRACT"],
                    "scope":{"decision":"SINGLE_VOLUME","volumeCount":1,"chapterCount":12,
                             "chapterAvg":2600,"evidence":[]},"volumeUnits":{"1":"VALIDATE"}},
                  "CHAPTER_SCHEDULE":{"contractVersion":1,"entries":[]}}}
                """;

        String reset = DeriveResetService.resetPlanState(status, "SPINE");

        assertTrue(M.readTree(reset).path("steps").path("CHAPTER_SCHEDULE").isObject(),
                "SPINE 回炉保留日程（合同没变，日程是它的投影）");
    }

    // ---- DRAFT 局部回炉（计划 §7.7） ----

    private static RunDO reworkWithGate(String issuesJson, String directive) throws Exception {
        RunDO run = new RunDO();
        run.setId(58L);
        run.setNovelId(20L);
        run.setStatus("REWORK");
        Map<String, Object> gate = Map.of("status", "REWORK", "round", 2,
                "directive", directive,
                "repairTarget", "DRAFT", "issues", issuesJson,
                "criteria", Map.of());
        Map<String, Object> draft = Map.of("changes", List.of(
                Map.of("no", 4, "who", "林默", "what", "得知"),
                Map.of("no", 8, "who", "苏晚", "what", "离场")));
        run.setStepStatus(M.writeValueAsString(Map.of("v", 1, "steps",
                Map.of("GATE", gate, "DRAFT", draft))));
        return run;
    }

    private static DeriveDraftService draftService(RunMapper runMapper, ChapterMapper chapterMapper,
                                                   NovelMapper novelMapper,
                                                   BeatInstanceMapper beatInstanceMapper) {
        return new DeriveDraftService(runMapper, novelMapper, chapterMapper, beatInstanceMapper,
                null, null, null, null, null, null);
    }

    private static List<com.zzdzz.novelgen.model.entity.derive.ChapterDO> chapterRows() {
        List<com.zzdzz.novelgen.model.entity.derive.ChapterDO> out = new java.util.ArrayList<>();
        for (int no = 1; no <= 12; no++) {
            com.zzdzz.novelgen.model.entity.derive.ChapterDO row =
                    new com.zzdzz.novelgen.model.entity.derive.ChapterDO();
            row.setId((long) no);
            row.setNovelId(20L);
            row.setChapterNo(no);
            row.setDraftOutline("旧章纲" + no);
            out.add(row);
        }
        return out;
    }

    private static List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> beatRows() {
        List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> out = new java.util.ArrayList<>();
        for (int no = 1; no <= 12; no++) {
            com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO row =
                    new com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO();
            row.setId((long) no);
            row.setRunId(58L);
            row.setSeq(no);
            row.setChapterNo(no);
            row.setFilledText("旧拍" + no);
            row.setStatus("PLANNED");
            out.add(row);
        }
        return out;
    }

    @Test
    void draftRepairOnlyClearsAffectedChapters() throws Exception {
        RunDO run = reworkWithGate("[\"第 8 章 章纲与合同不一致\"]", "重写第 8 章的句子");
        when(chapterMapper.selectList(any())).thenReturn(chapterRows());
        when(beatInstanceMapper.selectList(any())).thenReturn(beatRows());

        DeriveDraftService service = draftService(runMapper, chapterMapper, novelMapper,
                beatInstanceMapper);
        DeriveDraftService.ResetScope scope = service.resetForRepair(run);

        assertEquals(List.of(5, 6, 7, 8), scope.affectedChapters(), "只清 P6 点名章所在窗");
        org.mockito.ArgumentCaptor<List<Integer>> chapters =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chapterMapper).clearDraftOutlinesByChapters(eq(20L), chapters.capture());
        assertEquals(List.of(5, 6, 7, 8), chapters.getValue());
        verify(beatInstanceMapper).resetFilledByChapters(eq(58L), eq(List.of(5, 6, 7, 8)));
        verify(chapterMapper, never()).clearDraftOutlines(any(Long.class));
        verify(chapterMapper, never()).clearForRewrite(any(Long.class));
    }

    @Test
    void draftRepairKeepsCastAndBrief() throws Exception {
        RunDO run = reworkWithGate("[\"第 8 章 章纲与合同不一致\"]", "重写第 8 章的句子");
        when(chapterMapper.selectList(any())).thenReturn(chapterRows());
        when(beatInstanceMapper.selectList(any())).thenReturn(beatRows());

        draftService(runMapper, chapterMapper, novelMapper, beatInstanceMapper)
                .resetForRepair(run);

        verify(novelMapper, never()).clearDraftAssets(org.mockito.ArgumentMatchers.anyLong());
        verify(novelMapper, never()).clearDraftBriefTruth(org.mockito.ArgumentMatchers.anyLong());
        verify(runMapper).updateStatus(eq(58L), eq("PLANNING"), org.mockito.ArgumentMatchers.isNull());
        org.mockito.ArgumentCaptor<String> status =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runMapper).updateStepStatus(eq(58L), status.capture());
        assertTrue(status.getValue().contains("\"round\":2"), "GATE 轮次必须保留");
        assertTrue(status.getValue().contains("重写第 8 章的句子"), "GATE 指令必须保留");
        assertTrue(status.getValue().contains("\"no\":4"), "未受影响章的状态快照保留");
        assertTrue(!status.getValue().contains("\"no\":8"), "受影响章的旧状态必须清掉");
    }

    @Test
    void draftRepairFallsBackToAllWhenNoChapter() throws Exception {
        RunDO run = reworkWithGate("[\"内容重复但没写章号\"]", "按意见整体重写");
        when(chapterMapper.selectList(any())).thenReturn(chapterRows());
        when(beatInstanceMapper.selectList(any())).thenReturn(beatRows());

        DeriveDraftService.ResetScope scope = draftService(runMapper, chapterMapper, novelMapper,
                beatInstanceMapper).resetForRepair(run);

        assertEquals(12, scope.affectedChapters().size(), "抽不到章号时回退全章");
    }

    private static List<com.zzdzz.novelgen.model.entity.derive.ChapterDO> chapters15() {
        List<com.zzdzz.novelgen.model.entity.derive.ChapterDO> out = new java.util.ArrayList<>();
        for (int no = 1; no <= 15; no++) {
            var row = new com.zzdzz.novelgen.model.entity.derive.ChapterDO();
            row.setId((long) no);
            row.setNovelId(20L);
            row.setChapterNo(no);
            row.setDraftOutline("旧章纲" + no);
            out.add(row);
        }
        return out;
    }

    private static List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> beats15() {
        List<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO> out = new java.util.ArrayList<>();
        for (int no = 1; no <= 15; no++) {
            var row = new com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO();
            row.setId((long) no);
            row.setRunId(58L);
            row.setSeq(no);
            row.setChapterNo(no);
            row.setFilledText("旧拍" + no);
            row.setStatus("PLANNED");
            out.add(row);
        }
        return out;
    }

    @Test
    void draftRepairExpandsOnlyAffectedWindowsAndKeepsTail() throws Exception {
        RunDO run = reworkWithGate(
                "[\"第1章问题\",\"第4章问题\",\"第7章问题\",\"第10章问题\","
                        + "\"第11章问题\",\"第12章问题\"]",
                "重写第1章 第4章 第7章 第10章 第11章 第12章");
        when(chapterMapper.selectList(any())).thenReturn(chapters15());
        when(beatInstanceMapper.selectList(any())).thenReturn(beats15());

        DeriveDraftService.ResetScope scope = draftService(
                runMapper, chapterMapper, novelMapper, beatInstanceMapper).resetForRepair(run);

        assertEquals(12, scope.affectedChapters().size(),
                "8 个问题章跨 4 窗时只清 4 窗，不用“超过半本”回退全书");
        assertFalse(scope.affectedChapters().contains(13));
        assertFalse(scope.affectedChapters().contains(15));
    }

    @Test
    void noHalfBookFallbackWhenEveryExtractedChapterIsReal() throws Exception {
        RunDO run = reworkWithGate(
                "[\"第1章问题\",\"第2章问题\",\"第3章问题\",\"第4章问题\","
                        + "\"第5章问题\",\"第6章问题\",\"第7章问题\",\"第8章问题\"]",
                "重写第1章 第2章 第3章 第4章 第5章 第6章 第7章 第8章");
        when(chapterMapper.selectList(any())).thenReturn(chapters15());
        when(beatInstanceMapper.selectList(any())).thenReturn(beats15());

        DeriveDraftService.ResetScope scope = draftService(
                runMapper, chapterMapper, novelMapper, beatInstanceMapper).resetForRepair(run);

        assertEquals(8, scope.affectedChapters().size(),
                "抽到 8 章时按窗展开到 1-8，不再用“超过半本”清 15 章");
        assertFalse(scope.affectedChapters().contains(9));
    }
}
