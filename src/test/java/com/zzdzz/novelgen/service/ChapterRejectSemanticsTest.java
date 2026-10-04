package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ChapterStepDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import com.zzdzz.novelgen.common.web.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 打回语义二分（2026-10-05）：同一个「打回」入口以前一律清场景+章纲，把剧情迁移/换皮迁入的剧情
 * 连锅端掉（书 56 第 3 章 7 拍被 AI 重编成 3 场的直接原因）。现在拆成两条：
 * <ul>
 *   <li>{@link ChapterPipelineService#reject} / {@link ChapterPipelineService#veto}（打回正文）：
 *       调 resetForTextReject——章纲与场景蓝图保留，只清场景草稿；</li>
 *   <li>{@link ChapterPipelineService#rejectOutline}（打回章纲）：仍调 resetForOutlineReject——连场景一起清。</li>
 * </ul>
 */
class ChapterRejectSemanticsTest {

    private static final long NOVEL_ID = 56L;
    private static final long CHAPTER_ID = 1301L;
    private static final int CHAPTER_NO = 3;
    private static final String REASON = "结尾钩子不对";

    private NovelDataService novelData;
    private ChapterDataService chapterData;
    private DigestDataService digestData;
    private ChapterPipelineService service;

    @BeforeEach
    void setUp() {
        novelData = mock(NovelDataService.class);
        chapterData = mock(ChapterDataService.class);
        digestData = mock(DigestDataService.class);
        NovelDO novel = new NovelDO();
        novel.setTitle("换皮测试·环带问真");
        when(novelData.getById(NOVEL_ID)).thenReturn(novel);
        service = new ChapterPipelineService(
                novelData, chapterData,
                mock(SceneDataService.class), mock(ChapterStepDataService.class), digestData,
                mock(OutlineService.class), mock(ContextPackerService.class), mock(SceneService.class),
                mock(GateService.class), mock(DigestService.class), mock(CharacterStateService.class), mock(ReviewService.class),
                mock(VolumePlanService.class), mock(LlmPort.class), mock(StageLog.class),
                mock(TuningService.class), mock(PromptTemplateService.class), new ObjectMapper());
    }

    private void chapterIn(String status) {
        ChapterDO ch = new ChapterDO();
        ch.setId(CHAPTER_ID);
        ch.setNovelId(NOVEL_ID);
        ch.setChapterNo(CHAPTER_NO);
        ch.setStatus(status);
        when(chapterData.findById(CHAPTER_ID)).thenReturn(Optional.of(ch));
    }

    /** 终稿打回：保章纲与场景（迁入剧情不丢），只清正文与场景草稿。 */
    @Test
    void rejectKeepsOutlineAndSceneBlueprints() {
        chapterIn("PENDING_APPROVAL");

        var target = service.reject(CHAPTER_ID, REASON);

        verify(chapterData).resetForTextReject(CHAPTER_ID, REASON);
        verify(chapterData, never()).resetForOutlineReject(CHAPTER_ID, REASON);
        assertThat(target.novelId()).isEqualTo(NOVEL_ID);
        assertThat(target.chapterNo()).isEqualTo(CHAPTER_NO);
    }

    /** 事后否决：与终稿打回同口径（保章纲/场景），只多一步清事实账。 */
    @Test
    void vetoKeepsOutlineAndSceneBlueprints() {
        chapterIn("DIGESTED");

        service.veto(CHAPTER_ID, REASON);

        verify(digestData).deleteByChapter(CHAPTER_ID);
        verify(chapterData).resetForTextReject(CHAPTER_ID, REASON);
        verify(chapterData, never()).resetForOutlineReject(CHAPTER_ID, REASON);
    }

    /** 打回章纲：这才是「连场景一起清」的那一支（重出一套规划）。 */
    @Test
    void rejectOutlineDropsScenes() {
        chapterIn("OUTLINED");

        service.rejectOutline(CHAPTER_ID, REASON);

        verify(chapterData).resetForOutlineReject(CHAPTER_ID, REASON);
        verify(chapterData, never()).resetForTextReject(CHAPTER_ID, REASON);
    }

    /** 状态闸不动：非待审批章照样拒。 */
    @Test
    void rejectStillGuardsStatus() {
        chapterIn("DIGESTED");

        assertThatThrownBy(() -> service.reject(CHAPTER_ID, REASON)).isInstanceOf(BizException.class);
        verify(chapterData, never()).resetForTextReject(CHAPTER_ID, REASON);
    }
}
