package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.enums.ImportAnalyzeStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入解析链的勾选与顺序语义（纯函数，不连库）：
 * ①解析链只解析不规划（枚举里没有卷纲/章纲）；②未知键忽略；③勾选顺序不影响执行顺序（依赖顺序由枚举定）；
 * ④空勾选 = 不解析（API 显式 opt-in，不让纯接口建书默默烧一轮 LLM）。
 */
class ImportAnalyzeStepTest {

    @Test
    void allStepsAreOrderedByDependency() {
        assertThat(ImportAnalyzeStep.all().stream().map(ImportAnalyzeStep::wire).toList()).containsExactly(
                "DIGESTS", "OUTLINE", "CARDS", "WORLD", "RULES", "EMBEDDINGS", "DERIVE_CHAPTER_OUTLINES");
    }

    /**
     * 解析链不含**规划**步骤：旧的卷纲（规划下一卷）与旧的章纲（把新规划卷入队）的键必须不被认识
     * ——它们属规划页。现在的章纲步是另一件事（从已有正文反推），用新键 DERIVE_CHAPTER_OUTLINES。
     */
    @Test
    void planningStepsAreNotPartOfParsingChain() {
        assertThat(ImportAnalyzeStep.of("VOLUME_PLAN")).isNull();
        assertThat(ImportAnalyzeStep.of("CHAPTER_OUTLINES")).isNull();
        assertThat(ImportAnalyzeStep.ordered(List.of("VOLUME_PLAN", "CHAPTER_OUTLINES"))).isEmpty();
        assertThat(ImportAnalyzeStep.of("DERIVE_CHAPTER_OUTLINES"))
                .isEqualTo(ImportAnalyzeStep.DERIVE_CHAPTER_OUTLINES);
    }

    @Test
    void userSelectionIsReorderedToExecutionOrder() {
        List<ImportAnalyzeStep> picked = ImportAnalyzeStep.ordered(List.of("EMBEDDINGS", "DIGESTS", "WORLD"));
        assertThat(picked.stream().map(ImportAnalyzeStep::wire).toList())
                .containsExactly("DIGESTS", "WORLD", "EMBEDDINGS");
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
                new ObjectMapper());
        List<java.util.Map<String, Object>> results = List.of(
                java.util.Map.of("step", "DIGESTS", "status", "SUCCESS"),
                java.util.Map.of("step", "WORLD", "status", "SKIPPED"),
                java.util.Map.of("step", "RULES", "status", "FAILED"));
        assertThat(service.summarize(results, 1)).contains("成功 1 步").contains("跳过 1 步").contains("失败 1 步");
        assertThat(service.summarize(results.subList(0, 1), 0)).isEqualTo("解析链结束：成功 1 步");
    }
}
