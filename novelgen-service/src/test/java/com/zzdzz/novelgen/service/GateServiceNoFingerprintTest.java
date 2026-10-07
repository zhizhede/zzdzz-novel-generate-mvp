package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 无指纹基线时的门禁行为：**必须 fail-open 跑得下去**，不能抛异常。
 * 「未选预设导入的书」在用户采纳「按本书正文提指纹」草稿之前就是这种状态——抛异常会让这本书的生成直接炸。
 */
class GateServiceNoFingerprintTest {

    private StylePackDataService stylePackData;
    private ChapterDataService chapterData;
    private GateService gateService;

    @BeforeEach
    void setUp() {
        stylePackData = mock(StylePackDataService.class);
        chapterData = mock(ChapterDataService.class);
        TuningService tuning = mock(TuningService.class);
        // 参数是基本类型 double：必须用 anyDouble/anyString，用 any() 会在打桩时拆箱 NPE
        when(tuning.d(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        gateService = new GateService(stylePackData, mock(GateReportDataService.class), chapterData, tuning,
                mock(com.zzdzz.novelgen.service.data.NovelDataService.class));
        when(chapterData.findFullText(anyLong(), anyInt())).thenReturn(null);
    }

    private static String chapterText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("雨停在凌晨三点，屋檐上的水一滴一滴落在铁皮桶里。");
            sb.append("\n\n");
            sb.append("他说：「这条巷子我扫了十一年。」她没接话。");
            sb.append("\n\n");
        }
        return sb.toString();
    }

    @Test
    void nullFingerprintDoesNotThrow() {
        when(stylePackData.findFingerprintByNovel(7L)).thenReturn(null);

        // 关键：不抛 IllegalStateException，而是正常给出判定（只跑长度/黑名单/直引号/比喻上限）
        assertThat(gateService.checkChapter(7L, 1L, 1, chapterText(), 1500, 2500)).isNotNull();
        assertThat(gateService.checkScene(7L, 1L, 1L, 1, chapterText(), 2000)).isNotNull();
    }

    @Test
    void blankFingerprintDoesNotThrow() {
        when(stylePackData.findFingerprintByNovel(7L)).thenReturn("   ");

        assertThat(gateService.checkChapter(7L, 1L, 1, chapterText(), 1500, 2500)).isNotNull();
    }

    @Test
    void fingerprintGuidanceIsNullWithoutBaseline() {
        // 无指纹时写作提示不注入指纹目标段（ContextPacker 按 null 跳过），而不是抛异常
        when(stylePackData.findFingerprintByNovel(7L)).thenReturn(null);
        assertThat(gateService.fingerprintGuidance(7L)).isNull();
    }

    @Test
    void brokenFingerprintJsonStillThrows() {
        // 有内容但坏 JSON 属于真异常（数据损坏），保持抛错而不是静默放行
        when(stylePackData.findFingerprintByNovel(7L)).thenReturn("{不是JSON");
        try {
            gateService.checkChapter(7L, 1L, 1, chapterText(), 1500, 2500);
            assertThat(false).as("坏 JSON 应当抛错").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("fingerprint");
        }
    }
}
