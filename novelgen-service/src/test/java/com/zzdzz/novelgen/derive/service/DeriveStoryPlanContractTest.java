package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CONTRACT/SCHEDULE 单元插入 PLAN_V2 状态机（schedule 计划 §6）：
 * SCALE/确认规模之后先冻结合同、再机械投影章级日程，SPINE 缺合同必须 fail-fast，
 * 旧 run（SPINE 之后）缺日程必须补投影。
 */
@ExtendWith(MockitoExtension.class)
class DeriveStoryPlanContractTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Mock
    private RunMapper runMapper;
    @Mock
    private NovelMapper novelMapper;
    @Mock
    private AssetBeatMapper assetBeatMapper;
    @Mock
    private CharacterPlanMapper characterPlanMapper;
    @Mock
    private StoryThreadMapper storyThreadMapper;
    @Mock
    private VolumePlanMapper volumePlanMapper;
    @Mock
    private LlmJson llmJson;
    @Mock
    private DeriveScaffoldService scaffoldService;
    @Mock
    private DeriveCharacterBusinessModelService businessModelService;
    @Mock
    private DeriveContractService contractService;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private DeriveChapterScheduleService scheduleService;

    private DeriveStoryPlanService service() {
        DeriveStoryPlanService svc = new DeriveStoryPlanService(runMapper, novelMapper,
                assetBeatMapper, characterPlanMapper, storyThreadMapper, volumePlanMapper, llmJson,
                scaffoldService, businessModelService, contractService);
        setField(svc, "chapterMapper", chapterMapper);
        setField(svc, "scheduleService", scheduleService);
        return svc;
    }

    private static void setField(DeriveStoryPlanService svc, String name, Object value) {
        try {
            java.lang.reflect.Field field = DeriveStoryPlanService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(svc, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlanContracts.ScopeContract scope() {
        return new PlanContracts.ScopeContract(PlanContracts.ScopeDecision.SINGLE_VOLUME,
                1, 12, 2600, List.of("单案短篇"));
    }

    private static StoryContract contract() {
        return new StoryContract(58L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                new StoryContract.Truth("C3", "陆敬", "旧账", "利用换班间隙",
                        "陆敬为掩盖旧账制造失踪", "揭穿并承担代价", 11),
                List.of(new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 3, 10, "C1"),
                        new StoryContract.Clue("GM-CLUE-02", "值班表涂改", 5, 11, "C1"),
                        new StoryContract.Clue("GM-CLUE-03", "旧债欠条", 7, 11, "C2")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-02", "CLUE", 1, 1, "C1"),
                        new StoryContract.ThreadRef("GM-CLUE-03", "CLUE", 1, 1, "C2")),
                List.of("不得出现第二起案件"),
                List.of(new StoryContract.VolumeTruth(1, "陆敬为掩盖旧账制造失踪", List.of())),
                scope());
    }

    private static RunDO planningRun(String currentUnit, PlanContracts.ScopeContract scope,
                                     Long novelId) throws Exception {
        RunDO run = new RunDO();
        run.setId(58L);
        run.setStatus("PLANNING");
        run.setNovelId(novelId);
        run.setParamTags("[\"悬疑\"]");
        PlanContracts.PlanV2State state = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.valueOf(currentUnit), List.of(PlanContracts.PlanUnit.SCALE),
                scope, java.util.Map.of());
        run.setStepStatus(M.writeValueAsString(
                java.util.Map.of("v", 1, "steps", java.util.Map.of("PLAN_V2", state))));
        return run;
    }

    private static JsonNode planV2(ArgumentCaptor<String> status) throws Exception {
        return M.readTree(status.getAllValues().get(status.getAllValues().size() - 1))
                .path("steps").path("PLAN_V2");
    }

    @Test
    void scaleTransitionsToContractBeforeSpine() throws Exception {
        RunDO run = planningRun("NEED_SCOPE", scope(), null);
        when(runMapper.selectById(58L)).thenReturn(run);
        when(novelMapper.insertFull(any())).thenReturn(12L);

        PlanVO vo = service().confirmScope(58L, 1, false);

        assertEquals("CONTRACT", vo.unit());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(runMapper).updateStepStatus(eq(58L), status.capture());
        assertTrue(status.getValue().contains("CONTRACT"), status.getValue());
        assertTrue(status.getValue().contains("SCALE"), status.getValue());
    }

    @Test
    void contractTransitionsToScheduleBeforeSpine() throws Exception {
        RunDO run = planningRun("CONTRACT", scope(), 12L);
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.generateIfAbsent(any(), any(), any())).thenReturn(contract());
        when(contractService.require(any())).thenReturn(contract());

        service().advance(58L);

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(runMapper, atLeastOnce()).updateStepStatus(eq(58L), status.capture());
        JsonNode plan = planV2(status);
        assertEquals("SCHEDULE", plan.path("currentUnit").asText());
        assertTrue(plan.path("completedUnits").toString().contains("CONTRACT"));
        assertEquals(1, contract().version(), "合同只冻结一行，版本不增长");
    }

    @Test
    void scheduleUnitPersistsRowsAndTransitionsToSpine() throws Exception {
        RunDO run = planningRun("SCHEDULE", scope(), 12L);
        List<ChapterScheduleEntry> entries = List.of(
                new ChapterScheduleEntry(1, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(11, 1, true, List.of(),
                        List.of("GM-CLUE-02", "GM-CLUE-03")));
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.require(any())).thenReturn(contract());
        when(scheduleService.project(run)).thenReturn(entries);
        when(scheduleService.persist(run, entries)).thenReturn(entries);

        PlanVO vo = service().advance(58L);

        assertEquals("SCHEDULE", vo.unit());
        verify(scheduleService, times(1)).persist(run, entries);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(runMapper, atLeastOnce()).updateStepStatus(eq(58L), status.capture());
        JsonNode plan = planV2(status);
        assertEquals("SPINE", plan.path("currentUnit").asText());
        assertTrue(plan.path("completedUnits").toString().contains("SCHEDULE"),
                plan.path("completedUnits").toString());
    }

    @Test
    void legacyRunGetsScheduleBackfilled() throws Exception {
        RunDO run = planningRun("CHARACTERS", scope(), 12L);
        when(contractService.load(any())).thenReturn(contract());
        when(scheduleService.load(run)).thenReturn(List.of());
        when(scheduleService.project(run)).thenReturn(List.of(
                new ChapterScheduleEntry(1, 1, false, List.of(), List.of())));

        PlanContracts.PlanV2State state = service().ensureScheduleForLegacy(run,
                new PlanContracts.PlanV2State(PlanContracts.PlanUnit.CHARACTERS,
                        List.of(PlanContracts.PlanUnit.SCALE, PlanContracts.PlanUnit.CONTRACT),
                        scope(), java.util.Map.of()));

        assertEquals(PlanContracts.PlanUnit.CHARACTERS, state.currentUnit(), "补投影不改当前单元");
        verify(scheduleService).persist(eq(run), any());
    }

    @Test
    void legacyRunWithStaleScheduleIsReprojected() throws Exception {
        RunDO run = planningRun("DONE", scope(), 12L);
        when(contractService.load(any())).thenReturn(contract());
        when(scheduleService.load(run)).thenReturn(List.of(
                new ChapterScheduleEntry(1, 1, false, List.of(), List.of())));
        org.mockito.Mockito.doThrow(new BizException(
                        com.zzdzz.novelgen.common.web.ErrorCode.PARAM_ERROR,
                        "SCHEDULE_STALE：日程合同版本 0 与合同 1 不一致，必须重新投影"))
                .when(scheduleService).assertCurrent(run);
        when(scheduleService.project(run)).thenReturn(List.of(
                new ChapterScheduleEntry(1, 1, false, List.of(), List.of())));

        service().ensureScheduleForLegacy(run, new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.DONE, List.of(PlanContracts.PlanUnit.SCALE), scope(),
                java.util.Map.of()));

        verify(scheduleService).persist(eq(run), any());
    }

    @Test
    void contractUnitCreatesExactlyOneFrozenContract() throws Exception {
        RunDO run = planningRun("CONTRACT", scope(), 12L);
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.generateIfAbsent(any(), any(), any())).thenReturn(contract());
        when(contractService.require(any())).thenReturn(contract());

        PlanVO vo = service().advance(58L);

        assertEquals("CONTRACT", vo.unit());
        verify(contractService).generateIfAbsent(eq(run), eq(scope()), any());
    }

    @Test
    void spineCannotRunWithoutFrozenContract() throws Exception {
        RunDO run = planningRun("SPINE", null, 12L);
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.load(any())).thenReturn(null);
        when(contractService.require(any())).thenThrow(new BizException(
                com.zzdzz.novelgen.common.web.ErrorCode.PARAM_ERROR,
                "CONTRACT_REQUIRED：唯一故事合同尚未冻结（先推进 CONTRACT 单元）"));

        BizException error = assertThrows(BizException.class, () -> service().advance(58L));

        assertTrue(error.getMessage().contains("CONTRACT_REQUIRED"), error.getMessage());
    }

    @Test
    void spineRebuildsDeterministicallyFromFrozenContract() throws Exception {
        RunDO run = planningRun("SPINE", scope(), 12L);
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.load(any())).thenReturn(contract());
        when(contractService.require(any())).thenReturn(contract());

        PlanVO vo = service().advance(58L);

        assertEquals("SPINE", vo.unit());
        verify(contractService, never()).generateIfAbsent(any(), any(), any());
        verify(llmJson, never()).ask(any(), any(), anyInt());
        verify(storyThreadMapper, atLeastOnce()).insertFull(any());
    }
}
