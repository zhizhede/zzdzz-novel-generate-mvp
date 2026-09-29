package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.data.PromptTemplateDataService;
import com.zzdzz.novelgen.model.dto.PromptTemplateDTO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 库值优先与 fail-open：enabled 行覆盖目录模板；禁用/损坏行回退 PromptCatalog 目录正文。
 * 回退源是目录（reader_review/system 含 %s 软/硬阈值两个占位）——调用点不再传显式回退文本。
 */
class PromptTemplateGetTest {

    private static PromptTemplateDTO row(String content, boolean enabled) {
        PromptTemplateDTO row = new PromptTemplateDTO();
        row.setId(1L);
        row.setNode("reader_review");
        row.setPhase("system");
        row.setTitle("读者评审五问");
        row.setContent(content);
        row.setExact(true);
        row.setVersion(1);
        row.setCustom(false);
        row.setEnabled(enabled);
        row.setUpdateTime(OffsetDateTime.now());
        return row;
    }

    @Test
    void enabledDbRowWinsOverCatalogFallback() {
        PromptTemplateDataService dao = mock(PromptTemplateDataService.class);
        when(dao.findAll()).thenReturn(List.of(row("库内模板：%s/%s", true)));
        PromptTemplateService s = new PromptTemplateService(dao);
        assertEquals("库内模板：0.3/0.5", s.format("reader_review", "system", 0.3, 0.5));
    }

    @Test
    void disabledRowFallsBackToCatalog() {
        PromptTemplateDataService dao = mock(PromptTemplateDataService.class);
        when(dao.findAll()).thenReturn(List.of(row("库内模板：%s/%s", false)));
        PromptTemplateService s = new PromptTemplateService(dao);
        String out = s.format("reader_review", "system", 0.3, 0.5);
        assertTrue(out.startsWith("你是一个没耐心的网文读者"), "应回退目录正文");
        assertTrue(out.contains("0.3") && out.contains("0.5"), "阈值应已填入");
    }

    @Test
    void corruptedDbRowFallsBackAtFormatTime() {
        PromptTemplateDataService dao = mock(PromptTemplateDataService.class);
        // 库内模板占位符类型坏了（%d 收到 double 必炸）→ 运行时回退目录正文
        when(dao.findAll()).thenReturn(List.of(row("坏了 %d %d", true)));
        PromptTemplateService s = new PromptTemplateService(dao);
        String out = s.format("reader_review", "system", 0.3, 0.5);
        assertTrue(out.startsWith("你是一个没耐心的网文读者"), "应回退目录正文");
    }

    @Test
    void missingCatalogEntryFailsFast() {
        PromptTemplateDataService dao = mock(PromptTemplateDataService.class);
        when(dao.findAll()).thenReturn(List.of());
        PromptTemplateService s = new PromptTemplateService(dao);
        assertThrows(IllegalStateException.class, () -> s.get("no_such_node", "no_such_phase"));
    }
}
