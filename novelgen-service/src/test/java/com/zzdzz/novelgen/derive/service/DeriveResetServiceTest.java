package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanV2StateCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P6 根因回炉的状态保留口径。 */
class DeriveResetServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void spineResetKeepsScaleAndGateRoundButDropsDownstreamPlan() throws Exception {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 12, 2600,
                List.of("单案短篇"));
        PlanContracts.PlanV2State done = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.DONE,
                List.of(PlanContracts.PlanUnit.SCALE, PlanContracts.PlanUnit.SPINE),
                scope, Map.of(1, PlanContracts.PlanUnit.VALIDATE));
        String status = M.writeValueAsString(Map.of("v", 1, "steps", Map.of(
                "PLAN_V2", done,
                "STORY_SPINE", Map.of("mainMystery", "旧脊柱"),
                "TRUTH_CONTRACT", Map.of("truthId", "T-old"),
                "DRAFT", Map.of("batch", 2),
                "GATE", Map.of(
                        "status", "REWORK",
                        "round", 1,
                        "directive", "重建角色弧",
                        "repairTarget", "SPINE",
                        "issues", List.of("主线矛盾"),
                        "criteria", Map.of()))));

        String reset = DeriveResetService.resetPlanState(status, "SPINE");
        PlanContracts.PlanV2State state = PlanV2StateCodec.read(
                M.readTree(reset).path("steps").path("PLAN_V2").toString());

        assertEquals(PlanContracts.PlanUnit.SPINE, state.currentUnit());
        assertEquals(scope, state.scope());
        assertTrue(state.volumeUnits().isEmpty());
        assertEquals(1, M.readTree(reset).path("steps").path("GATE").path("round").asInt(), "根因回炉保留轮次，禁止无限重置");
        assertEquals("重建角色弧",
                M.readTree(reset).path("steps").path("GATE").path("directive").asText());
        assertTrue(M.readTree(reset).path("steps").path("STORY_SPINE").isMissingNode());
        assertEquals("T-old", M.readTree(reset).path("steps").path("TRUTH_CONTRACT").path("truthId").asText(),
                "真相合同跨根因回炉保留");
        assertTrue(M.readTree(reset).path("steps").path("DRAFT").isMissingNode());
    }

    @Test
    void scaleResetDropsScope() throws Exception {
        String status = """
                {"v":1,"steps":{"PLAN_V2":{
                  "currentUnit":"DONE","completedUnits":["SCALE"],
                  "scope":{"decision":"SINGLE_VOLUME","volumeCount":1,
                    "chapterCount":12,"chapterAvg":2600,"evidence":[]},
                  "volumeUnits":{"1":"VALIDATE"}
                }}}
                """;

        String reset = DeriveResetService.resetPlanState(status, "SCALE");
        PlanContracts.PlanV2State state = PlanV2StateCodec.read(
                M.readTree(reset).path("steps").path("PLAN_V2").toString());

        assertEquals(PlanContracts.PlanUnit.SCALE, state.currentUnit());
        assertEquals(null, state.scope());
    }
}
