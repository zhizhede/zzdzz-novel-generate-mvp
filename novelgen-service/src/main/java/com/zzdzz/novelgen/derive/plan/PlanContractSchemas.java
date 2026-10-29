package com.zzdzz.novelgen.derive.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.zzdzz.novelgen.derive.plan.compiler.RouteNarrative;
import com.zzdzz.novelgen.derive.plan.compiler.RouteNarrativeContract;
import com.zzdzz.novelgen.llm.contract.JsonSchemaSupport;
import com.zzdzz.novelgen.llm.contract.NodeOutputContract;
import java.util.Arrays;
import java.util.List;

/**
 * 故事规划 V2 的节点输出契约与 JSON Schema。
 *
 * <p>枚举 schema 一律从 {@link PlanContracts} 的枚举声明生成，不维护第二份字符串表。
 */
public final class PlanContractSchemas {

    /** ROUTE v2 契约标识。 */
    public static final String ROUTE_NARRATIVE_ID = "derive.route_narrative";

    /** ROUTE v2 schema 版本。 */
    public static final int ROUTE_NARRATIVE_VERSION = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode ROUTE_NARRATIVE_SCHEMA = buildRouteNarrativeSchema();

    private static final NodeOutputContract<RouteNarrative> ROUTE_NARRATIVE =
            new NodeOutputContract<>(
                    ROUTE_NARRATIVE_ID,
                    StoryPlanningPrompts.NODE_ROUTE,
                    ROUTE_NARRATIVE_VERSION,
                    ROUTE_NARRATIVE_SCHEMA,
                    promptFragment(ROUTE_NARRATIVE_SCHEMA),
                    RouteNarrativeContract::parseStructure,
                    RouteNarrativeContract::validateContextFree);

    private PlanContractSchemas() {
    }

    /**
     * 获取 ROUTE v2 节点输出契约。
     *
     * @return 不可变的契约视图
     */
    public static NodeOutputContract<RouteNarrative> routeNarrative() {
        return ROUTE_NARRATIVE;
    }

    private static String promptFragment(JsonNode schema) {
        return """
                ROUTE v2 只描述拍的叙事效果，不决定 beatRole、章号、seq、storyLayer 或稳定 ID。
                只输出 JSON 对象，严格遵守以下 JSON Schema：
                """ + schema;
    }

    private static JsonNode buildRouteNarrativeSchema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("title", ROUTE_NARRATIVE_ID + "@" + ROUTE_NARRATIVE_VERSION);
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.putArray("required").add("beatNarratives").add("skipped");

        ObjectNode properties = root.putObject("properties");
        properties.set("beatNarratives", arrayOf(beatNarrativeSchema()));
        properties.set("skipped", arrayOf(skippedBeatSchema()));
        return root;
    }

    private static ObjectNode beatNarrativeSchema() {
        ObjectNode schema = closedObject();
        schema.putArray("required").add("beatKey").add("goal");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("beatKey").put("type", "string");
        properties.putObject("goal").put("type", "string");
        properties.set("knowledgeEffects", arrayOf(knowledgeEffectSchema()));
        properties.set("stateEffects", arrayOf(stateEffectSchema()));
        properties.set("threadEffects", arrayOf(threadEffectSchema()));
        return schema;
    }

    private static ObjectNode knowledgeEffectSchema() {
        ObjectNode schema = closedObject();
        schema.putArray("required").add("characterKeys").add("fact");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode characterKeys = properties.putObject("characterKeys");
        characterKeys.put("type", "array");
        characterKeys.putObject("items").put("type", "string");
        properties.putObject("fact").put("type", "string");
        return schema;
    }

    private static ObjectNode stateEffectSchema() {
        ObjectNode schema = closedObject();
        schema.putArray("required").add("characterKey").add("kind").add("detail");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("characterKey").put("type", "string");
        ObjectNode kind = properties.putObject("kind");
        kind.put("type", "string");
        kind.set("enum", enumArray(PlanContracts.StateChangeKind.values()));
        properties.putObject("detail").put("type", "string");
        return schema;
    }

    private static ObjectNode threadEffectSchema() {
        ObjectNode schema = closedObject();
        schema.putArray("required").add("threadKey").add("effect").add("detail");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("threadKey").put("type", "string");
        ObjectNode effect = properties.putObject("effect");
        effect.put("type", "string");
        effect.set("enum", enumArray(PlanContracts.ThreadEffectKind.values()));
        properties.putObject("detail").put("type", "string");
        return schema;
    }

    private static ObjectNode skippedBeatSchema() {
        ObjectNode schema = closedObject();
        schema.putArray("required").add("beatKey").add("reason");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("beatKey").put("type", "string");
        properties.putObject("reason").put("type", "string");
        return schema;
    }

    private static ObjectNode arrayOf(JsonNode itemSchema) {
        ObjectNode array = MAPPER.createObjectNode();
        array.put("type", "array");
        array.set("items", itemSchema);
        return array;
    }

    private static ObjectNode closedObject() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ArrayNode enumArray(Enum<?>[] values) {
        List<String> names = Arrays.stream(values).map(Enum::name).toList();
        return JsonSchemaSupport.enumArray(names);
    }
}