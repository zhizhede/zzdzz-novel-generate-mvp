package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.model.vo.BookFingerprintDraftVO;
import com.zzdzz.novelgen.model.vo.FingerprintApplyVO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 按本书正文提指纹：样本下限/低置信判定/采纳校验（DAO 全 mock，只锁业务判定）。 */
class BookFingerprintServiceTest {

    private ChapterDataService chapterData;
    private StylePackDataService stylePackData;
    private NovelDataService novelData;
    private BookFingerprintService service;

    /** 每行 12 字左右、含标点与对白，保证指标非零（纯函数 computeMetrics 对同一文本结果稳定）。 */
    private static String chapter(int paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            sb.append("雨停在凌晨三点，屋檐上的水一滴一滴落在铁皮桶里，声音钝而均匀。")
                    .append("\n\n")
                    .append("他说：「这条巷子我扫了十一年。」她没接话——手里那把扫帚停了停。")
                    .append("\n\n");
        }
        return sb.toString();
    }

    @BeforeEach
    void setUp() {
        chapterData = mock(ChapterDataService.class);
        stylePackData = mock(StylePackDataService.class);
        novelData = mock(NovelDataService.class);
        NovelDO novel = new NovelDO();
        novel.setId(9L);
        novel.setTitle("测试书");
        novel.setStylePackId(33L);
        when(novelData.getById(9L)).thenReturn(novel);
        service = new BookFingerprintService(chapterData, stylePackData, novelData, new ObjectMapper());
    }

    private static ChapterDataService.ChapterTextWithTitleRow row(int no, String text) {
        return new ChapterDataService.ChapterTextWithTitleRow(no, null, text);
    }

    @Test
    void draftFromTwoChaptersIsLowConfidence() {
        // punct 指标 2026-10-07 改口径：无收引号行尾的文本 = 1.0 跳过（不再全零），触发不了「硬下限提示」——
        // 要测该笔记得给「行尾收引号但句末无标点」的真实低分区行（ratio 0.0 → 全零剔除 → 笔记提示 0.5 硬下限仍在）
        String lowPunct = "“扫了十一年”\n“她没接话”\n“雨停了”\n";
        when(chapterData.listTextsByNovel(9L)).thenReturn(
                List.of(row(1, chapter(20) + lowPunct), row(2, chapter(20) + lowPunct)));

        BookFingerprintDraftVO draft = service.draft(9L);

        assertThat(draft.novelId()).isEqualTo(9L);
        assertThat(draft.title()).isEqualTo("测试书");
        assertThat(draft.chapterCount()).isEqualTo(2);
        assertThat(draft.totalChars()).isGreaterThan(2000);
        assertThat(draft.lowConfidence()).isTrue();
        assertThat(draft.metricCount()).isGreaterThan(0);
        assertThat(draft.metrics()).hasSize(draft.metricCount());
        // 中文名由后端给（MetricLabels 单源），前端不再自建映射
        assertThat(draft.metrics()).allSatisfy(m -> assertThat(m.label()).isNotBlank());
        assertThat(draft.fingerprintJson()).contains("\"baseline\"");
        assertThat(draft.notes()).anySatisfy(n -> assertThat(n).contains("样本过少"));
        // 与门禁硬下限的交汇提示：样本正文对白句末无标点（基线 0 或被全零剔除）时，必须明确告知 0.5 硬下限仍在
        assertThat(draft.notes()).anySatisfy(n -> assertThat(n).contains("硬性要求 ≥0.5"));
        assertThat(draft.budgetMax()).isGreaterThanOrEqualTo(draft.budgetMin());
    }

    @Test
    void draftRejectsBookWithoutText() {
        when(chapterData.listTextsByNovel(9L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.draft(9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("还没有正文");
    }

    @Test
    void draftRejectsTooLittleText() {
        when(chapterData.listTextsByNovel(9L)).thenReturn(List.of(row(1, "他走了。")));
        assertThatThrownBy(() -> service.draft(9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("太少");
    }

    @Test
    void draftRejectsUnknownNovel() {
        when(novelData.getById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.draft(404L)).isInstanceOf(BizException.class);
    }

    @Test
    void applyValidatesFingerprintShape() {
        assertThatThrownBy(() -> service.apply(9L, null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.apply(9L, new FingerprintApplyVO("  ", null, null, null, false)))
                .isInstanceOf(BizException.class).hasMessageContaining("为空");
        assertThatThrownBy(() -> service.apply(9L, new FingerprintApplyVO("{不是JSON", null, null, null, false)))
                .isInstanceOf(BizException.class).hasMessageContaining("合法 JSON");
        assertThatThrownBy(() -> service.apply(9L, new FingerprintApplyVO("{\"baseline\":{}}", null, null, null, false)))
                .isInstanceOf(BizException.class).hasMessageContaining("baseline");
        assertThatThrownBy(() -> service.apply(9L,
                new FingerprintApplyVO("{\"baseline\":{\"dash_per1k\":{\"value\":1}}}", null, null, null, true)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void applyWritesFingerprintAndOptionallyBand() {
        when(stylePackData.findGateConfigByNovel(9L)).thenReturn("{\"banned_phrases\":[\"心中暗想\"]}");

        service.apply(9L, new FingerprintApplyVO("{\"baseline\":{\"dash_per1k\":{\"value\":1.2}}}",
                1800, 2600, 0.15, true));

        verify(stylePackData).updateFingerprint(eq(33L), anyString());
        // 章长带合并进本书 gate_config：原有键必须保留（只覆盖带三元组）
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(stylePackData).updateGateConfigByNovel(eq(9L), captor.capture());
        String merged = captor.getValue();
        assertThat(merged).contains("1800").contains("2600").contains("chapter_length_tolerance")
                .contains("心中暗想");
    }

    @Test
    void applySkipsBandWhenNotRequested() {
        service.apply(9L, new FingerprintApplyVO("{\"baseline\":{\"dash_per1k\":{\"value\":1.2}}}",
                1800, 2600, 0.15, false));
        verify(stylePackData).updateFingerprint(eq(33L), anyString());
        verify(stylePackData, never()).updateGateConfigByNovel(anyLong(), anyString());
    }

    @Test
    void applyRejectsBookWithoutStylePack() {
        NovelDO packless = new NovelDO();
        packless.setId(77L);
        packless.setStylePackId(null);
        when(novelData.getById(77L)).thenReturn(packless);
        assertThatThrownBy(() -> service.apply(77L,
                new FingerprintApplyVO("{\"baseline\":{\"dash_per1k\":{\"value\":1}}}", null, null, null, false)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有风格包");
    }

    @Test
    void budgetBandMergeIsPureAndKeepsOtherKeys() {
        String merged = DeriveSupport.applyBudgetBand("{\"reader_fat_ratio_block\":0.33}", 1500, 2500, 0.2);
        assertThat(merged).contains("\"budget_min\":1500").contains("\"budget_max\":2500")
                .contains("chapter_length_tolerance").contains("reader_fat_ratio_block");
        // null/坏 JSON 从空对象起步，不抛错
        assertThat(DeriveSupport.applyBudgetBand(null, 1500, 2500, 0.2)).contains("budget_min");
        assertThat(DeriveSupport.applyBudgetBand("{坏", 1500, 2500, 0.2)).contains("budget_min");
    }
}
