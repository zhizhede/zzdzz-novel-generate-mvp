package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.entity.ImportAnalyzeTaskDO;
import com.zzdzz.novelgen.model.enums.ImportAnalyzeStep;
import com.zzdzz.novelgen.model.vo.ImportAnalyzeStatusVO;
import com.zzdzz.novelgen.service.data.ImportAnalyzeTaskDataService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 解析链「跳过已有 / 覆盖重做」的口径基线（2026-09-30 用户定调：每个已有内容的部分都给开关，**默认不跳过**）：
 * ①只有本次勾选的步才谈得上跳过；②任务行 steps 新格式（对象）与旧格式（裸字符串）都要读得出来。
 */
class ImportAnalyzeServiceTest {

    // ===== 跳过集合：只对勾选的步生效 =====

    @Test
    void skipOnlyAppliesToSelectedSteps() {
        Set<ImportAnalyzeStep> skip = ImportAnalyzeService.effectiveSkip(
                ImportAnalyzeStep.ordered(List.of("DIGESTS", "OUTLINE")),
                List.of("CARDS", "digests", "不存在的键"));

        // CARDS 没勾选、未知键无效；大小写不敏感地命中 DIGESTS
        assertThat(skip).containsExactly(ImportAnalyzeStep.DIGESTS);
    }

    @Test
    void nothingSkippedByDefault() {
        Set<ImportAnalyzeStep> skip = ImportAnalyzeService.effectiveSkip(
                ImportAnalyzeStep.ordered(ImportAnalyzeStep.all().stream().map(ImportAnalyzeStep::wire).toList()),
                null);

        assertThat(skip).isEmpty();   // 默认不跳过＝覆盖重做
    }

    // ===== 任务行 steps 的读写兼容 =====

    private ImportAnalyzeService service(ImportAnalyzeTaskDataService taskData) {
        return new ImportAnalyzeService(null, null, taskData, null, null, null, null, null, new ObjectMapper());
    }

    private ImportAnalyzeTaskDO task(String stepsJson) {
        ImportAnalyzeTaskDO t = new ImportAnalyzeTaskDO();
        t.setId(9L);
        t.setNovelId(34L);
        t.setStatus("DONE");
        t.setSteps(stepsJson);
        t.setDoneSteps("[]");
        t.setMessage("解析链结束");
        return t;
    }

    @Test
    void statusReadsLegacyPlainKeyArray() {
        ImportAnalyzeTaskDataService taskData = mock(ImportAnalyzeTaskDataService.class);
        when(taskData.findAliveByNovel(34L)).thenReturn(task("[\"DIGESTS\",\"OUTLINE\"]"));

        ImportAnalyzeStatusVO s = service(taskData).status(34L);

        assertThat(s.plannedSteps()).containsExactly("DIGESTS", "OUTLINE");
        assertThat(s.skipExistingSteps()).isEmpty();   // 旧格式没有开关字段：按默认（覆盖）解释
    }

    @Test
    void statusReadsStepObjectsWithSkipFlags() {
        ImportAnalyzeTaskDataService taskData = mock(ImportAnalyzeTaskDataService.class);
        when(taskData.findAliveByNovel(34L)).thenReturn(task(
                "[{\"key\":\"DIGESTS\",\"skipExisting\":true},{\"key\":\"CARDS\",\"skipExisting\":false},"
                        + "{\"key\":\"OUTLINE\",\"skipExisting\":false}]"));

        ImportAnalyzeStatusVO s = service(taskData).status(34L);

        assertThat(s.plannedSteps()).containsExactly("DIGESTS", "CARDS", "OUTLINE");
        assertThat(s.skipExistingSteps()).containsExactly("DIGESTS");
    }

    @Test
    void statusIgnoresUnknownKeysAndBrokenJson() {
        ImportAnalyzeTaskDataService taskData = mock(ImportAnalyzeTaskDataService.class);
        when(taskData.findAliveByNovel(34L)).thenReturn(task("[{\"key\":\"NOPE\"},{\"key\":\"CARDS\"}]"));
        assertThat(service(taskData).status(34L).plannedSteps()).containsExactly("CARDS");

        when(taskData.findAliveByNovel(34L)).thenReturn(task("{不是数组"));
        assertThat(service(taskData).status(34L).plannedSteps()).isEmpty();
    }

    @Test
    void noTaskMeansNullStatus() {
        ImportAnalyzeTaskDataService taskData = mock(ImportAnalyzeTaskDataService.class);
        when(taskData.findAliveByNovel(34L)).thenReturn(null);
        assertThat(service(taskData).status(34L)).isNull();
    }

    /**
     * 解析链只解析不规划：历史任务行里遗留的规划步键（VOLUME_PLAN / CHAPTER_OUTLINES，两步已于 2026-10-03 移出本链）
     * 在进度读回时被忽略，不会当成一步解析结果渲染出来。
     */
    @Test
    void statusDropsLegacyPlanningStepKeys() {
        ImportAnalyzeTaskDataService taskData = mock(ImportAnalyzeTaskDataService.class);
        when(taskData.findAliveByNovel(34L)).thenReturn(task(
                "[{\"key\":\"DIGESTS\",\"skipExisting\":false},{\"key\":\"VOLUME_PLAN\",\"skipExisting\":false},"
                        + "{\"key\":\"CHAPTER_OUTLINES\",\"skipExisting\":false},{\"key\":\"EMBEDDINGS\",\"skipExisting\":false}]"));

        ImportAnalyzeStatusVO s = service(taskData).status(34L);

        assertThat(s.plannedSteps()).containsExactly("DIGESTS", "EMBEDDINGS");
    }
}
