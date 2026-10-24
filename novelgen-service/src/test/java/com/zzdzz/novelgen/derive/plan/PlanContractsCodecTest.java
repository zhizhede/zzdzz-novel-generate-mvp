package com.zzdzz.novelgen.derive.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PLAN_V2 状态合同编解码。 */
class PlanContractsCodecTest {

    @Test
    void planV2StateRoundTripsAndPreservesWireValues() {
        var state = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.SPINE,
                List.of(PlanContracts.PlanUnit.SCALE),
                new PlanContracts.ScopeContract(
                        PlanContracts.ScopeDecision.MULTI_VOLUME,
                        4,
                        48,
                        2800,
                        List.of("显式四卷")),
                Map.of(1, PlanContracts.PlanUnit.SLOT_ROUTE));

        String json = PlanV2StateCodec.write(state);

        assertEquals(state, PlanV2StateCodec.read(json));
        assertTrue(json.contains("\"MULTI_VOLUME\""));
        assertTrue(json.contains("\"SLOT_ROUTE\""));
    }

    @Test
    void malformedStateFallsBackToScale() {
        assertNotNull(PlanV2StateCodec.read("{broken"));
        assertEquals(PlanContracts.PlanUnit.SCALE,
                PlanV2StateCodec.read("null").currentUnit());
    }
}
