package com.zzdzz.novelgen.derive.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.compiler.RouteNarrativeContract;
import com.zzdzz.novelgen.llm.contract.ContractViolation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史 ROUTE 输出回放：坏输出必须得到稳定的契约错误码，不能被旧的 seq/filled 口径误分类。
 */
class HistoricalRouteReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RouteNarrativeContract.RouteContractKeys KEYS =
            new RouteNarrativeContract.RouteContractKeys(Set.of("C1"), Set.of("T1"), Set.of("B01"));

    @Test
    void run44C1DuplicateFailsWithSchemaViolation() {
        assertHistoricalRejected("derive-replay/run44-c1-duplicate.json");
    }

    @Test
    void run60ObstacleFailsWithSchemaViolation() {
        assertHistoricalRejected("derive-replay/run60-route-obstacle.json");
    }

    @Test
    void run60MidpointFailsWithSchemaViolation() {
        assertHistoricalRejected("derive-replay/run60-route-midpoint.json");
    }

    @Test
    void run60C1DuplicateIsClassifiedWithoutSeqOrFilledCode() {
        List<ContractViolation> violations = parse("derive-replay/run60-route-c1-duplicate.json");
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> "SCHEMA_FIELD_INVALID".equals(v.code())),
                () -> text(violations));
        assertTrue(violations.stream().noneMatch(v -> v.code().contains("SEQ")
                || v.code().contains("FILLED")),
                () -> "legacy seq/filled classification leaked: " + text(violations));
    }

    @Test
    void validRoutePassesReplay() {
        ReplayFixtureLoader.ReplayFixture fixture = ReplayFixtureLoader.load("derive-replay/route-valid.json");
        JsonNode root = readRaw(fixture);
        RouteNarrativeContract.ParseResult result = RouteNarrativeContract.parse(root, KEYS);
        assertTrue(result.valid(), () -> text(result.violations()));
    }

    private static void assertHistoricalRejected(String resource) {
        List<ContractViolation> violations = parse(resource);
        assertFalse(violations.isEmpty(), resource + " should be rejected");
        assertTrue(violations.stream().anyMatch(v ->
                        "SCHEMA_FIELD_INVALID".equals(v.code())
                                || "SCHEMA_ENUM_INVALID".equals(v.code())),
                () -> resource + ": " + text(violations));
    }

    private static List<ContractViolation> parse(String resource) {
        JsonNode root = readRaw(ReplayFixtureLoader.load(resource));
        return RouteNarrativeContract.parse(root, KEYS).violations();
    }

    private static JsonNode readRaw(ReplayFixtureLoader.ReplayFixture fixture) {
        try {
            return MAPPER.readTree(fixture.rawContent());
        } catch (Exception e) {
            throw new AssertionError("fixture rawContent is not valid JSON: " + fixture.id(), e);
        }
    }

    private static String text(List<ContractViolation> violations) {
        return violations.stream().map(ContractViolation::feedbackLine)
                .collect(Collectors.joining("; "));
    }
}


