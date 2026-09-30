package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.enums.ImportAnalyzeStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入解析链的勾选与顺序语义（纯函数，不连库）：
 * ①默认全跑 = 不传勾选就按枚举全序；②未知键忽略；③勾选顺序不影响执行顺序（依赖顺序由枚举定）；
 * ④空勾选 = 不解析（API 显式 opt-in，不让纯接口建书默默烧一轮 LLM）。
 */
class ImportAnalyzeStepTest {

    @Test
    void allStepsAreOrderedByDependency() {
        assertThat(ImportAnalyzeStep.all().stream().map(ImportAnalyzeStep::wire).toList()).containsExactly(
                "DIGESTS", "OUTLINE", "CARDS", "WORLD", "RULES", "EMBEDDINGS", "VOLUME_PLAN", "CHAPTER_OUTLINES");
    }

    @Test
    void userSelectionIsReorderedToExecutionOrder() {
        List<ImportAnalyzeStep> picked = ImportAnalyzeStep.ordered(List.of("CHAPTER_OUTLINES", "DIGESTS", "WORLD"));
        assertThat(picked.stream().map(ImportAnalyzeStep::wire).toList())
                .containsExactly("DIGESTS", "WORLD", "CHAPTER_OUTLINES");
    }

    @Test
    void unknownAndBlankKeysAreIgnoredAndLowerCaseAccepted() {
        assertThat(ImportAnalyzeStep.ordered(List.of("nope", "  ", "digests"))).containsExactly(ImportAnalyzeStep.DIGESTS);
        assertThat(ImportAnalyzeStep.of("outline")).isEqualTo(ImportAnalyzeStep.OUTLINE);
        assertThat(ImportAnalyzeStep.of("没这项")).isNull();
    }

    @Test
    void emptySelectionMeansNoAnalyze() {
        assertThat(ImportAnalyzeStep.ordered(List.of())).isEmpty();
        assertThat(ImportAnalyzeStep.ordered(null)).isEmpty();
    }

    @Test
    void everyStepHasChineseLabelForUi() {
        for (ImportAnalyzeStep s : ImportAnalyzeStep.all()) {
            assertThat(s.label()).isNotBlank();
        }
    }

    @Test
    void summarizeCountsSuccessSkipAndFail() {
        ImportAnalyzeService service = new ImportAnalyzeService(null, null, null, null, null, null, null, null,
                null, new ObjectMapper());
        List<java.util.Map<String, Object>> results = List.of(
                java.util.Map.of("step", "DIGESTS", "status", "SUCCESS"),
                java.util.Map.of("step", "WORLD", "status", "SKIPPED"),
                java.util.Map.of("step", "RULES", "status", "FAILED"));
        assertThat(service.summarize(results, 1)).contains("成功 1 步").contains("跳过 1 步").contains("失败 1 步");
        assertThat(service.summarize(results.subList(0, 1), 0)).isEqualTo("解析链结束：成功 1 步");
    }
}
