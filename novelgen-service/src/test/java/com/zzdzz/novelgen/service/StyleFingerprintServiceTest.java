package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.model.dto.StyleFingerprintQueryDTO;
import com.zzdzz.novelgen.model.vo.StyleFingerprintVO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 文风指纹库筛选/排序语义基线（纯函数，不连库）：条件空值=不筛、降序 nullsLast、日期按本地时区含当日。 */
class StyleFingerprintServiceTest {

    private static StyleFingerprintVO row(String source, long refId, String name, String genre, Integer metricCount,
                                          Long totalChars, Boolean lowConfidence, String createTime, List<String> tags) {
        return new StyleFingerprintVO(source, refId, name, null, genre, null, null, totalChars, metricCount,
                null, null, null, lowConfidence, null, null, null, null, null,
                createTime == null ? null : OffsetDateTime.parse(createTime + "T00:00:00+08:00"), null,
                metricCount != null, List.of(), null, List.of(), List.of(), tags);
    }

    private static final StyleFingerprintVO SAMPLE = row("SAMPLE", 7, "源主角", "源主角", 8, 38771L, false,
            "2026-09-24", List.of("哲思小说", "轮回主题"));
    private static final StyleFingerprintVO BOOK = row("BOOK", 18, "草稿落库验证书", null, 8, null, null,
            "2026-09-20", List.of());

    private static StyleFingerprintQueryDTO query(String source, String keyword, String genre, String confidence,
                                                 Integer minMetrics, Long minChars, Long maxChars,
                                                 String from, String to) {
        return new StyleFingerprintQueryDTO(source, keyword, genre, confidence, minMetrics, minChars, maxChars,
                from, to, null);
    }

    private static StyleFingerprintQueryDTO empty() {
        return query(null, null, null, null, null, null, null, null, null);
    }

    @Test
    void emptyConditionsMatchEverything() {
        assertThat(StyleFingerprintService.matches(SAMPLE, empty())).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, query("ALL", "", " ", "ALL", null, null, null, null, null))).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, null)).isTrue();
    }

    @Test
    void sourceAndGenreAreExact() {
        assertThat(StyleFingerprintService.matches(SAMPLE, query("sample", null, null, null, null, null, null, null, null))).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, query("BOOK", null, null, null, null, null, null, null, null))).isFalse();
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, "源主角", null, null, null, null, null, null))).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, "源主", null, null, null, null, null, null))).isFalse();
    }

    @Test
    void keywordHitsNameGenreAndTags() {
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, "源主", null, null, null, null, null, null, null))).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, "轮回", null, null, null, null, null, null, null))).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, "深海系", null, null, null, null, null, null, null))).isFalse();
        assertThat(StyleFingerprintService.matches(BOOK, query(null, "源主角", null, null, null, null, null, null, null))).isFalse();
    }

    @Test
    void lowConfidenceFilterOnlyHitsFlaggedSamples() {
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, null, "HIGH", null, null, null, null, null))).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, null, "LOW", null, null, null, null, null))).isFalse();
        assertThat(StyleFingerprintService.matches(BOOK, query(null, null, null, "LOW", null, null, null, null, null))).isFalse();
        assertThat(StyleFingerprintService.matches(BOOK, query(null, null, null, "HIGH", null, null, null, null, null))).isTrue();
    }

    @Test
    void charRangeExcludesRowsWithoutScale() {
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, null, null, null, 30000L, null, null, null))).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, null, null, null, 40000L, null, null, null))).isFalse();
        // 书籍无语料规模读数：字数条件一律不算命中，避免出现「按字数筛出无字数记录」的假命中
        assertThat(StyleFingerprintService.matches(BOOK, query(null, null, null, null, null, 0L, null, null, null))).isFalse();
    }

    @Test
    void dateRangeIsInclusiveOnBothEnds() {
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, null, null, null, null, null, "2026-09-24", "2026-09-24"))).isTrue();
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, null, null, null, null, null, "2026-09-25", null))).isFalse();
        assertThat(StyleFingerprintService.matches(SAMPLE, query(null, null, null, null, null, null, null, null, "2026-09-23"))).isFalse();
    }

    @Test
    void badDateRejected() {
        assertThatThrownBy(() -> StyleFingerprintService.matches(SAMPLE,
                query(null, null, null, null, null, null, null, "20260924", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void descendingSortsKeepNullsLast() {
        List<StyleFingerprintVO> rows = List.of(BOOK, SAMPLE);
        assertThat(rows.stream().sorted(StyleFingerprintService.comparator("CHARS_DESC")).toList())
                .containsExactly(SAMPLE, BOOK);
        assertThat(rows.stream().sorted(StyleFingerprintService.comparator("TIME_DESC")).toList())
                .containsExactly(SAMPLE, BOOK);
        assertThat(rows.stream().sorted(StyleFingerprintService.comparator("NAME_ASC")).map(StyleFingerprintVO::name).toList())
                .containsExactly("源主角", "草稿落库验证书");
    }

    @Test
    void unknownSortFallsBackToTimeDesc() {
        OffsetDateTime newest = OffsetDateTime.of(2026, 9, 29, 0, 0, 0, 0, ZoneOffset.ofHours(8));
        StyleFingerprintVO fresh = new StyleFingerprintVO("PRESET", 28, "导入书C·文风v1", null, "导入书C", null,
                null, null, 9, null, null, null, null, null, null, null, null, null, newest, null, true, List.of(),
                null, List.of(), List.of(), List.of());
        assertThat(List.of(SAMPLE, fresh).stream().sorted(StyleFingerprintService.comparator("nonsense")).toList())
                .containsExactly(fresh, SAMPLE);
    }
}
