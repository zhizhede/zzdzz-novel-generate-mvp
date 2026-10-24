package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 收尾：闭合义务从 ⑥a 规划传到 ⑧ 清算——
 * 逐窗分章规则 5（推理/悬疑+闭合 ⇒ 末章收束拍承载真相了结）与 ⑧ logic④ 硬条件不被删。
 */
class DeriveClosureContractTest {

    @Test
    void planPrompt_requiresVerdictSemanticsForMysteryClosedEndings() {
        String p = DerivePlanService.PLAN_WINDOW_PROMPT;
        assertTrue(p.contains("真相了结"), "收束拍须承载真相了结语义");
        assertTrue(p.contains("纯氛围收束拍充数"), "禁氛围拍充数");
        assertTrue(p.contains("推理"), "按推理标签分支");
        assertTrue(p.contains("末章"), "末章闭合义务在场");
    }

    @Test
    void qcPrompt_logicHardensTruthCardAccounting() {
        String p = DeriveQcService.QC_PROMPT;
        assertTrue(p.contains("按卡硬对账"), "logic④ 升级为硬对账");
        assertTrue(p.contains("pass=false"), "缺项直接 fail");
        assertTrue(p.contains("判决后情绪余韵尾"), "余韵尾合法通行证在场");
        assertTrue(p.contains("悬空"), "次级钩子悬空纳入对账");
    }
}
