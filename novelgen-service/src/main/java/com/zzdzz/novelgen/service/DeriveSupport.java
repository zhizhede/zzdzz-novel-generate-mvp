package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 通用生成配置（novels.derive_config JSONB）的解析与换算：
 * 开书/工作台落的 POV、掺水量、节奏、目标章数、优先级、类型标签等参数在此统一成类型化读数。
 * 读侧 fail-open——无配置或坏 JSON 一律给默认，绝不拦生成。
 */
@Slf4j
public final class DeriveSupport {

    /** 解析后的生成配置（全部可空=未设置，消费方各自取默认）。 */
    public record Cfg(Integer water, String pov, String povCharacter, String pacingNote,
                      Integer chaptersPerVolume, Integer targetChapters, Boolean autoContinue,
                      Integer priority, java.util.List<String> tags) {

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
            return new Cfg(null, null, null, null, null, null, null, null, null);
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
                    intOrNull(n.path("priority")), tags);
        } catch (Exception e) {
            return new Cfg(null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * 人称读取链：章级（chapters.pov，AI 章纲步写的本章实际人称）→ 书级（derive_config.pov）→ null（不约束）。
     */
    public static String effectivePov(String chapterPov, String bookPov) {
        if (chapterPov != null && !chapterPov.isBlank()) {
            return chapterPov.strip();
        }
        if (bookPov != null && !bookPov.isBlank()) {
            return bookPov.strip();
        }
        return null;
    }

    /**
     * 掺水量 0-100（50=均衡默认）→ 读者评审注水阈值三元组 {block, hard, ai_review_fix_floor}：
     * 越干阈值越严，越水阈值越松。线性插值并夹紧，纯函数可单测。
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
     * 把掺水量三元组写进风格包 gate_config JSON（缺失键补齐，既有键覆盖）。
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

    /** 把章长带写进风格包 gate_config JSON（缺失键补齐，既有键覆盖）。 */
    public static String applyBudgetBand(String gateJson, int budgetMin, int budgetMax, double tolerance) {
        ObjectNode node;
        try {
            node = (gateJson == null || gateJson.isBlank())
                    ? MAPPER.createObjectNode()
                    : (ObjectNode) MAPPER.readTree(gateJson);
        } catch (Exception e) {
            node = MAPPER.createObjectNode();
        }
        node.put("budget_min", budgetMin);
        node.put("budget_max", budgetMax);
        node.put("chapter_length_tolerance", tolerance);
        return node.toString();
    }

    /** 读章长带（budget_min/max）：两键齐全才返回，坏 JSON / 缺键 / 非数值一律 null。 */
    public static int[] readBudgetBand(String gateJson) {
        if (gateJson == null || gateJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(gateJson);
            if (n.path("budget_min").canConvertToInt() && n.path("budget_max").canConvertToInt()) {
                return new int[]{n.path("budget_min").asInt(), n.path("budget_max").asInt()};
            }
            return null;
        } catch (Exception e) {
            log.warn("门禁配置解析失败，章长带按未配置处理：{}", e.getMessage());
            return null;
        }
    }

    /** 掺水量 → 密度提示（给场景提示词的纯文本口径）。 */
    public static String densityHint(Integer water) {
        if (water == null) {
            return null;
        }
        int w = Math.max(0, Math.min(100, water));
        if (w <= 20) {
            return "极干：信息密度拉满，禁止注水与重复解释";
        }
        if (w <= 40) {
            return "偏干：紧凑推进，控制抒情与无关描写";
        }
        if (w <= 60) {
            return "均衡：推进与氛围兼顾";
        }
        if (w <= 80) {
            return "偏水：允许更多氛围、日常与情绪铺陈，节奏舒缓";
        }
        return "极水：氛围与情绪优先，允许较慢节奏";
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().strip() : null;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
