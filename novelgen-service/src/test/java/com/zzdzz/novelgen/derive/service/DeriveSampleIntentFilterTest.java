package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveSampleIntentFilterTest {

    @Test
    void explicitRedLinesAreExtracted() {
        List<String> redLines = DeriveSampleService.intentRedLines(
                "现代都市单案短篇：只有三名角色，地点限于旧楼和走廊，无死亡、超自然、第二案件。");

        assertTrue(redLines.contains("死亡"));
        assertTrue(redLines.contains("超自然"));
        assertTrue(redLines.contains("第二案件"));
        assertTrue(redLines.contains("珍品"));
        assertTrue(redLines.contains("街市"));
    }

    @Test
    void negatedRedLinesDoNotRejectWeldText() {
        List<String> redLines = DeriveSampleService.intentRedLines("无死亡、无超自然");

        assertNull(DeriveSampleService.firstForbiddenTerm("本故事无死亡、无超自然", redLines));
    }

    @Test
    void incompatibleOutlineRowsAreFiltered() {
        var bad = DeriveSampleService.Row.of(1, "O-1", "武装勒索与密室囚禁", null, label());
        var good = DeriveSampleService.Row.of(2, "O-2", "旧楼走廊里的物证比对", null, label());

        var rows = DeriveSampleService.filterIntentRows(
                List.of(bad, good), List.of("密室", "武装"));

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).id());
    }

    private static LabelIndexDO label() {
        LabelIndexDO row = new LabelIndexDO();
        row.setCat("{}");
        row.setPacing(new BigDecimal("0.5"));
        row.setBeatType("事件");
        row.setArcFit("{}");
        return row;
    }
}