package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.data.ChapterDataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全书导出的拼章题：迁移/换皮建的书 title 为 NULL（不伪造章题），导入书的 title 往往**就是**原行首标题
 * （「第3章」「第一章 归墟」），所以不能再套一层，否则拼出「第3章 第3章 归墟」——那样的导出稿也导入不回来。
 */
class NovelServiceExportTest {

    private static ChapterDataService.ChapterTextWithTitleRow row(int no, String title) {
        return new ChapterDataService.ChapterTextWithTitleRow(no, title, "正文");
    }

    @Test
    void fallsbackToArabicHeadingWhenTitleMissing() {
        assertEquals("第3章", NovelService.chapterHeading(row(3, null)));
        assertEquals("第3章", NovelService.chapterHeading(row(3, "   ")));
    }

    @Test
    void appendsPlainTitleAfterHeading() {
        assertEquals("第3章 归墟", NovelService.chapterHeading(row(3, "归墟")));
    }

    @Test
    void keepsTitleThatIsAlreadyAHeading() {
        assertEquals("第3章", NovelService.chapterHeading(row(3, "第3章")));
        assertEquals("第 12 章 断口", NovelService.chapterHeading(row(12, "第 12 章 断口")));
        // 中文序数也算章题：不能再补一个「第3章」在前面
        assertEquals("第三章 归墟", NovelService.chapterHeading(row(3, "第三章 归墟")));
    }
}
