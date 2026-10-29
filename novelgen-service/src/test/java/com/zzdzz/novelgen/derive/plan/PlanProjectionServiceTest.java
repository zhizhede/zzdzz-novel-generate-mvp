package com.zzdzz.novelgen.derive.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.compiler.PlanProjectionService;
import com.zzdzz.novelgen.derive.plan.compiler.StoryPlanCompiler;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanProjectionServiceTest {

    @Mock
    private RunMapper runMapper;
    @Mock
    private NovelMapper novelMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private BeatInstanceMapper beatInstanceMapper;

    @Test
    void activateRejectsAnyExistingChapterTextBeforeDeletingRows() {
        when(runMapper.selectById(59L)).thenReturn(run());
        NovelDO novel = new NovelDO();
        novel.setId(12L);
        when(novelMapper.selectById(12L)).thenReturn(novel);
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(12L, 1, "已有正文")));

        var input = PlanCompilerFixtures.input(59L, 12);
        var plan = new StoryPlanCompiler().compile(input).plan();

        assertThrows(BizException.class,
                () -> service().activate(59L, 12L, 1, plan));
        verify(chapterMapper, never()).deleteByNovelId(anyLong());
        verify(beatInstanceMapper, never()).deleteByRun(anyLong());
    }

    @Test
    void activateProjectsEveryChapterAndBeatWithPlanRevision() {
        when(runMapper.selectById(59L)).thenReturn(run());
        when(chapterMapper.selectList(any())).thenReturn(List.of(
                chapter(12L, 1, null), chapter(12L, 2, null)));
        when(chapterMapper.deleteByNovelId(12L)).thenReturn(2);
        when(chapterMapper.insertPlanChapter(any())).thenAnswer(invocation -> 70L + ((ChapterDO) invocation.getArgument(0)).getChapterNo());
        when(beatInstanceMapper.deleteByRun(59L)).thenReturn(0);
        when(beatInstanceMapper.insertFull(any())).thenReturn(1L);
        NovelDO novel = new NovelDO();
        novel.setId(12L);
        novel.setCharCount(0);
        when(novelMapper.selectById(12L)).thenReturn(novel);
        when(novelMapper.updateChapterCount(12L, 12)).thenReturn(1);

        var plan = new StoryPlanCompiler()
                .compile(PlanCompilerFixtures.input(59L, 12)).plan();
        service().activate(59L, 12L, 4, plan);

        ArgumentCaptor<ChapterDO> chapters = ArgumentCaptor.forClass(ChapterDO.class);
        verify(chapterMapper, org.mockito.Mockito.times(12)).insertPlanChapter(chapters.capture());
        assertTrue(chapters.getAllValues().stream()
                .allMatch(row -> row.getNovelId() == 12L && row.getPlanRevision() == 4));

        ArgumentCaptor<BeatInstanceDO> beats = ArgumentCaptor.forClass(BeatInstanceDO.class);
        verify(beatInstanceMapper, org.mockito.Mockito.atLeastOnce()).insertFull(beats.capture());
        assertTrue(beats.getAllValues().stream()
                .allMatch(row -> row.getRunId() == 59L && row.getPlanRevision() == 4));
        assertTrue(beats.getAllValues().stream()
                .allMatch(row -> row.getRouteContract().contains("focusCharacters")
                        && row.getRouteContract().contains("dependsOn")));
        verify(novelMapper).updateChapterCount(12L, 12);
    }

    @Test
    void activationNeverDeletesOrWritesAnotherNovel() {
        when(runMapper.selectById(59L)).thenReturn(run());
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(12L, 1, null)));
        when(chapterMapper.deleteByNovelId(12L)).thenReturn(1);
        when(chapterMapper.insertPlanChapter(any())).thenReturn(71L);
        when(beatInstanceMapper.deleteByRun(59L)).thenReturn(0);
        when(beatInstanceMapper.insertFull(any())).thenReturn(1L);
        NovelDO novel = new NovelDO();
        novel.setId(12L);
        novel.setCharCount(0);
        when(novelMapper.selectById(12L)).thenReturn(novel);
        when(novelMapper.updateChapterCount(12L, 12)).thenReturn(1);
        var plan = new StoryPlanCompiler()
                .compile(PlanCompilerFixtures.input(59L, 12)).plan();

        service().activate(59L, 12L, 1, plan);

        verify(chapterMapper).deleteByNovelId(12L);
        verify(chapterMapper, never()).deleteByNovelId(13L);
        verify(beatInstanceMapper).deleteByRun(59L);
        verify(beatInstanceMapper, never()).deleteByRun(60L);
    }

    private PlanProjectionService service() {
        return new PlanProjectionService(runMapper, novelMapper, chapterMapper,
                beatInstanceMapper, new ObjectMapper());
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(59L);
        run.setNovelId(12L);
        return run;
    }

    private static ChapterDO chapter(long novelId, int chapterNo, String content) {
        ChapterDO chapter = new ChapterDO();
        chapter.setId((long) chapterNo);
        chapter.setNovelId(novelId);
        chapter.setChapterNo(chapterNo);
        chapter.setContent(content);
        return chapter;
    }
}






