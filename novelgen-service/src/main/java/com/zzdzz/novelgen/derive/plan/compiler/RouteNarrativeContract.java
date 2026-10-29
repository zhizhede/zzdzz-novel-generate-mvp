package com.zzdzz.novelgen.derive.plan.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.derive.plan.PlanContractSchemas;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.llm.contract.ContractViolation;
import com.zzdzz.novelgen.llm.contract.JsonSchemaSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * ROUTE v2 wire contract 的纯 Java 解析与领域校验器。
 *
 * <p>结构层先按静态 JSON Schema 校验；上下文层再按当前 run 的合法
 * cast/thread/beat key 清单校验。所有业务失败均返回 {@link ContractViolation}。
 */
public final class RouteNarrativeContract {

    private RouteNarrativeContract() {
    }

    /**
     * 当前结构计划提供的合法 key 清单。
     *
     * @param castKeys 合法角色键
     * @param threadKeys 合法线程键
     * @param beatKeys 合法拍键
     */
    public record RouteContractKeys(
            Set<String> castKeys,
            Set<String> threadKeys,
            Set<String> beatKeys) {
    }

    /**
     * 结构解析与上下文校验的组合结果。
     *
     * @param value 结构合法时的 wire 值；schema 失败时为 null
     * @param violations 全部违规；空列表表示通过
     */
    public record ParseResult(
            RouteNarrative value,
            List<ContractViolation> violations) {

        /**
         * 判断结果是否完全通过。
         *
         * @return 有值且零违规时为 true
         */
        public boolean valid() {
            return value != null && violations.isEmpty();
        }
    }

    /**
     * 只做 wire JSON 到 {@link RouteNarrative} 的结构转换。
     *
     * <p>调用方应先通过 ROUTE v2 schema；本方法对不符合 wire 结构的输入抛
     * {@link IllegalArgumentException}，不拼装 P6 文案。
     *
     * @param root ROUTE v2 JSON 根对象
     * @return 解析后的 wire 值
     */
    public static RouteNarrative parseStructure(JsonNode root) {
        ObjectNode object = requireObject(root, "$");
        List<RouteNarrative.BeatNarrative> beatNarratives = mapArray(
                requiredArray(object, "beatNarratives", "$.beatNarratives"),
                "$.beatNarratives",
                RouteNarrativeContract::parseBeatNarrative);
        List<RouteNarrative.SkippedBeat> skipped = mapArray(
                requiredArray(object, "skipped", "$.skipped"),
                "$.skipped",
                RouteNarrativeContract::parseSkippedBeat);
        return new RouteNarrative(List.copyOf(beatNarratives), List.copyOf(skipped));
    }

    /**
     * 按当前 run 的合法 key 清单做上下文校验，同时执行重复拍与空白字段校验。
     *
     * @param narrative 已解析的 wire 值
     * @param keys 合法 cast/thread/beat key 清单
     * @return 违规列表；空列表表示通过
     */
    public static List<ContractViolation> validate(
            RouteNarrative narrative,
            RouteContractKeys keys) {
        Objects.requireNonNull(narrative, "narrative");
        Objects.requireNonNull(keys, "keys");

        List<ContractViolation> violations = new ArrayList<>(validateContextFree(narrative));
        validateKeys(narrative, keys, violations);
        return List.copyOf(violations);
    }

    /**
     * 执行不依赖 run 上下文的重复拍与空白字段校验。
     *
     * @param narrative 已解析的 wire 值
     * @return 违规列表；空列表表示通过
     */
    public static List<ContractViolation> validateContextFree(RouteNarrative narrative) {
        Objects.requireNonNull(narrative, "narrative");
        List<ContractViolation> violations = new ArrayList<>();

        Set<String> narrativeBeatKeys = new HashSet<>();
        List<RouteNarrative.BeatNarrative> beats = narrative.beatNarratives() == null
                ? List.of()
                : narrative.beatNarratives();
        for (int i = 0; i < beats.size(); i++) {
            RouteNarrative.BeatNarrative beat = beats.get(i);
            String beatPath = "$.beatNarratives[" + i + "]";
            if (beat == null) {
                violations.add(fieldViolation("SCHEMA_FIELD_INVALID", beatPath,
                        "拍叙事必须是对象", "object", "null"));
                continue;
            }
            if (!narrativeBeatKeys.add(beat.beatKey())) {
                violations.add(new ContractViolation(
                        "DOMAIN_DUPLICATE_BEAT_KEY",
                        beatPath + ".beatKey",
                        "同一个 beat key 不得在 narrative 中重复",
                        "unique beatKey",
                        text(beat.beatKey())));
            }
            addBlank(violations, beat.goal(), beatPath + ".goal", "goal");
            addBlankEffects(violations, beat, beatPath);
        }

        List<RouteNarrative.SkippedBeat> skipped = narrative.skipped() == null
                ? List.of()
                : narrative.skipped();
        Set<String> skippedBeatKeys = new HashSet<>();
        for (int i = 0; i < skipped.size(); i++) {
            RouteNarrative.SkippedBeat skip = skipped.get(i);
            String path = "$.skipped[" + i + "]";
            if (skip == null) {
                violations.add(fieldViolation("SCHEMA_FIELD_INVALID", path,
                        "跳过项必须是对象", "object", "null"));
                continue;
            }
            if (narrativeBeatKeys.contains(skip.beatKey()) || !skippedBeatKeys.add(skip.beatKey())) {
                violations.add(new ContractViolation(
                        "DOMAIN_DUPLICATE_BEAT_KEY",
                        path + ".beatKey",
                        "同一个 beat key 不得重复，也不得同时出现在 narrative 和 skipped",
                        "unique beatKey",
                        text(skip.beatKey())));
            }
            addBlank(violations, skip.reason(), path + ".reason", "reason");
        }
        return violations;
    }

    /**
     * 组合 schema 校验、结构解析和上下文校验。
     *
     * @param root ROUTE v2 JSON 根对象
     * @param keys 合法 cast/thread/beat key 清单
     * @return 值与全部违规；schema 失败时 value 为 null
     */
    public static ParseResult parse(JsonNode root, RouteContractKeys keys) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(keys, "keys");

        List<ContractViolation> schemaViolations = JsonSchemaSupport.validate(
                PlanContractSchemas.routeNarrative().schemaView(), root);
        if (!schemaViolations.isEmpty()) {
            return new ParseResult(null, List.copyOf(schemaViolations));
        }

        RouteNarrative narrative;
        try {
            narrative = parseStructure(root);
        } catch (IllegalArgumentException e) {
            return new ParseResult(null, List.of(new ContractViolation(
                    "SCHEMA_FIELD_INVALID",
                    "$",
                    e.getMessage(),
                    "ROUTE v2 structure",
                    truncate(root.toString()))));
        }
        return new ParseResult(narrative, validate(narrative, keys));
    }

    private static void validateKeys(
            RouteNarrative narrative,
            RouteContractKeys keys,
            List<ContractViolation> violations) {
        List<RouteNarrative.BeatNarrative> beats = narrative.beatNarratives() == null
                ? List.of()
                : narrative.beatNarratives();
        for (int i = 0; i < beats.size(); i++) {
            RouteNarrative.BeatNarrative beat = beats.get(i);
            if (beat == null) {
                continue;
            }
            String beatPath = "$.beatNarratives[" + i + "]";
            if (!contains(keys.beatKeys(), beat.beatKey())) {
                violations.add(keyViolation("DOMAIN_UNKNOWN_BEAT_KEY",
                        beatPath + ".beatKey", "beatKeys", beat.beatKey()));
            }
            List<RouteNarrative.KnowledgeEffect> knowledgeEffects =
                    beat.knowledgeEffects() == null ? List.of() : beat.knowledgeEffects();
            for (int j = 0; j < knowledgeEffects.size(); j++) {
                RouteNarrative.KnowledgeEffect effect = knowledgeEffects.get(j);
                if (effect == null) {
                    continue;
                }
                List<String> characterKeys = effect.characterKeys() == null
                        ? List.of()
                        : effect.characterKeys();
                for (int k = 0; k < characterKeys.size(); k++) {
                    String characterKey = characterKeys.get(k);
                    if (!contains(keys.castKeys(), characterKey)) {
                        violations.add(keyViolation("DOMAIN_UNKNOWN_CHARACTER",
                                beatPath + ".knowledgeEffects[" + j + "].characterKeys[" + k + "]",
                                "castKeys", characterKey));
                    }
                }
            }
            List<RouteNarrative.StateEffect> stateEffects =
                    beat.stateEffects() == null ? List.of() : beat.stateEffects();
            for (int j = 0; j < stateEffects.size(); j++) {
                RouteNarrative.StateEffect effect = stateEffects.get(j);
                if (effect != null && !contains(keys.castKeys(), effect.characterKey())) {
                    violations.add(keyViolation("DOMAIN_UNKNOWN_CHARACTER",
                            beatPath + ".stateEffects[" + j + "].characterKey",
                            "castKeys", effect.characterKey()));
                }
            }
            List<RouteNarrative.ThreadEffect> threadEffects =
                    beat.threadEffects() == null ? List.of() : beat.threadEffects();
            for (int j = 0; j < threadEffects.size(); j++) {
                RouteNarrative.ThreadEffect effect = threadEffects.get(j);
                if (effect != null && !contains(keys.threadKeys(), effect.threadKey())) {
                    violations.add(keyViolation("DOMAIN_UNKNOWN_THREAD",
                            beatPath + ".threadEffects[" + j + "].threadKey",
                            "threadKeys", effect.threadKey()));
                }
            }
        }

        List<RouteNarrative.SkippedBeat> skipped = narrative.skipped() == null
                ? List.of()
                : narrative.skipped();
        for (int i = 0; i < skipped.size(); i++) {
            RouteNarrative.SkippedBeat skip = skipped.get(i);
            if (skip != null && !contains(keys.beatKeys(), skip.beatKey())) {
                violations.add(keyViolation("DOMAIN_UNKNOWN_BEAT_KEY",
                        "$.skipped[" + i + "].beatKey", "beatKeys", skip.beatKey()));
            }
        }
    }

    private static void addBlankEffects(
            List<ContractViolation> violations,
            RouteNarrative.BeatNarrative beat,
            String beatPath) {
        List<RouteNarrative.KnowledgeEffect> knowledgeEffects =
                beat.knowledgeEffects() == null ? List.of() : beat.knowledgeEffects();
        for (int i = 0; i < knowledgeEffects.size(); i++) {
            RouteNarrative.KnowledgeEffect effect = knowledgeEffects.get(i);
            if (effect != null) {
                addBlank(violations, effect.fact(),
                        beatPath + ".knowledgeEffects[" + i + "].fact", "fact");
            }
        }
        List<RouteNarrative.StateEffect> stateEffects =
                beat.stateEffects() == null ? List.of() : beat.stateEffects();
        for (int i = 0; i < stateEffects.size(); i++) {
            RouteNarrative.StateEffect effect = stateEffects.get(i);
            if (effect != null) {
                addBlank(violations, effect.detail(),
                        beatPath + ".stateEffects[" + i + "].detail", "detail");
            }
        }
        List<RouteNarrative.ThreadEffect> threadEffects =
                beat.threadEffects() == null ? List.of() : beat.threadEffects();
        for (int i = 0; i < threadEffects.size(); i++) {
            RouteNarrative.ThreadEffect effect = threadEffects.get(i);
            if (effect != null) {
                addBlank(violations, effect.detail(),
                        beatPath + ".threadEffects[" + i + "].detail", "detail");
            }
        }
    }

    private static void addBlank(
            List<ContractViolation> violations,
            String value,
            String path,
            String field) {
        if (value == null || value.isBlank()) {
            violations.add(new ContractViolation(
                    "DOMAIN_BLANK_FIELD",
                    path,
                    field + " 不得为空白",
                    "non-blank " + field,
                    value == null ? "null" : value));
        }
    }

    private static ContractViolation keyViolation(
            String code, String path, String expected, String actual) {
        return new ContractViolation(
                code,
                path,
                "key 必须来自当前结构计划的合法清单",
                expected,
                text(actual));
    }

    private static ContractViolation fieldViolation(
            String code, String path, String message, String expected, String actual) {
        return new ContractViolation(code, path, message, expected, actual);
    }

    private static boolean contains(Set<String> keys, String value) {
        return keys != null && keys.contains(value);
    }

    private static RouteNarrative.BeatNarrative parseBeatNarrative(JsonNode node) {
        ObjectNode object = requireObject(node, "beatNarratives item");
        return new RouteNarrative.BeatNarrative(
                requireText(object, "beatKey"),
                requireText(object, "goal"),
                optionalArray(object, "knowledgeEffects",
                        RouteNarrativeContract::parseKnowledgeEffect),
                optionalArray(object, "stateEffects",
                        RouteNarrativeContract::parseStateEffect),
                optionalArray(object, "threadEffects",
                        RouteNarrativeContract::parseThreadEffect));
    }

    private static RouteNarrative.KnowledgeEffect parseKnowledgeEffect(JsonNode node) {
        ObjectNode object = requireObject(node, "knowledgeEffects item");
        return new RouteNarrative.KnowledgeEffect(
                mapArray(requiredArray(object, "characterKeys", "characterKeys"),
                        "characterKeys",
                        RouteNarrativeContract::requireTextValue),
                requireText(object, "fact"));
    }

    private static RouteNarrative.StateEffect parseStateEffect(JsonNode node) {
        ObjectNode object = requireObject(node, "stateEffects item");
        String rawKind = requireText(object, "kind");
        PlanContracts.StateChangeKind kind;
        try {
            kind = PlanContracts.StateChangeKind.valueOf(rawKind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知 StateChangeKind: " + rawKind);
        }
        return new RouteNarrative.StateEffect(
                requireText(object, "characterKey"),
                kind,
                requireText(object, "detail"));
    }

    private static RouteNarrative.ThreadEffect parseThreadEffect(JsonNode node) {
        ObjectNode object = requireObject(node, "threadEffects item");
        String rawEffect = requireText(object, "effect");
        PlanContracts.ThreadEffectKind effect;
        try {
            effect = PlanContracts.ThreadEffectKind.valueOf(rawEffect);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知 ThreadEffectKind: " + rawEffect);
        }
        return new RouteNarrative.ThreadEffect(
                requireText(object, "threadKey"),
                effect,
                requireText(object, "detail"));
    }

    private static RouteNarrative.SkippedBeat parseSkippedBeat(JsonNode node) {
        ObjectNode object = requireObject(node, "skipped item");
        return new RouteNarrative.SkippedBeat(
                requireText(object, "beatKey"),
                requireText(object, "reason"));
    }

    private static <T> List<T> optionalArray(
            ObjectNode object,
            String field,
            Function<JsonNode, T> parser) {
        JsonNode value = object.get(field);
        if (value == null || value.isMissingNode()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("字段 " + field + " 必须是数组");
        }
        return mapArray((ArrayNode) value, field, parser);
    }

    private static ArrayNode requiredArray(
            ObjectNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null || value.isMissingNode()) {
            throw new IllegalArgumentException("缺少字段 " + field + "（path=" + path + "）");
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("字段 " + field + " 必须是数组");
        }
        return (ArrayNode) value;
    }

    private static ObjectNode requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("path=" + path + " 必须是 JSON 对象");
        }
        return (ObjectNode) node;
    }

    private static String requireText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isMissingNode()) {
            throw new IllegalArgumentException("缺少字段 " + field);
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("字段 " + field + " 必须是字符串");
        }
        return value.asText();
    }

    private static String requireTextValue(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException("数组元素必须是字符串");
        }
        return node.asText();
    }

    private static <T> List<T> mapArray(
            ArrayNode array,
            String path,
            Function<JsonNode, T> parser) {
        List<T> values = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            try {
                values.add(parser.apply(element));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(path + ": " + e.getMessage());
            }
        }
        return List.copyOf(values);
    }

    private static String text(String value) {
        return value == null ? "null" : value;
    }

    private static String truncate(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120) + "...";
    }
}