package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.model.enums.NovelSourceType;
import com.zzdzz.novelgen.model.vo.NovelQueryVO;
import com.zzdzz.novelgen.model.vo.NovelVO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 导入书籍切章 + 书籍管理筛选/排序语义基线（纯函数，不连库）。 */
class NovelServiceTest {

    private static NovelVO book(long id, String title, String description, String sourceType, String status,
                                String approvalMode, int chapters, boolean autoContinue, String createTime) {
        return new NovelVO(id, title, description, approvalMode, status, sourceType, chapters,
                createTime == null ? null : OffsetDateTime.parse(createTime + "T00:00:00+08:00"),
                autoContinue, null);
    }

    private static final NovelVO IMPORTED = book(1, "夜班守则", "规则怪谈", "IMPORTED", "active", "auto", 12, false,
            "2026-09-10");
    private static final NovelVO DERIVED = book(9, "悉达多衍生-test", "哲思衍生", "DERIVED", "active", "manual", 40, true,
            "2026-09-24");
    private static final NovelVO ORIGINAL = book(8, "草稿落库验证书", null, "ORIGINAL", "draft", "auto", 0, false,
            "2026-09-20");

    private static NovelQueryVO query(String keyword, String sourceType, String status, String approvalMode,
                                      String autoContinue, Integer minChapters, Integer maxChapters,
                                      String from, String to) {
        return new NovelQueryVO(keyword, sourceType, status, approvalMode, autoContinue, minChapters, maxChapters,
                from, to, null);
    }

    private static NovelQueryVO empty() {
        return query(null, null, null, null, null, null, null, null, null);
    }

    // ===== 筛选 =====

    @Test
    void emptyConditionsMatchEverything() {
        assertThat(NovelService.matches(ORIGINAL, empty())).isTrue();
        assertThat(NovelService.matches(ORIGINAL, query("", "ALL", "ALL", "ALL", "ALL", null, null, null, null))).isTrue();
        assertThat(NovelService.matches(ORIGINAL, null)).isTrue();
    }

    @Test
    void sourceTypeIsExactAndCaseInsensitive() {
        assertThat(NovelService.matches(IMPORTED, query(null, "imported", null, null, null, null, null, null, null))).isTrue();
        assertThat(NovelService.matches(IMPORTED, query(null, "DERIVED", null, null, null, null, null, null, null))).isFalse();
    }

    @Test
    void keywordHitsTitleOrDescription() {
        assertThat(NovelService.matches(DERIVED, query("悉达多", null, null, null, null, null, null, null, null))).isTrue();
        assertThat(NovelService.matches(DERIVED, query("哲思", null, null, null, null, null, null, null, null))).isTrue();
        assertThat(NovelService.matches(ORIGINAL, query("悉达多", null, null, null, null, null, null, null, null))).isFalse();
        // 简介为空的行不会被关键字误命中（null 不参与拼接）
        assertThat(NovelService.matches(ORIGINAL, query("null", null, null, null, null, null, null, null, null))).isFalse();
    }

    @Test
    void statusAndApprovalModeAndAutoContinue() {
        assertThat(NovelService.matches(ORIGINAL, query(null, null, "draft", null, null, null, null, null, null))).isTrue();
        assertThat(NovelService.matches(DERIVED, query(null, null, "draft", null, null, null, null, null, null))).isFalse();
        assertThat(NovelService.matches(DERIVED, query(null, null, null, "manual", null, null, null, null, null))).isTrue();
        assertThat(NovelService.matches(DERIVED, query(null, null, null, null, "ON", null, null, null, null))).isTrue();
        assertThat(NovelService.matches(DERIVED, query(null, null, null, null, "OFF", null, null, null, null))).isFalse();
        assertThat(NovelService.matches(IMPORTED, query(null, null, null, null, "OFF", null, null, null, null))).isTrue();
    }

    @Test
    void chapterRangeIsInclusive() {
        assertThat(NovelService.matches(DERIVED, query(null, null, null, null, null, 40, 40, null, null))).isTrue();
        assertThat(NovelService.matches(DERIVED, query(null, null, null, null, null, 41, null, null, null))).isFalse();
        assertThat(NovelService.matches(DERIVED, query(null, null, null, null, null, null, 39, null, null))).isFalse();
        // 0 章的书（尚未生成）用「≥1 章」筛时不命中
        assertThat(NovelService.matches(ORIGINAL, query(null, null, null, null, null, 1, null, null, null))).isFalse();
    }

    @Test
    void dateRangeIsInclusiveOnBothEnds() {
        assertThat(NovelService.matches(DERIVED, query(null, null, null, null, null, null, null, "2026-09-24", "2026-09-24"))).isTrue();
        assertThat(NovelService.matches(DERIVED, query(null, null, null, null, null, null, null, "2026-09-25", null))).isFalse();
        assertThat(NovelService.matches(DERIVED, query(null, null, null, null, null, null, null, null, "2026-09-23"))).isFalse();
    }

    @Test
    void badDateRejected() {
        assertThatThrownBy(() -> NovelService.matches(DERIVED, query(null, null, null, null, null, null, null, "20260924", null)))
                .isInstanceOf(com.zzdzz.novelgen.common.web.BizException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    // ===== 排序 =====

    @Test
    void defaultSortKeepsLegacyIdOrder() {
        List<NovelVO> rows = List.of(DERIVED, IMPORTED, ORIGINAL);
        assertThat(rows.stream().sorted(NovelService.comparator(null)).map(NovelVO::id).toList())
                .containsExactly(1L, 8L, 9L);
        assertThat(rows.stream().sorted(NovelService.comparator("unknown")).map(NovelVO::id).toList())
                .containsExactly(1L, 8L, 9L);
    }

    @Test
    void explicitSorts() {
        List<NovelVO> rows = List.of(DERIVED, IMPORTED, ORIGINAL);
        assertThat(rows.stream().sorted(NovelService.comparator("TIME_DESC")).map(NovelVO::id).toList())
                .containsExactly(9L, 8L, 1L);
        assertThat(rows.stream().sorted(NovelService.comparator("CHAPTERS_DESC")).map(NovelVO::id).toList())
                .containsExactly(9L, 1L, 8L);
        assertThat(rows.stream().sorted(NovelService.comparator("TITLE_ASC")).map(NovelVO::title).toList())
                .containsExactly("夜班守则", "悉达多衍生-test", "草稿落库验证书");
    }

    // ===== 入库类型 =====

    @Test
    void sourceTypeNormalizeAndOfSample() {
        assertThat(NovelSourceType.normalize(null)).isEqualTo("ORIGINAL");
        assertThat(NovelSourceType.normalize(" ")).isEqualTo("ORIGINAL");
        assertThat(NovelSourceType.normalize("derived")).isEqualTo("DERIVED");
        assertThat(NovelSourceType.normalize("瞎写")).isEqualTo("ORIGINAL");
        assertThat(NovelSourceType.ofSample(null)).isEqualTo("ORIGINAL");
        assertThat(NovelSourceType.ofSample(7L)).isEqualTo("DERIVED");
    }

    // ===== 切章 =====

    @Test
    void splitChaptersByHeading() {
        String text = "第1章 雨夜\n雨下了一夜。\n\n第2章 天亮\n天亮了。\n\n第3章\n什么都没发生。";
        List<NovelService.ChapterSlice> slices = NovelService.splitChapters(text);
        assertThat(slices).hasSize(3);
        assertThat(slices.get(0).no()).isEqualTo(1);
        assertThat(slices.get(0).title()).isEqualTo("雨夜");
        assertThat(slices.get(0).content()).isEqualTo("雨下了一夜。");
        assertThat(slices.get(1).title()).isEqualTo("天亮");
        assertThat(slices.get(2).title()).isEqualTo("第3章");
        assertThat(slices.get(2).content()).isEqualTo("什么都没发生。");
    }

    @Test
    void headingLineItselfNeverLeaksIntoBody() {
        List<NovelService.ChapterSlice> slices = NovelService.splitChapters("第1章 开端\n正文一\n第2章 续\n正文二");
        assertThat(slices.get(0).content()).isEqualTo("正文一");
        assertThat(slices.get(0).content()).doesNotContain("第1章");
        assertThat(slices.get(1).content()).isEqualTo("正文二");
    }

    @Test
    void preambleMergesIntoFirstChapter() {
        List<NovelService.ChapterSlice> slices = NovelService.splitChapters("书名\n作者：某人\n\n第1章\n正文");
        assertThat(slices).hasSize(1);
        assertThat(slices.get(0).content()).startsWith("书名").contains("正文");
    }

    @Test
    void chineseNumeralHeadingsAreRecognized() {
        // 第X章（中文数字）
        assertThat(NovelService.parseHeading("第一章 雨夜")).isEqualTo(new NovelService.Heading(1, "雨夜"));
        assertThat(NovelService.parseHeading("第十一章 漂流")).isEqualTo(new NovelService.Heading(11, "漂流"));
        assertThat(NovelService.chineseToInt("二十一")).isEqualTo(21);
        assertThat(NovelService.chineseToInt("十")).isEqualTo(10);
        assertThat(NovelService.chineseToInt("一百")).isEqualTo(100);
    }

    @Test
    void chineseNumeralSectionHeadingsAreRecognized() {
        // X、标题（中文数字 + 顿号/点/冒号）——实弹来自用户 docx「一、登船 / 十二、漂流」
        assertThat(NovelService.parseHeading("一、登船")).isEqualTo(new NovelService.Heading(1, "登船"));
        assertThat(NovelService.parseHeading("十二、漂流")).isEqualTo(new NovelService.Heading(12, "漂流"));
        assertThat(NovelService.parseHeading("三、船长与海")).isEqualTo(new NovelService.Heading(3, "船长与海"));
        assertThat(NovelService.parseHeading("四．水手们")).isEqualTo(new NovelService.Heading(4, "水手们"));
        assertThat(NovelService.parseHeading("五：风来了")).isEqualTo(new NovelService.Heading(5, "风来了"));
    }

    @Test
    void headingLikeProseLineIsNotTreatedAsHeading() {
        // 长度闸：中文数字开头的长正文行不能当章标题，否则书会被切碎
        String prose = "一、他想起那件事的时候正在下雨，巷子里的水漫过脚踝，凉得人打个哆嗦。";
        assertThat(prose.length()).isGreaterThan(30);
        assertThat(NovelService.parseHeading(prose)).isNull();
        // 阿拉伯数字的「第N章」沿用既有口径，不受长度闸影响（已在跑的行为不动）
        assertThat(NovelService.parseHeading("第1章 " + "很长的标题".repeat(8))).isNotNull();
        // 非标题行
        assertThat(NovelService.parseHeading("雨下了整夜。")).isNull();
        assertThat(NovelService.parseHeading("")).isNull();
        assertThat(NovelService.parseHeading(null)).isNull();
    }

    @Test
    void splitsBookByChineseSectionHeadings() {
        String text = "书名\n作者\n\n一、登船\n甲板上没有人。\n\n二、商人\n他数着钱。\n\n三、船长与海\n浪打在舷侧。";
        List<NovelService.ChapterSlice> slices = NovelService.splitChapters(text);
        assertThat(slices).hasSize(3);
        assertThat(slices.get(0).no()).isEqualTo(1);
        assertThat(slices.get(0).title()).isEqualTo("登船");
        assertThat(slices.get(0).content()).contains("书名").contains("甲板上没有人。");
        assertThat(slices.get(1).title()).isEqualTo("商人");
        assertThat(slices.get(2).title()).isEqualTo("船长与海");
    }

    @Test
    void textWithoutHeadingsBecomesSingleChapter() {
        List<NovelService.ChapterSlice> slices = NovelService.splitChapters("就是一段没有任何章标题的正文。");
        assertThat(slices).hasSize(1);
        assertThat(slices.get(0).no()).isEqualTo(1);
        assertThat(slices.get(0).title()).isEqualTo("第1章");
    }

    @Test
    void renumberWhenNotContiguousFromOne() {
        String text = "第5章 甲\n正文甲\n第6章 乙\n正文乙";
        List<NovelService.ChapterSlice> slices = new java.util.ArrayList<>(NovelService.splitChapters(text));
        // 不连续/不从 1 开始 → 重排为 1..N，无标题的「第N章」标题同步改写
        assertThat(NovelService.renumber(slices)).isTrue();
        assertThat(slices.get(0).no()).isEqualTo(1);
        assertThat(slices.get(1).no()).isEqualTo(2);
        assertThat(slices.get(0).title()).isEqualTo("甲");

        List<NovelService.ChapterSlice> plain = new java.util.ArrayList<>(NovelService.splitChapters("第7章\n正文"));
        assertThat(NovelService.renumber(plain)).isTrue();
        assertThat(plain.get(0).title()).isEqualTo("第1章");
    }

    @Test
    void renumberNoopWhenAlreadySequential() {
        List<NovelService.ChapterSlice> slices = new java.util.ArrayList<>(
                NovelService.splitChapters("第1章 甲\n正文\n第2章 乙\n正文"));
        assertThat(NovelService.renumber(slices)).isFalse();
        assertThat(slices.get(1).no()).isEqualTo(2);
    }

}
