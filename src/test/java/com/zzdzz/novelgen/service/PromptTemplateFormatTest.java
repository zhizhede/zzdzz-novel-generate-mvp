package com.zzdzz.novelgen.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PromptTemplateService 纯函数行为：fail-open 格式化与占位符校验。 */
class PromptTemplateFormatTest {

    @Test
    void validTemplateIsUsed() {
        String out = PromptTemplateService.formatSafe("第 %d 章《%s》", "FALLBACK %s", "test", 3, "标题");
        assertEquals("第 3 章《标题》", out);
    }

    @Test
    void brokenPlaceholderFallsBackToCode() {
        // %d 配字符串参数必然抛 IllegalFormatConversionException → 回退代码模板
        String out = PromptTemplateService.formatSafe("第 %d 章", "代码模板 %s", "test", "不是数字");
        assertEquals("代码模板 不是数字", out);
    }

    @Test
    void missingPlaceholderFallsBack() {
        // 模板占位符比参数多 → MissingFormatArgumentException → 回退
        String out = PromptTemplateService.formatSafe("两个 %s %s", "代码 %s", "test", "a");
        assertEquals("代码 a", out);
    }

    @Test
    void specsExtractedInOrder() {
        assertEquals(List.of("%d", "%s", "%d", "%%"),
                PromptTemplateService.specs("第 %d 章 %s 预算 %d 字（增长 %%）"));
    }
}
