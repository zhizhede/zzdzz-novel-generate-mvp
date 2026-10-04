package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.data.ChapterDataService.ChapterTextWithTitleRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章纲反推的选章口径（纯函数，不连库、零 LLM）：
 * 只取有正文的章、按章号升序、截断到 cap；cap&lt;=0 或空表一律空——导入书上百章不能无上限全跑。
 */
class OutlineServiceDeriveTest {

    @Test
    void picksChaptersWithTextInChapterOrderUpToCap() {
        List<ChapterTextWithTitleRow> texts = List.of(
                new ChapterTextWithTitleRow(3, null, "第三章正文"),
                new ChapterTextWithTitleRow(1, null, "第一章正文"),
                new ChapterTextWithTitleRow(2, null, "  "),          // 空白正文：不算候选
                new ChapterTextWithTitleRow(5, null, null));          // 无正文：不算候选

        assertThat(OutlineService.outlineCandidates(texts, 30))
                .extracting(ChapterTextWithTitleRow::chapterNo).containsExactly(1, 3);
        assertThat(OutlineService.outlineCandidates(texts, 1))
                .extracting(ChapterTextWithTitleRow::chapterNo).containsExactly(1);
    }

    @Test
    void emptyWhenNothingToDerive() {
        assertThat(OutlineService.outlineCandidates(List.of(), 30)).isEmpty();
        assertThat(OutlineService.outlineCandidates(null, 30)).isEmpty();
        assertThat(OutlineService.outlineCandidates(List.of(new ChapterTextWithTitleRow(1, null, "正文")), 0)).isEmpty();
        assertThat(OutlineService.outlineCandidates(List.of(new ChapterTextWithTitleRow(1, null, "正文")), -1)).isEmpty();
    }
}
