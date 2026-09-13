package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.dao.PromptTemplateDAO;
import com.zzdzz.novelgen.model.entity.PromptTemplateDO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 库值优先与 fail-open：enabled 行覆盖代码模板；禁用/损坏行回退代码模板。 */
class PromptTemplateGetTest {

    private static PromptTemplateDO row(String content, boolean enabled) {
        return new PromptTemplateDO(1L, "outline", "user", "AI 章纲生成", content,
                true, 1, false, enabled, false, OffsetDateTime.now());
    }

    @Test
    void enabledDbRowWinsOverCodeFallback() {
        PromptTemplateDAO dao = mock(PromptTemplateDAO.class);
        when(dao.findAll()).thenReturn(List.of(row("库内模板：第 %s 章", true)));
        PromptTemplateService s = new PromptTemplateService(dao);
        assertEquals("库内模板：第 7 章",
                s.format("outline", "user", "代码模板：第 %s 章", "7"));
    }

    @Test
    void disabledRowFallsBackToCode() {
        PromptTemplateDAO dao = mock(PromptTemplateDAO.class);
        when(dao.findAll()).thenReturn(List.of(row("库内模板：第 %s 章", false)));
        PromptTemplateService s = new PromptTemplateService(dao);
        assertEquals("代码模板：第 7 章",
                s.format("outline", "user", "代码模板：第 %s 章", "7"));
    }

    @Test
    void corruptedDbRowFallsBackAtFormatTime() {
        PromptTemplateDAO dao = mock(PromptTemplateDAO.class);
        // 库内模板占位符类型坏了（%s 收到 int 必炸）→ 运行时回退代码模板
        when(dao.findAll()).thenReturn(List.of(row("坏了 %s %s", true)));
        PromptTemplateService s = new PromptTemplateService(dao);
        assertEquals("代码模板：第 7 章",
                s.format("outline", "user", "代码模板：第 %s 章", 7));
    }
}
