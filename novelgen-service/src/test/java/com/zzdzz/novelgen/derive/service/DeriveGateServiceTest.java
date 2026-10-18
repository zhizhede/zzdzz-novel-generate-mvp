package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P6 立项闸纯函数：verdict→动作、step_status GATE 读写合并、回炉作废、解析校验。 */
class DeriveGateServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void verdictMapsToAction_withRoundCap() {
        assertEquals("PASS", DeriveGateService.gateAction("GO", 0));
        assertEquals("REWORK", DeriveGateService.gateAction("REWORK", 0));
        assertEquals("REWORK", DeriveGateService.gateAction("REWORK", 1));
        assertEquals("FAILED", DeriveGateService.gateAction("REWORK", 2), "prevRound≥2 熔断");
        assertEquals("FAILED", DeriveGateService.gateAction("REJECT", 0));
        assertThrows(Exception.class, () -> DeriveGateService.gateAction("MAYBE", 0));
    }

    @Test
    void withGate_mergesAndReadsBack() {
        String base = "{\"v\":1,\"steps\":{\"SAMPLE\":{\"status\":\"DONE\"}}}";
        String after = DeriveGateService.withGate(base, "PASS", 1, List.of("毛病一"), "砍支线");
        assertTrue(DeriveGateService.gatePassed(after));
        assertEquals(1, DeriveGateService.gateRound(after));
        assertFalse(after.contains("REWORK"));
        assertTrue(after.contains("SAMPLE"), "既有 steps 键必须保留");

        String rework = DeriveGateService.withGate(base, "REWORK", 2, List.of(), "");
        assertFalse(DeriveGateService.gatePassed(rework));
        assertEquals(2, DeriveGateService.gateRound(rework));
    }

    @Test
    void gateReset_invalidatesPass() {
        String passed = DeriveGateService.withGate("{}", "PASS", 1, List.of(), "");
        String reset = DeriveGateService.gateReset(passed);
        assertFalse(DeriveGateService.gatePassed(reset), "⑥b 回炉后旧 PASS 必须作废");
        assertEquals(1, DeriveGateService.gateRound(reset), "轮次保留供熔断计数");
    }

    @Test
    void gateReset_preservesDirectiveForRedo() {
        // 2026-10-18 修：回炉重做必须按减法指令执行——reset 只清 status，directive/issues/轮次都保留
        String rework = DeriveGateService.withGate("{}", "REWORK", 1, List.of("事件超载"), "砍机关、换嫁祸");
        String reset = DeriveGateService.gateReset(rework);
        assertFalse(DeriveGateService.gatePassed(reset));
        assertEquals("砍机关、换嫁祸", DeriveGateService.gateDirective(reset), "回炉后指令必须还在，供重做注入");
        assertTrue(reset.contains("事件超载"), "issues 同步保留");
    }

    @Test
    void parseGate_validatesVerdictAndShape() throws Exception {
        Json(M.readTree("{\"verdict\":\"GO\",\"issues\":[],\"directive\":\"\"}"));
        assertThrows(LlmJson.Bad.class, () -> Json(M.readTree("{\"verdict\":\"YES\",\"issues\":[],\"directive\":\"\"}")));
        assertThrows(LlmJson.Bad.class, () -> Json(M.readTree("{\"verdict\":\"GO\",\"issues\":{},\"directive\":\"\"}")));
        assertThrows(LlmJson.Bad.class, () -> Json(M.readTree("{\"verdict\":\"GO\",\"issues\":[]}")));
    }

    private static void Json(com.fasterxml.jackson.databind.JsonNode n) {
        DeriveGateService.parseGate(n);
    }

    @Test
    void emptyAndBrokenStepStatusAreSafe() {
        assertFalse(DeriveGateService.gatePassed(null));
        assertFalse(DeriveGateService.gatePassed("not-json"));
        assertEquals(0, DeriveGateService.gateRound("{\"v\":1,\"steps\":{}}"));
    }

    @Test
    void antiReproDefaultsOn() {
        assertTrue(DeriveSampleService.antiReproDefaulted(null), "2026-10-17 拍板：缺省=开");
        assertTrue(DeriveSampleService.antiReproDefaulted(true));
        assertFalse(DeriveSampleService.antiReproDefaulted(false), "显式 false 仍可关");
    }
}
