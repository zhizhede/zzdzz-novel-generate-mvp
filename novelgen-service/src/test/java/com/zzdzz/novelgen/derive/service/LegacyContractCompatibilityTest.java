package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
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
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧 run 兼容与迁移（contract-first 计划 §8）：
 * 没有合同且没正文 → 退回 CONTRACT 补冻结合同；已有正文 → 明确拒绝自动迁移；
 * 已有合同 → 原样复用，不重新生成。
 */
@ExtendWith(MockitoExtension.class)
class LegacyContractCompatibilityTest {

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

    private DeriveStoryPlanService service() {
        DeriveStoryPlanService svc = new DeriveStoryPlanService(runMapper, novelMapper,
                assetBeatMapper, characterPlanMapper, storyThreadMapper, volumePlanMapper, llmJson,
                scaffoldService, businessModelService, contractService);
        try {
            java.lang.reflect.Field field = DeriveStoryPlanService.class
                    .getDeclaredField("chapterMapper");
            field.setAccessible(true);
            field.set(svc, chapterMapper);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return svc;
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

    private static RunDO legacyRun(String unit, boolean withText) throws Exception {
        RunDO run = new RunDO();
        run.setId(58L);
        run.setStatus("PLANNING");
        run.setNovelId(12L);
        PlanContracts.PlanV2State state = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.valueOf(unit), List.of(PlanContracts.PlanUnit.SCALE),
                scope(), java.util.Map.of());
        run.setStepStatus(M.writeValueAsString(
                java.util.Map.of("v", 1, "steps", java.util.Map.of("PLAN_V2", state))));
        if (withText) {
            ChapterDO chapter = new ChapterDO();
            chapter.setChapterNo(1);
            chapter.setContent("成稿正文");
        }
        return run;
    }

    @Test
    void legacyRunWithoutContractAndWithoutTextRestartsAtContract() throws Exception {
        RunDO run = legacyRun("SPINE", false);
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.load(any())).thenReturn(null);
        when(contractService.generateIfAbsent(any(), any(), any())).thenReturn(contract());
        when(contractService.require(any())).thenReturn(contract());

        PlanVO vo = service().advance(58L);

        assertEquals("CONTRACT", vo.unit(), "无合同的旧 run 必须退回 CONTRACT 单元补冻结合同");
        verify(contractService).generateIfAbsent(any(), any(), any());
    }

    @Test
    void runWithWrittenTextIsNotAutoMigrated() throws Exception {
        RunDO run = legacyRun("SPINE", true);
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.load(any())).thenReturn(null);
        ChapterDO chapter = new ChapterDO();
        chapter.setChapterNo(1);
        chapter.setContent("成稿正文");
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter));

        BizException error = assertThrows(BizException.class, () -> service().advance(58L));

        assertTrue(error.getMessage().contains("LEGACY_TEXT_REQUIRES_MANUAL"),
                error.getMessage());
        verify(contractService, never()).generateIfAbsent(any(), any(), any());
    }

    @Test
    void existingFrozenContractIsReused() throws Exception {
        RunDO run = legacyRun("SPINE", false);
        when(runMapper.selectById(58L)).thenReturn(run);
        when(contractService.load(any())).thenReturn(contract());
        when(contractService.require(any())).thenReturn(contract());

        PlanVO vo = service().advance(58L);

        assertEquals("SPINE", vo.unit());
        verify(contractService, never()).generateIfAbsent(any(), any(), any());
        verify(llmJson, never()).ask(any(), any(), anyInt());
    }
}