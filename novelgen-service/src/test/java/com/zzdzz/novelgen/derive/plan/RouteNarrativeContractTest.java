package com.zzdzz.novelgen.derive.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.derive.plan.compiler.RouteNarrative;
import com.zzdzz.novelgen.derive.plan.compiler.RouteNarrativeContract;
import com.zzdzz.novelgen.llm.contract.ContractViolation;
import com.zzdzz.novelgen.llm.contract.NodeOutputContractRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROUTE v2 wire contract 的结构与上下文校验。 */
class RouteNarrativeContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RouteNarrativeContract.RouteContractKeys KEYS =
            new RouteNarrativeContract.RouteContractKeys(
                    Set.of("C1"),
                    Set.of("T1"),
                    Set.of("B01"));

    @Test
    void parsesHistoricalRouteFixture() {
        RouteNarrativeContract.ParseResult result = parse(validRoot());

        assertTrue(result.valid(), () -> violationsText(result.violations()));
        RouteNarrative narrative = result.value();
        assertEquals(1, narrative.beatNarratives().size());
        RouteNarrative.BeatNarrative beat = narrative.beatNarratives().get(0);
        assertEquals("B01", beat.beatKey());
        assertNotNull(beat.knowledgeEffects());
        assertNotNull(beat.stateEffects());
        assertNotNull(beat.threadEffects());
        assertTrue(beat.stateEffects().isEmpty());
        assertTrue(narrative.skipped().isEmpty());
    }

    @Test
    void rejectsUnknownCharacter() {
        JsonNode root = validRoot();
        ((ObjectNode) root.path("beatNarratives").get(0)
                .path("knowledgeEffects").get(0)).putArray("characterKeys").add("X9");

        RouteNarrativeContract.ParseResult result = RouteNarrativeContract.parse(root, KEYS);

        assertFalse(result.valid());
        assertViolation(result.violations(), "DOMAIN_UNKNOWN_CHARACTER",
                "$.beatNarratives[0].knowledgeEffects[0].characterKeys[0]");
    }

    @Test
    void rejectsUnknownThread() {
        JsonNode root = validRoot();
        ObjectNode beat = (ObjectNode) root.path("beatNarratives").get(0);
        ObjectNode threadEffect = beat.putArray("threadEffects").addObject();
        threadEffect.put("threadKey", "T9");
        threadEffect.put("effect", "OPEN");
        threadEffect.put("detail", "线索首次出现");

        RouteNarrativeContract.ParseResult result = RouteNarrativeContract.parse(root, KEYS);

        assertFalse(result.valid());
        assertViolation(result.violations(), "DOMAIN_UNKNOWN_THREAD",
                "$.beatNarratives[0].threadEffects[0].threadKey");
    }

    @Test
    void rejectsUnknownBeatKey() {
        JsonNode root = validRoot();
        ((ObjectNode) root.path("beatNarratives").get(0)).put("beatKey", "B99");

        RouteNarrativeContract.ParseResult result = RouteNarrativeContract.parse(root, KEYS);

        assertFalse(result.valid());
        assertViolation(result.violations(), "DOMAIN_UNKNOWN_BEAT_KEY",
                "$.beatNarratives[0].beatKey");
    }

    @Test
    void rejectsDuplicateBeatKey() {
        JsonNode root = validRoot();
        ((ArrayNode) root.path("beatNarratives"))
                .add(root.path("beatNarratives").get(0).deepCopy());

        RouteNarrativeContract.ParseResult result = RouteNarrativeContract.parse(root, KEYS);

        assertFalse(result.valid());
        assertViolation(result.violations(), "DOMAIN_DUPLICATE_BEAT_KEY",
                "$.beatNarratives[1].beatKey");
    }

    @Test
    void rejectsLegacyBeatRoleField() {
        JsonNode root = validRoot();
        ((ObjectNode) root).put("beatRole", "HOOK");

        RouteNarrativeContract.ParseResult result = RouteNarrativeContract.parse(root, KEYS);

        assertFalse(result.valid());
        assertViolation(result.violations(), "SCHEMA_FIELD_INVALID", "$.beatRole");
    }

    @Test
    void rejectsUnknownStateChangeKind() {
        JsonNode root = validRoot();
        ObjectNode beat = (ObjectNode) root.path("beatNarratives").get(0);
        ObjectNode stateEffect = beat.putArray("stateEffects").addObject();
        stateEffect.put("characterKey", "C1");
        stateEffect.put("kind", "MIDPOINT");
        stateEffect.put("detail", "状态改变");

        RouteNarrativeContract.ParseResult result = RouteNarrativeContract.parse(root, KEYS);

        assertFalse(result.valid());
        assertViolation(result.violations(), "SCHEMA_ENUM_INVALID",
                "$.beatNarratives[0].stateEffects[0].kind");
    }

    @Test
    void rejectsUnknownThreadEffectKind() {
        JsonNode root = validRoot();
        ObjectNode beat = (ObjectNode) root.path("beatNarratives").get(0);
        ObjectNode threadEffect = beat.putArray("threadEffects").addObject();
        threadEffect.put("threadKey", "T1");
        threadEffect.put("effect", "BOOM");
        threadEffect.put("detail", "线程改变");

        RouteNarrativeContract.ParseResult result = RouteNarrativeContract.parse(root, KEYS);

        assertFalse(result.valid());
        assertViolation(result.violations(), "SCHEMA_ENUM_INVALID",
                "$.beatNarratives[0].threadEffects[0].effect");
    }

    @Test
    void registryContainsRouteNarrativeV2() {
        NodeOutputContractRegistry registry = new NodeOutputContractRegistry();
        new PlanContractConfiguration(registry);

        var contract = registry.require(
                PlanContractSchemas.ROUTE_NARRATIVE_ID,
                PlanContractSchemas.ROUTE_NARRATIVE_VERSION);

        assertEquals(PlanContractSchemas.ROUTE_NARRATIVE_ID, contract.contractId());
        assertEquals(PlanContractSchemas.ROUTE_NARRATIVE_VERSION, contract.schemaVersion());
        assertNotNull(contract.schemaView());
    }

    private static RouteNarrativeContract.ParseResult parse(JsonNode root) {
        return RouteNarrativeContract.parse(root, KEYS);
    }

    private static void assertViolation(List<ContractViolation> violations,
                                        String code, String path) {
        assertTrue(violations.stream().anyMatch(v -> code.equals(v.code()) && path.equals(v.path())),
                () -> "missing " + code + " at " + path + "; got " + violationsText(violations));
    }

    private static String violationsText(List<ContractViolation> violations) {
        return violations.stream()
                .map(ContractViolation::feedbackLine)
                .collect(Collectors.joining("; "));
    }

    private static JsonNode validRoot() {
        try (InputStream in = RouteNarrativeContractTest.class
                .getResourceAsStream("/derive-replay/route-valid.json")) {
            if (in == null) {
                throw new AssertionError("missing test resource derive-replay/route-valid.json");
            }
            JsonNode envelope = MAPPER.readTree(in.readAllBytes());
            return MAPPER.readTree(envelope.path("rawContent").asText());
        } catch (IOException e) {
            throw new AssertionError("cannot load route-valid.json", e);
        }
    }
}