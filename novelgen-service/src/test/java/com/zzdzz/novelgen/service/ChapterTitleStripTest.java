package com.zzdzz.novelgen.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 剥章题行：迁移/换皮建的章行 title 恒为 NULL（样本章题是「第N章（无标题）」，不伪造），
 * 所以不能只靠等值匹配——否则模型写在正文首行的「第3章」会一路进成品（书 56 第 3/4/12 章、书 57 第 3 章）。
 * 同时判据必须收紧，不能把真正的正文首行误剥。
 */
class ChapterTitleStripTest {

    @Test
    void stripsHeadingWhenTitleIsNull() {
        assertEquals("律纹的第七十三道折角从我指下收笔。",
                ChapterPipelineService.stripTitleLine("第3章\n律纹的第七十三道折角从我指下收笔。", null));
        assertEquals("潮声从池底升起来。",
                ChapterPipelineService.stripTitleLine("第 12 章\n潮声从池底升起来。", null));
        assertEquals("他推开门。",
                ChapterPipelineService.stripTitleLine("第三章：断口\n他推开门。", null));
        assertEquals("她没回头。",
                ChapterPipelineService.stripTitleLine("第3章 归墟\n她没回头。", null));
    }

    @Test
    void stripsHeadingMarkedWithTrailingPunct() {
        assertEquals("风停了。", ChapterPipelineService.stripTitleLine("第3章。\n风停了。", null));
        assertEquals("风停了。", ChapterPipelineService.stripTitleLine("第3章\n风停了。", ""));
    }

    @Test
    void keepsTitleEqualityPathWorking() {
        assertEquals("正文首行。", ChapterPipelineService.stripTitleLine("归墟\n正文首行。", "归墟"));
        assertEquals("正文首行。", ChapterPipelineService.stripTitleLine("归墟。\n正文首行。", "归墟"));
    }

    @Test
    void doesNotStripRealProse() {
        // 行首虽像章序号，但整行是正文句子：不得剥
        assertEquals("第三章的门在右边。",
                ChapterPipelineService.stripTitleLine("第三章的门在右边。", null));
        assertEquals("第三章讲的是他离城那一年。",
                ChapterPipelineService.stripTitleLine("第三章讲的是他离城那一年。", null));
        // 首行正常正文，不能动
        assertEquals("他把灯挑亮了些。\n第二句。",
                ChapterPipelineService.stripTitleLine("他把灯挑亮了些。\n第二句。", null));
        // 只有章题一行、后面没有正文 → 剥成空串（与等值分支同口径）
        assertEquals("", ChapterPipelineService.stripTitleLine("第3章", null));
    }
}
