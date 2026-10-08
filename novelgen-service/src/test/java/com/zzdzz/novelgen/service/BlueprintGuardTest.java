package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ChapterStepDataService;
import com.zzdzz.novelgen.service.data.EmbeddingDataService;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import com.zzdzz.novelgen.service.data.GenerationTaskDataService;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.PromptTemplateDataService;
import com.zzdzz.novelgen.service.data.SampleCardDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 守卫补测（重构规划 §6 缺口补齐）：FORBID_VOLUME_PLAN 入队口 + adopt 兜底、activate 骨架门禁 × 预设。
 * 验收两点：①受保护蓝图 + 预置章纲 → 拒；②ORIGINAL / 无预置章纲 → **不误杀**（自愈与规划功能照常）。
 * ChapterPipelineService 内两处 replan 禁令走同一 blueprintProtected 单读口，流程级覆盖随 M2 补。
 */
class BlueprintGuardTest {

    private static final String PROTECTED_JSON = "{\"mode\":\"RESKIN\",\"sourceSampleId\":1}";
    private static final String ORIGINAL_JSON = "{\"mode\":\"ORIGINAL\"}";
    private static final String SKELETON = "> 由样本《离婚后》\n正文";

    private static ChapterDO chapter(int no, String outlineYaml) {
        ChapterDO c = mock(ChapterDO.class);
        when(c.getChapterNo()).thenReturn(no);
        when(c.getOutlineYaml()).thenReturn(outlineYaml);
        when(c.getFullText()).thenReturn(null);
        return c;
    }

    // ===== 守卫③：卷规划入队口 assertBlueprintNotOverwritten =====

    private GenerationQueueService queue(GenerationTaskDataService taskDAO, NovelDataService novelData,
                                         ChapterDataService chapterData) {
        return new GenerationQueueService(taskDAO, novelData, chapterData, mock(ChapterPipelineService.class),
                mock(ChapterStepDataService.class), mock(StageLog.class), mock(PlanningService.class),
                mock(TuningService.class), mock(LlmCallLogDataService.class), mock(VolumeReviewService.class),
                mock(ReskinService.class), mock(SpliceService.class));
    }

    @Test
    void submitPlanRejectsProtectedBookWithPresetOutline() {
        NovelDataService novelData = mock(NovelDataService.class);
        ChapterDataService chapterData = mock(ChapterDataService.class);
        GenerationTaskDataService taskDAO = mock(GenerationTaskDataService.class);
        ChapterDO preset = chapter(6, "goal: 蓝图");
        when(novelData.findDeriveConfig(1L)).thenReturn(PROTECTED_JSON);
        when(chapterData.listSummariesByNovel(1L)).thenReturn(List.of(preset));

        GenerationQueueService service = queue(taskDAO, novelData, chapterData);
        assertThatThrownBy(() -> service.submitPlan(1L, "书", 2, 6, 7, null, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("受保护蓝图");
        verify(taskDAO, never()).insert(anyLong(), anyInt(), anyInt(), any(), anyString(), any(), anyInt());
    }

    @Test
    void submitPlanAllowsProtectedBookWithoutPresetOutline() {
        NovelDataService novelData = mock(NovelDataService.class);
        ChapterDataService chapterData = mock(ChapterDataService.class);
        GenerationTaskDataService taskDAO = mock(GenerationTaskDataService.class);
        ChapterDO withoutOutline = chapter(6, null);
        when(novelData.findDeriveConfig(1L)).thenReturn(PROTECTED_JSON);
        when(chapterData.listSummariesByNovel(1L)).thenReturn(List.of(withoutOutline));
        when(taskDAO.insert(anyLong(), anyInt(), anyInt(), any(), anyString(), any(), anyInt())).thenReturn(7L);

        assertThat(queue(taskDAO, novelData, chapterData).submitPlan(1L, "书", 2, 6, 7, null, 1L)).isEqualTo(7L);
    }

    /** 不误杀：ORIGINAL 书带章纲照常规划（旧逻辑 migrate()=false 放行，新逻辑同）。 */
    @Test
    void submitPlanAllowsOriginalBookWithOutline() {
        NovelDataService novelData = mock(NovelDataService.class);
        ChapterDataService chapterData = mock(ChapterDataService.class);
        GenerationTaskDataService taskDAO = mock(GenerationTaskDataService.class);
        ChapterDO aiOutline = chapter(6, "goal: AI 章纲");
        when(novelData.findDeriveConfig(1L)).thenReturn(ORIGINAL_JSON);
        when(chapterData.listSummariesByNovel(1L)).thenReturn(List.of(aiOutline));
        when(taskDAO.insert(anyLong(), anyInt(), anyInt(), any(), anyString(), any(), anyInt())).thenReturn(8L);

        assertThat(queue(taskDAO, novelData, chapterData).submitPlan(1L, "书", 2, 6, 7, null, 1L)).isEqualTo(8L);
    }

    // ===== 守卫④：adopt 兜底（同条件第二道） =====

    private VolumePlanService volumePlan(NovelDataService novelData, ChapterDataService chapterData) {
        VolumePlanService service = new VolumePlanService(
                mock(LlmPort.class), mock(LlmJson.class), mock(ContextPackerService.class),
                chapterData, mock(ForeshadowDataService.class), novelData, mock(CanonDocDataService.class),
                mock(SamplePlotNodeDataService.class), mock(StylePackDataService.class), mock(StageLog.class),
                mock(TuningService.class), new ObjectMapper(),
                new PromptTemplateService(mock(PromptTemplateDataService.class)),
                mock(PlatformTransactionManager.class));
        service.initTx();
        return service;
    }

    private static VolumePlanService.PlanDraft singleRowDraft(int chapterNo) {
        return new VolumePlanService.PlanDraft("arc", "brief", List.of(
                new VolumePlanService.PlanRow(chapterNo, "题", "目标", "钩", null, List.of(), 1500, 2500)));
    }

    @Test
    void adoptDraftRejectsProtectedBookWithPresetOutline() {
        NovelDataService novelData = mock(NovelDataService.class);
        ChapterDataService chapterData = mock(ChapterDataService.class);
        ChapterDO preset = chapter(6, "goal: 蓝图");
        when(novelData.findDeriveConfig(1L)).thenReturn(PROTECTED_JSON);
        when(chapterData.listSummariesByNovel(1L)).thenReturn(List.of(preset));

        assertThatThrownBy(() -> volumePlan(novelData, chapterData).adoptDraft(1L, 2, singleRowDraft(6)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("受保护蓝图");
        verify(chapterData, never()).deletePlan(anyLong());
    }

    // ===== 激活门禁：结构判据（骨架原文）× 预设 =====

    private NovelService novelService(NovelDataService novelData, CanonDocDataService canonData) {
        PromptTemplateDataService promptDao = mock(PromptTemplateDataService.class);
        when(promptDao.findAll()).thenReturn(List.of());
        return new NovelService(novelData, mock(StylePackDataService.class),
                mock(ChapterDataService.class), mock(DigestService.class),
                mock(SampleCardDataService.class), mock(SamplePlotNodeDataService.class),
                mock(com.zzdzz.novelgen.service.data.ImportedSampleDataService.class),
                mock(com.zzdzz.novelgen.service.data.MaterialCardDataService.class), canonData,
                mock(GenerationTaskDataService.class), mock(EmbeddingDataService.class),
                mock(LlmPort.class), mock(LlmJson.class),
                new PromptTemplateService(promptDao), new ObjectMapper());
    }

    @Test
    void activateRejectsOriginalBookWithSkeletonOutline() {
        NovelDataService novelData = mock(NovelDataService.class);
        CanonDocDataService canonData = mock(CanonDocDataService.class);
        when(novelData.getById(1L)).thenReturn(mock(NovelDO.class));
        when(novelData.findDeriveConfig(1L)).thenReturn(ORIGINAL_JSON);
        when(canonData.findContentByKindName(1L, "misc", "大纲")).thenReturn(SKELETON);

        assertThatThrownBy(() -> novelService(novelData, canonData).activate(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("样本剧情骨架");
        verify(novelData, never()).activate(anyLong());
    }

    @Test
    void activateAllowsProtectedPresetWithSkeletonOutline() {
        NovelDataService novelData = mock(NovelDataService.class);
        CanonDocDataService canonData = mock(CanonDocDataService.class);
        when(novelData.getById(1L)).thenReturn(mock(NovelDO.class));
        when(novelData.findDeriveConfig(1L)).thenReturn(PROTECTED_JSON);
        when(canonData.findContentByKindName(1L, "misc", "大纲")).thenReturn(SKELETON);
        when(novelData.activate(1L)).thenReturn(1);

        novelService(novelData, canonData).activate(1L);
        verify(novelData).activate(eq(1L));
    }
}
