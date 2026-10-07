package com.zzdzz.novelgen.service.data.impl;

import com.zzdzz.novelgen.mapper.ChapterMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 打回两条清场路径的 SQL 编排（打回语义二分的下半截）：
 * 打回正文只重置场景草稿、**不删场景行**（场景蓝图是迁入剧情的载体）；打回章纲才删场景行。
 * 单测钉住「调了哪些 mapper 方法」，SQL 文本本身由实弹跑库验证（本项目无 Spring 集成测试）。
 */
class ChapterDataServiceImplRejectTest {

    private static final long CHAPTER_ID = 1301L;
    private static final String REASON = "结尾钩子不对";

    @Test
    void textRejectResetsDraftsInsteadOfDeletingScenes() {
        ChapterDataServiceImpl impl = new ChapterDataServiceImpl();
        ChapterMapper mapper = mock(ChapterMapper.class);
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);

        impl.resetForTextReject(CHAPTER_ID, REASON);

        verify(mapper).deleteGateReports(CHAPTER_ID);
        verify(mapper).resetSceneDrafts(CHAPTER_ID);
        verify(mapper).markTextRejected(CHAPTER_ID, REASON);
        verify(mapper).deleteChapterSteps(CHAPTER_ID);
        verify(mapper, never()).deleteScenes(CHAPTER_ID);
        verify(mapper, never()).markRejected(CHAPTER_ID, REASON);
    }

    @Test
    void outlineRejectDeletesScenesAndClearsOutline() {
        ChapterDataServiceImpl impl = new ChapterDataServiceImpl();
        ChapterMapper mapper = mock(ChapterMapper.class);
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);

        impl.resetForOutlineReject(CHAPTER_ID, REASON);

        verify(mapper).deleteGateReports(CHAPTER_ID);
        verify(mapper).deleteScenes(CHAPTER_ID);
        verify(mapper).markRejected(CHAPTER_ID, REASON);
        verify(mapper).deleteChapterSteps(CHAPTER_ID);
        verify(mapper, never()).resetSceneDrafts(CHAPTER_ID);
        verify(mapper, never()).markTextRejected(CHAPTER_ID, REASON);
    }
}
