package com.zzdzz.novelgen.derive.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** run.step_status.steps.PLAN_V2 的读写适配。 */
public final class PlanV2StateCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanV2StateCodec() {
    }

    public static PlanContracts.PlanV2State initial() {
        return new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.SCALE, List.of(), null, Map.of());
    }

    public static PlanContracts.PlanV2State read(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "null" : json);
            if (node == null || !node.isObject()) {
                return initial();
            }
            return MAPPER.treeToValue(node, PlanContracts.PlanV2State.class);
        } catch (Exception e) {
            return initial();
        }
    }

    public static String write(PlanContracts.PlanV2State state) {
        try {
            return MAPPER.writeValueAsString(state == null ? initial() : state);
        } catch (Exception e) {
            throw new IllegalStateException("PLAN_V2 状态序列化失败", e);
        }
    }
}
