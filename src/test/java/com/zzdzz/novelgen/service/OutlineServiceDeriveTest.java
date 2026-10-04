package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.data.ChapterDataService.ChapterTextRow;
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
        List<ChapterTextRow> texts = List.of(
                new ChapterTextRow(3, null, "第三章正文"),
                new ChapterTextRow(1, null, "第一章正文"),
                new ChapterTextRow(2, null, "  "),          // 空白正文：不算候选
                new ChapterTextRow(5, null, null));          // 无正文：不算候选

        assertThat(OutlineService.outlineCandidates(texts, 30))
                .extracting(ChapterTextRow::chapterNo).containsExactly(1, 3);
        assertThat(OutlineService.outlineCandidates(texts, 1))
                .extracting(ChapterTextRow::chapterNo).containsExactly(1);
    }

    @Test
    void emptyWhenNothingToDerive() {
        assertThat(OutlineService.outlineCandidates(List.of(), 30)).isEmpty();
        assertThat(OutlineService.outlineCandidates(null, 30)).isEmpty();
        assertThat(OutlineService.outlineCandidates(List.of(new ChapterTextRow(1, null, "正文")), 0)).isEmpty();
        assertThat(OutlineService.outlineCandidates(List.of(new ChapterTextRow(1, null, "正文")), -1)).isEmpty();
    }
}
