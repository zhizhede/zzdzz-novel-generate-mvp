package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.model.entity.StylePackDO;
import com.zzdzz.novelgen.model.dto.NovelCreateDTO;
import com.zzdzz.novelgen.model.vo.NovelImportVO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GenerationTaskDataService;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.PromptTemplateDataService;
import com.zzdzz.novelgen.service.data.SampleCardDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风格包取名/复用/删书级联基线（2026-09-30 用户实弹：删掉「导入书C」后用同名重导，
 * 撞 style_packs 活名唯一约束 uq_style_packs_name_alive，界面横幅里滚出整段 SQL）。
 * 语义锁定：同名残留包一律复用，不再 INSERT；真被活包占着才退让改名；删书级联回收专属包。
 */
class NovelServiceStylePackTest {

    private NovelDataService novelData;
    private StylePackDataService stylePackData;
    private ChapterDataService chapterData;
    private GenerationTaskDataService taskData;
    private NovelService service;

    @BeforeEach
    void setUp() {
        novelData = mock(NovelDataService.class);
        stylePackData = mock(StylePackDataService.class);
        chapterData = mock(ChapterDataService.class);
        taskData = mock(GenerationTaskDataService.class);
        // Mockito 对 Long 返回型给的是 0 而不是 null：书名查重必须显式桩成「没有同名书」，否则全部被
        // requireTitle 的「已有同名作品」挡在门外（这不是产品逻辑，而是 mock 默认值）。
        when(novelData.findIdByTitle(anyString())).thenReturn(null);
        // 同理：可复用包/同名活包默认必须桩成「没有」，否则 0 会被当成包 id 走进复用分支。
        when(stylePackData.findReusablePackId(anyString())).thenReturn(null);
        when(stylePackData.findIdByName(anyString())).thenReturn(null);
        PromptTemplateDataService promptDao = mock(PromptTemplateDataService.class);
        when(promptDao.findAll()).thenReturn(List.of());
        service = new NovelService(novelData, stylePackData, chapterData, mock(DigestService.class),
                mock(SampleCardDataService.class), mock(SamplePlotNodeDataService.class),
                mock(ImportedSampleDataService.class), mock(MaterialCardDataService.class),
                mock(CanonDocDataService.class), taskData,
                mock(com.zzdzz.novelgen.service.data.EmbeddingDataService.class),
                mock(LlmPort.class), mock(LlmJson.class),
                new PromptTemplateService(promptDao), new ObjectMapper(),
                mock(OutlineService.class));
    }

    private static StylePackDO preset(long id, String name) {
        StylePackDO p = new StylePackDO();
        p.setId(id);
        p.setName(name);
        p.setPreset(true);
        p.setRulesMd("规则");
        p.setFingerprint("{\"baseline\":{}}");
        return p;
    }

    private static NovelDO novel(long id, String title) {
        NovelDO d = new NovelDO();
        d.setId(id);
        d.setTitle(title);
        d.setDescription("");
        d.setApprovalMode("auto");
        d.setStatus("active");
        return d;
    }

    private static NovelCreateDTO createVo(String title) {
        return new NovelCreateDTO(title, "简介", 1L, null, null, null, null, null);
    }

    private static NovelImportVO importVo(String title, String text) {
        return new NovelImportVO(title, null, null, text, null, null, null);
    }

    // ===== 取名/复用 =====

    @Test
    void createsFreshPackWhenNameIsFree() {
        when(stylePackData.getById(1L)).thenReturn(preset(1L, "品类预设"));
        when(stylePackData.insertPack(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(42L);
        when(novelData.insert(anyLong(), eq("导入书B2"), anyString(), eq(42L), anyString(), anyString(), anyString()))
                .thenReturn(7L);
        when(novelData.getById(7L)).thenReturn(novel(7L, "导入书B2"));

        assertThat(service.create(createVo("导入书B2"), 1L).id()).isEqualTo(7L);
        verify(stylePackData).insertPack(eq("导入书B2·风格"), anyString(), anyString(), anyString(), any());
        verify(stylePackData, never()).reusePack(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void reusesOrphanPackOfDeletedBookInsteadOfInserting() {
        ChapterDO chapter = new ChapterDO();
        chapter.setId(900L);
        when(stylePackData.findReusablePackId("导入书C·风格")).thenReturn(36L);
        when(novelData.insert(anyLong(), eq("导入书C"), anyString(), eq(36L), anyString(), anyString(), anyString()))
                .thenReturn(25L);
        when(chapterData.find(25L, 1)).thenReturn(Optional.of(chapter));

        NovelService.NovelImportResultVO result = service.importBook(importVo("导入书C", "只剩一行的正文。"), 1L);
        assertThat(result.chapterCount()).isEqualTo(1);
        assertThat(result.pendingFingerprint()).isTrue();
        verify(stylePackData).reusePack(eq(36L), eq("导入书C·风格"), anyString(), eq(""), isNull(), isNull());
        verify(stylePackData, never()).insertPack(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void livePackHoldingTheNameForcesSuffixName() {
        when(stylePackData.findIdByName("刀剑测试书·风格")).thenReturn(5L);
        when(stylePackData.findIdByName("刀剑测试书·风格·2")).thenReturn(null);
        when(stylePackData.insertPack(anyString(), anyString(), anyString(), any(), any())).thenReturn(43L);
        when(novelData.insert(anyLong(), anyString(), anyString(), eq(43L), anyString(), anyString(), anyString()))
                .thenReturn(8L);
        when(novelData.getById(8L)).thenReturn(novel(8L, "刀剑测试书"));
        when(stylePackData.getById(1L)).thenReturn(preset(1L, "品类预设"));

        service.create(createVo("刀剑测试书"), 1L);
        verify(stylePackData).insertPack(eq("刀剑测试书·风格·2"), anyString(), anyString(), any(), any());
    }

    @Test
    void givesUpWithFriendlyMessageWhenNothingIsFree() {
        when(stylePackData.getById(1L)).thenReturn(preset(1L, "品类预设"));
        when(stylePackData.findIdByName(anyString())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(createVo("永远占着"), 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("同名风格包过多");
        verify(stylePackData, never()).insertPack(anyString(), anyString(), anyString(), any(), any());
    }

    // ===== 删书级联 =====

    @Test
    void deletingBookAlsoFreesItsExclusivePack() {
        NovelDO book = novel(25L, "导入书C");
        book.setStylePackId(77L);
        when(novelData.getById(25L)).thenReturn(book);
        when(taskData.existsActiveForNovel(25L)).thenReturn(false);

        service.deleteNovel(25L);

        verify(novelData).delete(25L);
        // 包 id 必须在删书前取出：删完书就再查不到这本书的 style_pack_id 了
        verify(stylePackData).deleteOrphanPack(77L);
    }

    @Test
    void nothingIsTouchedWhenDeletionIsRefused() {
        when(novelData.getById(25L)).thenReturn(novel(25L, "导入书C"));
        when(taskData.existsActiveForNovel(25L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteNovel(25L)).isInstanceOf(BizException.class);

        verify(novelData, never()).delete(anyLong());
        verify(stylePackData, never()).deleteOrphanPack(anyLong());
    }
}
