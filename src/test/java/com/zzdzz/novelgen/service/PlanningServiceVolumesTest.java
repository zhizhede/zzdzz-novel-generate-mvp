package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规划页卷纲列表的「正文已成 / 待生成」判定基线（2026-10-01 修）。
 * 老实现拿 `listSummariesByNovel` 的 `getFullText()` 判有无正文，而那一列是为省流量置空的
 * （`NULL AS full_text`）→ hasText **恒为 false**，规划页把已有正文的章全标成「规划就绪·待生成」
 * （真库脚印：书 2 的 68 行里 0 行显示「正文已成」，而它有 47 章 DIGESTED）。
 * 现在改走带 `textChars` 的规划读模型，本测试锁住「textChars>0 ⇒ hasText=true」。
 */
class PlanningServiceVolumesTest {

    private static ChapterDataService.ChapterPlanRow row(int no, Integer volNo, String arc, long textChars) {
        return new ChapterDataService.ChapterPlanRow(no, 34L, no, volNo, arc, "第" + no + "章", null, null, null,
                null, 0, 700, 950, textChars > 0 ? "DIGESTED" : "OUTLINED", textChars, "[]", "[]", null, null);
    }

    private PlanningService service(ChapterDataService chapterData) {
        SceneDataService sceneData = mock(SceneDataService.class);
        when(sceneData.countByChapter(anyLong())).thenReturn(0);
        return new PlanningService(null, chapterData, sceneData, null, null, null, null);
    }

    @Test
    void hasTextComesFromTextCharsNotFromSummariesFullText() {
        ChapterDataService chapterData = mock(ChapterDataService.class);
        when(chapterData.listPlanRowsByNovel(34L)).thenReturn(List.of(
                row(1, 1, "导入正文", 1456),      // 导入章：有正文
                row(18, 2, "空船归港", 0)));      // 规划章：无正文

        List<Map<String, Object>> volumes = service(chapterData).volumes(34L);

        assertThat(volumes).hasSize(2);
        Map<String, Object> first = volumes.get(0);
        assertThat(first.get("volNo")).isEqualTo(1);
        assertThat(first.get("arc")).isEqualTo("导入正文");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ch = (List<Map<String, Object>>) first.get("chapters");
        assertThat(ch.get(0).get("hasText")).isEqualTo(true);
        assertThat(ch.get(0).get("textChars")).isEqualTo(1456L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ch2 = (List<Map<String, Object>>) volumes.get(1).get("chapters");
        assertThat(ch2.get(0).get("hasText")).isEqualTo(false);
    }

    @Test
    void chaptersWithoutVolumeGroupUnderZero() {
        ChapterDataService chapterData = mock(ChapterDataService.class);
        when(chapterData.listPlanRowsByNovel(34L)).thenReturn(List.of(row(1, null, null, 900)));

        List<Map<String, Object>> volumes = service(chapterData).volumes(34L);

        assertThat(volumes).hasSize(1);
        assertThat(volumes.get(0).get("volNo")).isEqualTo(0);
        assertThat(volumes.get(0).get("arc")).isEqualTo("未分卷");
    }
}
