package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 知识事实粗归一：仅用于匹配此前已经写入的相近事实。 */
class DerivePlanKnowledgeRepairTest {

    @Test
    void factMatchesReportVariants() {
        assertTrue(DerivePlanService.factMatches(
                "C2在公共餐饮处完成详细报告，证据成型",
                "C2完成的详细报告内容"));
        assertFalse(DerivePlanService.factMatches(
                "C2提交财务报表",
                "C2完成的详细报告内容"));
    }
}