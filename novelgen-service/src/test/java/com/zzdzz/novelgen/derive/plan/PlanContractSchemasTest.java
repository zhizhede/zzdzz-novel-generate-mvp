package com.zzdzz.novelgen.derive.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zzdzz.novelgen.llm.contract.ContractViolation;
import com.zzdzz.novelgen.llm.contract.JsonSchemaSupport;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROUTE v2 JSON Schema 视图的枚举与封闭性。 */
class PlanContractSchemasTest {

    @Test
    void schemaUsesAllStateChangeKinds() {
        assertEquals(
                enumNames(PlanContracts.StateChangeKind.values()),
                texts(stateKindSchema()));
    }

    @Test
    void schemaUsesAllThreadEffectKinds() {
        assertEquals(
                enumNames(PlanContracts.ThreadEffectKind.values()),
                texts(threadEffectSchema()));
    }

    @Test
    void schemaRejectsLegacyChapterNoAndSeqFields() {
        JsonNode root = JsonNodeFactory.instance.objectNode()
                .put("chapterNo", 1)
                .put("seq", 7);

        List<ContractViolation> violations =
                JsonSchemaSupport.validate(PlanContractSchemas.routeNarrative().schemaView(), root);

        assertTrue(violations.stream().anyMatch(v ->
                "SCHEMA_FIELD_INVALID".equals(v.code()) && "$.chapterNo".equals(v.path())),
                violations::toString);
        assertTrue(violations.stream().anyMatch(v ->
                "SCHEMA_FIELD_INVALID".equals(v.code()) && "$.seq".equals(v.path())),
                violations::toString);
    }

    @Test
    void schemaRejectsLegacyBeatRoleField() {
        JsonNode root = JsonNodeFactory.instance.objectNode().put("beatRole", "HOOK");

        List<ContractViolation> violations =
                JsonSchemaSupport.validate(PlanContractSchemas.routeNarrative().schemaView(), root);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v ->
                "SCHEMA_FIELD_INVALID".equals(v.code()) && "$.beatRole".equals(v.path())),
                violations::toString);
    }

    @Test
    void schemaRejectsAdditionalProperties() {
        assertClosedObjects(PlanContractSchemas.routeNarrative().schemaView());
    }

    private static JsonNode stateKindSchema() {
        return PlanContractSchemas.routeNarrative().schemaView()
                .path("properties").path("beatNarratives").path("items")
                .path("properties").path("stateEffects").path("items")
                .path("properties").path("kind").path("enum");
    }

    private static JsonNode threadEffectSchema() {
        return PlanContractSchemas.routeNarrative().schemaView()
                .path("properties").path("beatNarratives").path("items")
                .path("properties").path("threadEffects").path("items")
                .path("properties").path("effect").path("enum");
    }

    private static List<String> enumNames(PlanContracts.StateChangeKind[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toList());
    }

    private static List<String> enumNames(PlanContracts.ThreadEffectKind[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toList());
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.asText());
        }
        return values;
    }

    private static void assertClosedObjects(JsonNode node) {
        if (node.isObject()) {
            if (node.has("properties")) {
                assertEquals(JsonNodeFactory.instance.booleanNode(false),
                        node.get("additionalProperties"),
                        "object schema must set additionalProperties=false: " + node);
            }
            node.forEach(PlanContractSchemasTest::assertClosedObjects);
        } else if (node.isArray()) {
            node.forEach(PlanContractSchemasTest::assertClosedObjects);
        }
    }
}