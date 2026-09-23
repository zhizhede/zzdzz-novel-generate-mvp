package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 衍生配置（novels.derive_config JSONB）的解析与换算：开书向导落的衍生参数在此统一成类型化读数，
 * 各消费方（场景提示词/卷规划/审批处置/续跑闭环）fail-open——无配置或坏 JSON 一律给默认，绝不拦生成。
 */
public final class DeriveSupport {

    /** 解析后的衍生配置（全部可空=未设置，消费方各自取默认）。 */
    public record Cfg(Integer water, String pov, String povCharacter, String pacingNote,
                      Integer chaptersPerVolume, Integer targetChapters, Boolean autoContinue,
                      Integer priority, Long sourceSampleId, java.util.List<String> tags) {

        public boolean autoContinueOn() {
            return Boolean.TRUE.equals(autoContinue);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeriveSupport() {
    }

    /** 坏 JSON/空值容忍：解析失败返回全空配置。 */
    public static Cfg parse(String json) {
        if (json == null || json.isBlank()) {
            return new Cfg(null, null, null, null, null, null, null, null, null, null);
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            java.util.List<String> tags = new java.util.ArrayList<>();
            if (n.path("tags").isArray()) {
                for (JsonNode t : n.path("tags")) {
                    String v = t.asText("").strip();
                    if (!v.isEmpty()) {
                        tags.add(v);
                    }
                }
            }
            return new Cfg(intOrNull(n.path("water")), textOrNull(n.path("pov")),
                    textOrNull(n.path("povCharacter")), textOrNull(n.path("pacingNote")),
                    intOrNull(n.path("chaptersPerVolume")), intOrNull(n.path("targetChapters")),
                    n.path("autoContinue").isBoolean() ? n.path("autoContinue").asBoolean() : null,
                    intOrNull(n.path("priority")),
                    n.path("sourceSampleId").canConvertToLong() ? n.path("sourceSampleId").asLong() : null,
                    tags);
        } catch (Exception e) {
            return new Cfg(null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * 掺水量 0-100（50=均衡默认）→ 读者评审注水阈值三元组 {block, hard, ai_review_fix_floor}：
     * 越干阈值越严（block 0.33→0.20、floor 0.60→0.70 修剪更狠保底更高），越水阈值越松（block→0.50、floor→0.50）。
     * 线性插值并夹紧，纯函数可单测。
     */
    public static double[] waterGates(int water) {
        int w = Math.max(0, Math.min(100, water));
        double k = (w - 50) / 50.0;
        double block = clamp(0.33 + 0.17 * k, 0.20, 0.50);
        double hard = clamp(0.50 + 0.15 * k, 0.35, 0.65);
        double floor = clamp(0.60 - 0.10 * k, 0.50, 0.70);
        return new double[]{round2(block), round2(hard), round2(floor)};
    }

    /**
     * 把掺水量三元组写进风格包 gate_config JSON（缺失键补齐，既有键覆盖）；
     * gateJson 为 null/坏 JSON 时从空对象起步。纯函数可单测。
     */
    public static String applyWaterGates(String gateJson, int water) {
        double[] gates = waterGates(water);
        ObjectNode node;
        try {
            node = (gateJson == null || gateJson.isBlank())
                    ? MAPPER.createObjectNode()
                    : (ObjectNode) MAPPER.readTree(gateJson);
        } catch (Exception e) {
            node = MAPPER.createObjectNode();
        }
        node.put("reader_fat_ratio_block", gates[0]);
        node.put("reader_fat_ratio_hard", gates[1]);
        node.put("ai_review_fix_floor", gates[2]);
        return node.toString();
    }

    /** 掺水量的人话提示词口径（场景生成密度段用）；null=未设置不注入。 */
    public static String densityHint(Integer water) {
        if (water == null) {
            return null;
        }
        if (water >= 70) {
            return "节奏可以舒缓：允许氛围铺陈与日常闲笔，但对白仍须各自推进关系或信息，禁止原地空转";
        }
        if (water >= 40) {
            return "节奏均衡：情节推进为主，允许适度氛围呼吸";
        }
        return "情节密度拉满：每一场都必须推进事件或关系，禁止纯氛围段落，环境描写只许夹在动作之间";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static Integer intOrNull(JsonNode n) {
        return n.canConvertToInt() ? n.asInt() : null;
    }

    private static String textOrNull(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText().strip() : null;
    }
}
