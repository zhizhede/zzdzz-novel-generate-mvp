package com.zzdzz.novelgen.llm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON Schema 构建、稳定哈希与校验的通用基础设施。
 *
 * <p>枚举渲染只有一个来源：调用方传入 {@code Enum::name} 的结果，本类不再维护第二份字符串表。
 * 校验基于 networknt json-schema-validator，校验消息统一转换为 {@link ContractViolation}，
 * 字段路径使用 {@code $} 起始的 JSON Pointer 风格（如 {@code $.beatNarratives[4].stateEffects[0].kind}）。
 */
public final class JsonSchemaSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "type",
            "enum",
            "required",
            "additionalProperties",
            "items",
            "minItems",
            "maxItems",
            "minLength",
            "maxLength",
            "minimum",
            "maximum",
            "pattern");

    private JsonSchemaSupport() {
    }

    /**
     * 把枚举名渲染成提示词用的无序号列表，每一项独占一行。
     *
     * @param values 枚举名集合（只接收 {@code Enum::name} 的结果）
     * @return 形如 {@code - HOOK\n- PAYOFF} 的文本
     */
    public static String bulletList(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        return values.stream()
                .map(value -> {
                    Objects.requireNonNull(value, "values 含 null");
                    return "- " + value;
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 把枚举名集合渲染成 JSON Schema 的 enum 数组，顺序与声明顺序一致。
     *
     * @param values 枚举名集合（只接收 {@code Enum::name} 的结果）
     * @return JSON 数组节点
     */
    public static ArrayNode enumArray(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        ArrayNode array = MAPPER.createArrayNode();
        for (String value : values) {
            array.add(Objects.requireNonNull(value, "values 含 null"));
        }
        return array;
    }

    /**
     * 计算 schema 的稳定哈希：对象键按字典序规范化后取 SHA-256。
     *
     * @param schema JSON Schema
     * @return 64 位十六进制哈希
     */
    public static String canonicalHash(JsonNode schema) {
        Objects.requireNonNull(schema, "schema");
        String canonical = canonicalize(schema).toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 用 JSON Schema 校验一个值，把所有校验消息转换为结构化违规列表。
     *
     * @param schema JSON Schema
     * @param value 待校验的值
     * @return 按路径排序的违规列表；空列表表示通过
     */
    public static List<ContractViolation> validate(JsonNode schema, JsonNode value) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(value, "value");
        JsonSchema jsonSchema = SCHEMA_FACTORY.getSchema(schema);
        Set<String> handledRequiredPaths = new HashSet<>();
        List<ContractViolation> violations = new ArrayList<>();
        for (ValidationMessage message : jsonSchema.validate(value)) {
            if (!SUPPORTED_KEYWORDS.contains(message.getType())) {
                throw new IllegalStateException(
                        "无法映射的 schema keyword: " + message.getType() + "，消息：" + message.getMessage());
            }
            switch (message.getType()) {
                case "required" -> addRequiredViolations(message, handledRequiredPaths, violations);
                case "additionalProperties" -> violations.add(toAdditionalPropertiesViolation(message));
                default -> violations.add(toViolation(message));
            }
        }
        violations.sort(Comparator.comparing(ContractViolation::path)
                .thenComparing(ContractViolation::code)
                .thenComparing(ContractViolation::message));
        return violations;
    }

    private static ContractViolation toViolation(ValidationMessage message) {
        String keyword = message.getType();
        String code = "enum".equals(keyword) ? "SCHEMA_ENUM_INVALID" : "SCHEMA_FIELD_INVALID";
        String path = pathOf(message);
        String expected = expectedOf(keyword, message);
        String actual = actualOf(message.getInstanceNode());
        return new ContractViolation(code, path, message.getMessage(), expected, actual);
    }

    private static void addRequiredViolations(ValidationMessage message,
                                              Set<String> handledRequiredPaths,
                                              List<ContractViolation> violations) {
        String objectPath = pathOf(message);
        if (!handledRequiredPaths.add(objectPath)) {
            return;
        }
        JsonNode requiredNode = message.getSchemaNode();
        JsonNode instanceNode = message.getInstanceNode();
        for (JsonNode requiredField : requiredNode) {
            String fieldName = requiredField.asText();
            if (!instanceNode.isObject() || !instanceNode.has(fieldName)) {
                violations.add(new ContractViolation(
                        "SCHEMA_FIELD_INVALID",
                        objectPath + "." + fieldName,
                        "缺少必填字段 " + fieldName,
                        "present",
                        "missing"));
            }
        }
    }

    private static ContractViolation toAdditionalPropertiesViolation(ValidationMessage message) {
        String property = message.getProperty();
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("additionalProperties 违规缺少属性名：" + message.getMessage());
        }
        String path = pathOf(message) + "." + property;
        return new ContractViolation(
                "SCHEMA_FIELD_INVALID",
                path,
                message.getMessage(),
                "no additional properties",
                property);
    }

    private static String pathOf(ValidationMessage message) {
        String path = message.getInstanceLocation().toString();
        return path == null || path.isBlank() ? "$" : path;
    }

    private static String expectedOf(String keyword, ValidationMessage message) {
        JsonNode schemaNode = message.getSchemaNode();
        return switch (keyword) {
            case "enum" -> joinEnum(schemaNode);
            case "type" -> "type=" + scalarText(schemaNode);
            case "pattern" -> "matches " + scalarText(schemaNode);
            case "minLength" -> "length>=" + scalarText(schemaNode);
            case "maxLength" -> "length<=" + scalarText(schemaNode);
            case "minimum" -> ">=" + scalarText(schemaNode);
            case "maximum" -> "<=" + scalarText(schemaNode);
            case "minItems" -> "size>=" + scalarText(schemaNode);
            case "maxItems" -> "size<=" + scalarText(schemaNode);
            case "items" -> "valid items";
            default -> message.getMessage();
        };
    }

    private static String joinEnum(JsonNode enumNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : enumNode) {
            values.add(scalarText(element));
        }
        return String.join("|", values);
    }

    private static String actualOf(JsonNode instanceNode) {
        if (instanceNode == null || instanceNode.isMissingNode() || instanceNode.isNull()) {
            return "missing";
        }
        if (instanceNode.isTextual()) {
            return instanceNode.asText();
        }
        if (instanceNode.isContainerNode()) {
            return truncate(instanceNode.toString());
        }
        return instanceNode.asText();
    }

    private static String scalarText(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String truncate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = MAPPER.createObjectNode();
            node.properties().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> canonical.set(entry.getKey(), canonicalize(entry.getValue())));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = MAPPER.createArrayNode();
            for (JsonNode element : node) {
                canonical.add(canonicalize(element));
            }
            return canonical;
        }
        return node;
    }
}