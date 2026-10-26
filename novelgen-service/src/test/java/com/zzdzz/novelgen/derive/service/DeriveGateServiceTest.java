package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.vo.derive.GateCriterionVO;
import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P6 立项闸纯函数：结构化判据、Java 裁决、step_status GATE 读写合并与解析校验。 */
class DeriveGateServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void allCriteriaPass_javaIgnoresLegacyModelVerdict() throws Exception {
        var review = DeriveGateService.parseGate(M.readTree(gateJson(true, "ADVISORY",
                "NONE", List.of(), "REJECT")));

        var decision = DeriveGateService.decideGate(review, null, 0);

        assertEquals("GO", decision.verdict());
        assertEquals("PASS", decision.action());
        assertEquals("NONE", decision.repairTarget());
    }

    @Test
    void advisoryProblemDoesNotBlock() throws Exception {
        String json = criteriaJson("budget", false, "ADVISORY", "DRAFT",
                List.of("支线略多但不构成矛盾"));
        var review = DeriveGateService.parseGate(M.readTree(json));

        var decision = DeriveGateService.decideGate(review, null, 0);

        assertEquals("GO", decision.verdict());
        assertEquals("PASS", decision.action());
        assertTrue(decision.issues().stream().anyMatch(s -> s.contains("支线略多")));
    }

    @Test
    void fixableProblemReworksForTwoRoundsThenFails() throws Exception {
        String json = criteriaJson("motif_dedup", false, "FIXABLE", "DRAFT",
                List.of("鞋印嫁祸重复"));
        var review = DeriveGateService.parseGate(M.readTree(json));

        assertEquals("REWORK", DeriveGateService.decideGate(review, null, 0).action());
        assertEquals("REWORK", DeriveGateService.decideGate(review, null, 4).action());
        var exhausted = DeriveGateService.decideGate(review, null, 5);
        assertEquals("FAILED", exhausted.action());
        assertEquals("REJECT", exhausted.verdict());
        assertEquals("DRAFT", exhausted.repairTarget());
    }


    @Test
    void upstreamRepairAlsoFailsAfterFiveRounds() throws Exception {
        String json = criteriaJson("single_rule", false, "HARD", "SPINE", List.of("双轨真相"));
        var review = DeriveGateService.parseGate(M.readTree(json));

        assertEquals("REWORK", DeriveGateService.decideGate(review, null, 4).action());
        assertEquals("FAILED", DeriveGateService.decideGate(review, null, 5).action());
    }    @Test
    void repairTargetUsesUpstreamPriority() throws Exception {
        String json = M.readTree(gateJson(true, "ADVISORY", "NONE", List.of(), null))
                .deepCopy().toString();
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) M.readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("criteria").path("motive_chain"))
                .put("pass", false).put("severity", "FIXABLE").put("repairTarget", "DRAFT");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("criteria").path("closure"))
                .put("pass", false).put("severity", "HARD").put("repairTarget", "PLAN_VOLUME:2");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("criteria").path("single_rule"))
                .put("pass", false).put("severity", "HARD").put("repairTarget", "SPINE");

        var decision = DeriveGateService.decideGate(
                DeriveGateService.parseGate(root), null, 0);

        assertEquals("SPINE", decision.repairTarget());
    }

    @Test
    void planValidationHardIssueForcesRework() throws Exception {
        var review = DeriveGateService.parseGate(
                M.readTree(gateJson(true, "ADVISORY", "NONE", List.of(), null)));
        PlanValidationVO plan = new PlanValidationVO(9L, null, false,
                List.of(new PlanValidationVO.Issue(
                        "ERROR", "KNOWLEDGE_NOT_ACQUIRED", "PLAN_VOLUME:3",
                        3, 28, List.of(55), "角色使用了尚未获得的知识")),
                List.of());

        var decision = DeriveGateService.decideGate(review, plan, 0);

        assertEquals("REWORK", decision.action());
        assertEquals("PLAN_VOLUME:3", decision.repairTarget());
        assertTrue(decision.issues().stream().anyMatch(s -> s.contains("KNOWLEDGE_NOT_ACQUIRED")));
    }

    @Test
    void withGate_mergesAndReadsBack() {
        String base = "{\"v\":1,\"steps\":{\"SAMPLE\":{\"status\":\"DONE\"}}}";
        List<GateCriterionVO> criteria = List.of(
                new GateCriterionVO("budget", true, "ADVISORY", "体量可控",
                        List.of(), "NONE"));
        String after = DeriveGateService.withGate(
                base, "PASS", 1, List.of("毛病一"), "砍支线", "NONE", criteria);
        assertTrue(DeriveGateService.gatePassed(after));
        assertEquals(1, DeriveGateService.gateRound(after));
        assertFalse(after.contains("REWORK"));
        assertTrue(after.contains("SAMPLE"), "既有 steps 键必须保留");
        assertTrue(after.contains("\"criteria\""));
        assertTrue(after.contains("\"budget\""));

        String rework = DeriveGateService.withGate(base, "REWORK", 2, List.of(), "");
        assertFalse(DeriveGateService.gatePassed(rework));
        assertEquals(2, DeriveGateService.gateRound(rework));
    }

    @Test
    void gateReset_invalidatesPass() {
        String passed = DeriveGateService.withGate("{}", "PASS", 1, List.of(), "");
        String reset = DeriveGateService.gateReset(passed);
        assertFalse(DeriveGateService.gatePassed(reset), "⑥b 回炉后旧 PASS 必须作废");
        // run45 实弹：PASS 后被 ⑧ 打回＝全新评审周期，轮次归零；
        // 否则 round=5 的 PASS 会让复评第 6 轮直接熔断。回炉总次数由 qc_round 兜底。
        assertEquals(0, DeriveGateService.gateRound(reset), "PASS 回炉轮次归零");
    }

    @Test
    void gateReset_afterReworkStillPreservesRound() {
        String rework = DeriveGateService.withGate("{}", "REWORK", 4, List.of("问题"), "指令");
        String reset = DeriveGateService.gateReset(rework);
        assertEquals(4, DeriveGateService.gateRound(reset),
                "REWORK 回炉仍保留轮次，防同一矛盾无限重置回第 1 轮");
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
    void parseGate_requiresAllSixCriteriaAndValidTargets() throws Exception {
        DeriveGateService.parseGate(
                M.readTree(gateJson(true, "ADVISORY", "NONE", List.of(), null)));
        assertThrows(LlmJson.Bad.class, () -> DeriveGateService.parseGate(
                M.readTree("{\"criteria\":{},\"issues\":[],\"directive\":\"\"}")));
        assertThrows(LlmJson.Bad.class, () -> DeriveGateService.parseGate(
                M.readTree(gateJson(true, "ADVISORY", "NONE", List.of(), null)
                        .replace("\"severity\":\"ADVISORY\"", "\"severity\":\"MAYBE\""))));
        assertThrows(LlmJson.Bad.class, () -> DeriveGateService.parseGate(
                M.readTree(criteriaJson("closure", false, "HARD", "NOWHERE", List.of("坏目标")))));
        var normalized = DeriveGateService.parseGate(
                M.readTree(gateJson(true, "ADVISORY", "NONE", List.of(), null)
                        .replace("\"issues\":[]", "\"issues\":{}")));
        assertTrue(normalized.criteria().stream().allMatch(c -> c.issues().isEmpty()));
    }


    @Test
    void missingDirectiveAndMalformedCriterionIssuesAreNormalized() throws Exception {
        String raw = gateJson(true, "ADVISORY", "NONE", List.of(), null)
                .replace(",\"directive\":\"\"", "");
        var review = DeriveGateService.parseGate(M.readTree(raw));

        assertEquals("", review.directive());
        assertTrue(review.criteria().stream().allMatch(c -> c.issues().isEmpty()));
    }    @Test
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

    private static String gateJson(boolean pass, String severity, String repairTarget,
                                   List<String> issues, String legacyVerdict) {
        String roots = "single_rule,budget,motive_chain,closure,function_lock,motif_dedup";
        StringBuilder criteria = new StringBuilder("{");
        String[] keys = roots.split(",");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                criteria.append(',');
            }
            criteria.append('"').append(keys[i]).append("\":")
                    .append(criterion(pass, severity, repairTarget, issues));
        }
        criteria.append('}');
        return "{\"criteria\":" + criteria
                + (legacyVerdict == null ? "" : ",\"verdict\":\"" + legacyVerdict + "\"")
                + ",\"issues\":[],\"directive\":\"\"}";
    }

    private static String criteriaJson(String failedKey, boolean pass, String severity,
                                       String repairTarget, List<String> issues) {
        String allPass = gateJson(true, "ADVISORY", "NONE", List.of(), null);
        return allPass.replaceFirst(
                "\"" + failedKey + "\":\\{[^}]*}",
                "\"" + failedKey + "\":" + criterion(pass, severity, repairTarget, issues));
    }

    private static String criterion(boolean pass, String severity, String repairTarget,
                                    List<String> issues) {
        String issueJson = issues.stream()
                .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"pass\":" + pass + ",\"severity\":\"" + severity
                + "\",\"note\":\"测试判据\",\"issues\":[" + issueJson
                + "],\"repairTarget\":\"" + repairTarget + "\"}";
    }
}
