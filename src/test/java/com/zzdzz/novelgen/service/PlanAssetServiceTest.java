package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.model.vo.PlanAssetQueryVO;
import com.zzdzz.novelgen.model.vo.PlanAssetVO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规划资产三层筛选/排序语义基线（纯函数，不连库）：
 * 与层级无关的条件在不适用的层上**不生效**（大纲层不判章状态/正文），
 * 「未分卷」用 volumeNo=0 表达，卷层按章号区间**相交**判定。
 */
class PlanAssetServiceTest {

    private static PlanAssetVO row(String level, long novelId, Integer volumeNo, Integer chapterNo,
                                   String status, Boolean hasOutline, Boolean hasText, Long textChars,
                                   Long outlineChars, String title, String goal, String outline, Boolean skeleton,
                                   String createTime) {
        return new PlanAssetVO(level, novelId, title, "IMPORTED", volumeNo, "卷一",
                chapterNo, title, goal, "钩子", "三天后",
                "VOLUME".equals(level) ? 3 : null, 1, 3, 2, 1,
                hasOutline, outlineChars, outline, skeleton, 800, 1200,
                status, hasText, textChars, 0,
                null, null, null, null, null,
                createTime == null ? null : OffsetDateTime.parse(createTime + "T00:00:00+08:00"), null);
    }

    private static PlanAssetVO chapter(long novelId, int no, String status, boolean hasOutline, boolean hasText) {
        return row("CHAPTER", novelId, 1, no, status, hasOutline, hasText,
                hasText ? 3000L : 0L, hasOutline ? 1200L : 0L, "第" + no + "章·章名", "本章目标", null, null, "2026-09-20");
    }

    private static PlanAssetVO outline(long novelId, boolean has, String content, Boolean skeleton, String createTime) {
        return row("OUTLINE", novelId, null, null, null, has, null, null,
                content == null ? null : (long) content.length(), "书名" + novelId, null, content, skeleton, createTime);
    }

    private static PlanAssetVO volume(long novelId, Integer volNo, int fromNo, int toNo, boolean hasReview) {
        return new PlanAssetVO("VOLUME", novelId, "书名" + novelId, "DERIVED", volNo, "卷一·风起",
                null, null, null, null, null, 10, fromNo, toNo, 8, 6,
                true, null, null, null, 800, 1200, null, true, 30000L, null,
                hasReview, hasReview ? "卷复盘摘要" : null, hasReview ? 2 : null, hasReview ? 1 : null,
                hasReview ? "{\"review\":{}}" : null,
                OffsetDateTime.parse("2026-09-21T00:00:00+08:00"), null);
    }

    private static PlanAssetQueryVO q(String level, Long novelId, String sourceType, String keyword,
                                      Integer volumeNo, Integer fromChapter, Integer toChapter, String status,
                                      String hasOutline, String hasText, String skeleton,
                                      Long minChars, Long maxChars, String from, String to, String sort) {
        return new PlanAssetQueryVO(level, novelId, sourceType, keyword, volumeNo, fromChapter, toChapter,
                status, hasOutline, hasText, skeleton, minChars, maxChars, from, to, sort);
    }

    private static PlanAssetQueryVO levelOnly(String level) {
        return q(level, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // ===== 层级 =====

    @Test
    void levelScopesRowsAndUnknownLevelMeansChapter() {
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, false), levelOnly("CHAPTER"))).isTrue();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, false), levelOnly("VOLUME"))).isFalse();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, false), levelOnly(null))).isTrue();
        assertThat(PlanAssetService.level("outline")).isEqualTo("OUTLINE");
        assertThat(PlanAssetService.level("volume")).isEqualTo("VOLUME");
        assertThat(PlanAssetService.level("乱写")).isEqualTo("CHAPTER");
    }

    // ===== 关键字 =====

    @Test
    void keywordHitsTitleGoalAndOutlineBody() {
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, false),
                q(null, null, null, "章名", null, null, null, null, null, null, null, null, null, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, false),
                q(null, null, null, "本章目标", null, null, null, null, null, null, null, null, null, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(outline(2, true, "全书大纲：主角入海", false, "2026-09-01"),
                q(null, null, null, "主角入海", null, null, null, null, null, null, null, null, null, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, false),
                q(null, null, null, "不存在的词", null, null, null, null, null, null, null, null, null, null, null, null))).isFalse();
    }

    // ===== 缺口清单（hasOutline / hasText） =====

    @Test
    void hasOutlineNoFindsTheGaps() {
        PlanAssetQueryVO noOutline = q("OUTLINE", null, null, null, null, null, null, null, "NO", null, null,
                null, null, null, null, null);
        assertThat(PlanAssetService.matches(outline(2, false, null, null, "2026-09-01"), noOutline)).isTrue();
        assertThat(PlanAssetService.matches(outline(2, true, "大纲正文", false, "2026-09-01"), noOutline)).isFalse();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", false, false),
                q("CHAPTER", null, null, null, null, null, null, null, "NO", "NO", null, null, null, null, null, null))).isTrue();
    }

    @Test
    void hasTextAndStatusAreIgnoredOnOutlineLevel() {
        PlanAssetVO bare = outline(2, false, null, null, "2026-09-01");
        assertThat(PlanAssetService.matches(bare,
                q("OUTLINE", null, null, null, null, null, null, "FINAL", null, "YES", null, null, null, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(chapter(1, 3, "FINAL", true, true),
                q("CHAPTER", null, null, null, null, null, null, "final", null, null, null, null, null, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, true),
                q("CHAPTER", null, null, null, null, null, null, "FINAL", null, null, null, null, null, null, null, null))).isFalse();
    }

    @Test
    void skeletonFilterOnlyAppliesToOutlines() {
        assertThat(PlanAssetService.matches(outline(2, true, "> 由样本《某书》预填骨架", true, "2026-09-01"),
                q("OUTLINE", null, null, null, null, null, null, null, null, null, "YES", null, null, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(outline(2, true, "真大纲", false, "2026-09-01"),
                q("OUTLINE", null, null, null, null, null, null, null, null, null, "YES", null, null, null, null, null))).isFalse();
        // 章纲层没有骨架概念：该条件不生效，行仍命中
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, false),
                q("CHAPTER", null, null, null, null, null, null, null, null, null, "YES", null, null, null, null, null))).isTrue();
    }

    // ===== 卷号与章号区间 =====

    @Test
    void volumeZeroMeansUnassignedImportChapters() {
        PlanAssetVO unassigned = row("CHAPTER", 1, null, 1, "FINAL", false, true, 900L, 0L,
                "登船", null, null, null, "2026-09-20");
        assertThat(PlanAssetService.matches(unassigned,
                q(null, null, null, null, 0, null, null, null, null, null, null, null, null, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, false),
                q(null, null, null, null, 0, null, null, null, null, null, null, null, null, null, null, null))).isFalse();
        assertThat(PlanAssetService.matches(unassigned,
                q(null, null, null, null, 1, null, null, null, null, null, null, null, null, null, null, null))).isFalse();
    }

    @Test
    void chapterRangeIntersectsVolumesAndFiltersChapters() {
        PlanAssetVO vol = volume(2, 2, 11, 20, true);
        assertThat(PlanAssetService.matches(vol,
                q("VOLUME", null, null, null, null, 15, 25, null, null, null, null, null, null, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(vol,
                q("VOLUME", null, null, null, null, 21, 30, null, null, null, null, null, null, null, null, null))).isFalse();
        assertThat(PlanAssetService.matches(vol,
                q("VOLUME", null, null, null, null, 1, 5, null, null, null, null, null, null, null, null, null))).isFalse();
        // 大纲层没有章号概念：带章号区间即排除
        assertThat(PlanAssetService.matches(outline(2, true, "大纲", false, "2026-09-01"),
                q("OUTLINE", null, null, null, null, 1, 5, null, null, null, null, null, null, null, null, null))).isFalse();
    }

    // ===== 字数与时间 =====

    @Test
    void charRangeUsesOutlineCharsOnOutlineLevelAndTextCharsElsewhere() {
        assertThat(PlanAssetService.matches(outline(2, true, "一二三四五", false, "2026-09-01"),
                q("OUTLINE", null, null, null, null, null, null, null, null, null, null, 5L, 10L, null, null, null))).isTrue();
        assertThat(PlanAssetService.matches(outline(2, true, "一二三四五", false, "2026-09-01"),
                q("OUTLINE", null, null, null, null, null, null, null, null, null, null, 6L, null, null, null, null))).isFalse();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, true),
                q("CHAPTER", null, null, null, null, null, null, null, null, null, null, 3000L, null, null, null, null))).isTrue();
    }

    @Test
    void dateRangeFiltersOnCreateTimeAndRejectsBadFormat() {
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, true),
                q(null, null, null, null, null, null, null, null, null, null, null, null, null, "2026-09-19", "2026-09-21", null))).isTrue();
        assertThat(PlanAssetService.matches(chapter(1, 3, "NEW", true, true),
                q(null, null, null, null, null, null, null, null, null, null, null, null, null, "2026-09-21", null, null))).isFalse();
        assertThatThrownBy(() -> PlanAssetService.matches(chapter(1, 3, "NEW", true, true),
                q(null, null, null, null, null, null, null, null, null, null, null, null, null, "2026/09/19", null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("yyyy-MM-dd");
    }

    // ===== 排序 =====

    @Test
    void defaultSortIsReadingOrderWithUnassignedVolumesFirst() {
        List<PlanAssetVO> rows = List.of(
                chapter(2, 1, "NEW", true, false),
                row("CHAPTER", 1, 2, 11, "NEW", true, false, 0L, 1200L, "第11章", null, null, null, "2026-09-20"),
                row("CHAPTER", 1, null, 1, "FINAL", false, true, 900L, 0L, "登船", null, null, null, "2026-09-20"));
        List<PlanAssetVO> sorted = rows.stream().sorted(PlanAssetService.comparator(null)).toList();
        assertThat(sorted.stream().map(r -> r.novelId() + "/" + r.volumeNo() + "/" + r.chapterNo()).toList())
                .containsExactly("1/null/1", "1/2/11", "2/1/1");
    }

    @Test
    void timeSortAndUnknownSortFallBackSafely() {
        PlanAssetVO early = outline(1, true, "大纲", false, "2026-09-01");
        PlanAssetVO late = outline(2, true, "大纲", false, "2026-09-20");
        assertThat(List.of(early, late).stream()
                .sorted(PlanAssetService.comparator("TIME_DESC")).map(PlanAssetVO::novelId).toList())
                .containsExactly(2L, 1L);
        // 未知取值回落到默认读序（书升序）
        assertThat(List.of(late, early).stream()
                .sorted(PlanAssetService.comparator("瞎写")).map(PlanAssetVO::novelId).toList())
                .containsExactly(1L, 2L);
        // 缺时间的行不会被降序翻到最前
        PlanAssetVO noTime = outline(3, false, null, null, null);
        assertThat(List.of(early, noTime, late).stream()
                .sorted(PlanAssetService.comparator("TIME_DESC")).map(PlanAssetVO::novelId).toList())
                .containsExactly(2L, 1L, 3L);
    }
}
