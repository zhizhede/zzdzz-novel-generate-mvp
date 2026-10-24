package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FactLedger 纯函数测试。 */
class DeriveFactServiceTest {

    @Test
    void factKeyIsStableAndPrefixed() {
        String first = DeriveFactService.factKey("C2完成详细报告");
        String second = DeriveFactService.factKey("C2完成详细报告");

        assertEquals(first, second);
        assertTrue(first.startsWith("F-"));
        assertEquals(26, first.length());
    }

    @Test
    void factKeyDiffersForDifferentFacts() {
        assertNotEquals(
                DeriveFactService.factKey("C2完成详细报告"),
                DeriveFactService.factKey("C3确认监控缺帧"));
    }
}