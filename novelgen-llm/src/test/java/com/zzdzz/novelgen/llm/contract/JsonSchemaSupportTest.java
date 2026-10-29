package com.zzdzz.novelgen.llm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class JsonSchemaSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private enum SampleRole {
        HOOK,
        PAYOFF
    }

    private static JsonNode readSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["kind", "items"],
                      "properties": {
                        "kind": {"type": "string", "enum": ["HOOK", "PAYOFF"]},
                        "label": {"type": "string", "minLength": 2, "maxLength": 5},
                        "score": {"type": "integer", "minimum": 0, "maximum": 10},
                        "code": {"type": "string", "pattern": "^[A-Z]+$"},
                        "items": {
                          "type": "array",
                          "minItems": 1,
                          "maxItems": 3,
                          "items": {"type": "string"}
                        },
                        "beatNarratives": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "required": ["stateEffects"],
                            "properties": {
                              "stateEffects": {
                                "type": "array",
                                "items": {
                                  "type": "object",
                                  "additionalProperties": false,
                                  "required": ["kind"],
                                  "properties": {
                                    "kind": {"type": "string", "enum": ["LOCATION", "PRESENT"]}
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("测试 schema 构造失败", e);
        }
    }

    private static JsonNode validValue() {
        try {
            return MAPPER.readTree("""
                    {
                      "kind": "HOOK",
                      "label": "ok",
                      "score": 5,
                      "code": "ABC",
                      "items": ["a"],
                      "beatNarratives": [{"stateEffects": [{"kind": "LOCATION"}]}]
                    }
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("测试样例构造失败", e);
        }
    }

    private static ContractViolation singleViolation(List<ContractViolation> violations) {
        assertEquals(1, violations.size(), "期望恰好一条违规：" + violations);
        return violations.get(0);
    }

    @Test
    void enumArrayPreservesDeclarationOrder() {
        List<String> names = Arrays.stream(SampleRole.values()).map(Enum::name).toList();

        ArrayNode array = JsonSchemaSupport.enumArray(names);

        assertEquals("[\"HOOK\",\"PAYOFF\"]", array.toString());
        List<String> rendered = IntStream.range(0, array.size())
                .mapToObj(i -> array.get(i).asText())
                .toList();
        assertEquals(List.of("HOOK", "PAYOFF"), rendered);
    }

    @Test
    void bulletListPutsEachValueOnItsOwnLine() {
        List<String> names = Arrays.stream(SampleRole.values()).map(Enum::name).toList();

        String bulletList = JsonSchemaSupport.bulletList(names);

        assertEquals(List.of("- HOOK", "- PAYOFF"), List.of(bulletList.split("\n", -1)));
    }

    @Test
    void validSamplePasses() {
        List<ContractViolation> violations = JsonSchemaSupport.validate(readSchema(), validValue());

        assertTrue(violations.isEmpty(), "合法样例不应有违规：" + violations);
    }

    @Test
    void unknownFieldIsRejectedWithAccuratePath() {
        ObjectNode value = validValue().deepCopy();
        value.put("extra", true);

        ContractViolation violation = singleViolation(JsonSchemaSupport.validate(readSchema(), value));

        assertEquals("SCHEMA_FIELD_INVALID", violation.code());
        assertEquals("$.extra", violation.path());
    }

    @Test
    void unknownEnumValueIsRejectedWithAllowedValues() {
        ObjectNode value = validValue().deepCopy();
        value.put("kind", "MIDPOINT");

        ContractViolation violation = singleViolation(JsonSchemaSupport.validate(readSchema(), value));

        assertEquals("SCHEMA_ENUM_INVALID", violation.code());
        assertEquals("$.kind", violation.path());
        assertTrue(violation.expected().contains("HOOK"), violation.expected());
        assertTrue(violation.expected().contains("PAYOFF"), violation.expected());
        assertEquals("MIDPOINT", violation.actual());
    }

    @Test
    void missingRequiredFieldIsRejectedWithAccuratePath() {
        ObjectNode value = validValue().deepCopy();
        value.remove("kind");

        ContractViolation violation = singleViolation(JsonSchemaSupport.validate(readSchema(), value));

        assertEquals("SCHEMA_FIELD_INVALID", violation.code());
        assertEquals("$.kind", violation.path());
        assertEquals("missing", violation.actual());
    }

    @Test
    void nestedArrayPathIsAccurate() {
        ObjectNode value = validValue().deepCopy();
        ArrayNode beats = value.putArray("beatNarratives");
        for (int i = 0; i < 4; i++) {
            beats.add(readNode("{\"stateEffects\": [{\"kind\": \"PRESENT\"}]}"));
        }
        beats.add(readNode("{\"stateEffects\": [{\"kind\": \"MIDPOINT\"}]}"));

        ContractViolation violation = singleViolation(JsonSchemaSupport.validate(readSchema(), value));

        assertEquals("SCHEMA_ENUM_INVALID", violation.code());
        assertEquals("$.beatNarratives[4].stateEffects[0].kind", violation.path());
        assertEquals("MIDPOINT", violation.actual());
    }

    @Test
    void arrayBoundsAreEnforced() {
        ObjectNode tooMany = validValue().deepCopy();
        tooMany.putArray("items").add("a").add("b").add("c").add("d");
        ContractViolation maxViolation = singleViolation(JsonSchemaSupport.validate(readSchema(), tooMany));
        assertEquals("SCHEMA_FIELD_INVALID", maxViolation.code());
        assertEquals("$.items", maxViolation.path());
        assertTrue(maxViolation.expected().contains("3"), maxViolation.expected());

        ObjectNode empty = validValue().deepCopy();
        empty.putArray("items");
        ContractViolation minViolation = singleViolation(JsonSchemaSupport.validate(readSchema(), empty));
        assertEquals("$.items", minViolation.path());
        assertTrue(minViolation.expected().contains("1"), minViolation.expected());
    }

    @Test
    void stringBoundsAreEnforced() {
        ObjectNode tooShort = validValue().deepCopy();
        tooShort.put("label", "x");
        ContractViolation minLength = singleViolation(JsonSchemaSupport.validate(readSchema(), tooShort));
        assertEquals("$.label", minLength.path());
        assertTrue(minLength.expected().contains("2"), minLength.expected());

        ObjectNode tooLong = validValue().deepCopy();
        tooLong.put("label", "abcdef");
        ContractViolation maxLength = singleViolation(JsonSchemaSupport.validate(readSchema(), tooLong));
        assertEquals("$.label", maxLength.path());
        assertTrue(maxLength.expected().contains("5"), maxLength.expected());
    }

    @Test
    void numericBoundsAreEnforced() {
        ObjectNode below = validValue().deepCopy();
        below.put("score", -1);
        ContractViolation minimum = singleViolation(JsonSchemaSupport.validate(readSchema(), below));
        assertEquals("$.score", minimum.path());
        assertTrue(minimum.expected().contains("0"), minimum.expected());

        ObjectNode above = validValue().deepCopy();
        above.put("score", 11);
        ContractViolation maximum = singleViolation(JsonSchemaSupport.validate(readSchema(), above));
        assertEquals("$.score", maximum.path());
        assertTrue(maximum.expected().contains("10"), maximum.expected());
    }

    @Test
    void patternIsEnforced() {
        ObjectNode value = validValue().deepCopy();
        value.put("code", "abc");

        ContractViolation violation = singleViolation(JsonSchemaSupport.validate(readSchema(), value));

        assertEquals("SCHEMA_FIELD_INVALID", violation.code());
        assertEquals("$.code", violation.path());
        assertTrue(violation.expected().contains("^[A-Z]+$"), violation.expected());
    }

    @Test
    void itemsTypeViolationPointsIntoArray() {
        ObjectNode value = validValue().deepCopy();
        value.putArray("items").add("a").add(1);

        ContractViolation violation = singleViolation(JsonSchemaSupport.validate(readSchema(), value));

        assertEquals("$.items[1]", violation.path());
        assertEquals("SCHEMA_FIELD_INVALID", violation.code());
    }

    @Test
    void typeMismatchIsRejected() {
        ObjectNode value = validValue().deepCopy();
        value.put("kind", 7);

        List<ContractViolation> violations = JsonSchemaSupport.validate(readSchema(), value);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v ->
                        "SCHEMA_FIELD_INVALID".equals(v.code()) && "$.kind".equals(v.path())),
                "应报告 $.kind 类型不匹配：" + violations);
    }

    @Test
    void canonicalHashIsStableForSameEnumOrder() {
        JsonNode first = enumSchema();
        JsonNode second = enumSchema();

        assertEquals(JsonSchemaSupport.canonicalHash(first), JsonSchemaSupport.canonicalHash(second));
    }

    @Test
    void canonicalHashKeepsEnumDeclarationOrder() {
        ObjectNode ordered = MAPPER.createObjectNode();
        ordered.put("type", "string");
        ordered.set("enum", JsonSchemaSupport.enumArray(List.of("HOOK", "PAYOFF")));

        ObjectNode reordered = MAPPER.createObjectNode();
        reordered.put("type", "string");
        reordered.set("enum", JsonSchemaSupport.enumArray(List.of("PAYOFF", "HOOK")));

        assertNotEquals(JsonSchemaSupport.canonicalHash(ordered), JsonSchemaSupport.canonicalHash(reordered));
    }

    @Test
    void unsupportedSchemaKeywordFailsLoudly() {
        JsonNode schema = readNode(
                "{\"type\": \"array\", \"uniqueItems\": true, \"items\": {\"type\": \"string\"}}");
        JsonNode value = readNode("[\"a\", \"a\"]");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JsonSchemaSupport.validate(schema, value));

        assertTrue(error.getMessage().contains("uniqueItems"), error.getMessage());
    }

    private static JsonNode enumSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "string");
        schema.set("enum", JsonSchemaSupport.enumArray(
                Arrays.stream(SampleRole.values()).map(Enum::name).toList()));
        return schema;
    }

    private static JsonNode readNode(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试 JSON 构造失败", e);
        }
    }


}