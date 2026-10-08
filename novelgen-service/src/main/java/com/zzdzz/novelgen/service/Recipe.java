package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;

import java.util.List;

/**
 * 配方：生成一本目标书的全部内容设定（业务侧推翻重构 L1，见 docs/改造方案-业务侧推翻重构详细规划.md §4）。
 * derive_config JSONB 的新读法——preset 入口 + 选材/克隆/词条两段 + params 收编；
 * 旧 mode 键在解析处一次性归一（MIGRATE 归档 RESKIN 能力集，规划 §9-D1 修正），不保留双读。
 * 读侧 fail-open：坏 JSON/空值 → ORIGINAL 默认配方；唯一会抛的不变量是 SPLICE 选材不足（§9-D3 口径）。
 */
public record Recipe(Preset preset, Sources sources, Long sourceSampleId, Clones clones,
                     List<String> tags, String gates, DeriveSupport.Cfg params, List<Long> samples) {

    /** 开书预设（产线）。MIGRATE 已下线，不在此枚举——历史值在 parse 处归档为 RESKIN。 */
    public enum Preset { ORIGINAL, RESKIN, SPLICE }

    /** 选材范围：纯原创 / 单样本 / 多本书（SPLICE 两层取样的取样池）。 */
    public enum Sources { NONE, SINGLE_SAMPLE, MULTI_SAMPLE }

    /** 克隆资产范围（建书时消费一次；能力门由 DeriveProfile.CLONE_ASSETS 把守，RESKIN/SPLICE 永不消费）。 */
    public record Clones(boolean characters, boolean cards, boolean world, boolean plotOutline) {
        public static Clones none() {
            return new Clones(false, false, false, false);
        }

        public static Clones all() {
            return new Clones(true, true, true, true);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Recipe parse(String json) {
        DeriveSupport.Cfg params = DeriveSupport.parse(json);
        if (json == null || json.isBlank()) {
            return defaults(params);
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            Preset preset = presetOf(n);
            Long sampleId = params.sourceSampleId();
            java.util.List<Long> samples = new java.util.ArrayList<>();
            JsonNode arr = n.path("sources").path("samples");
            if (arr.isArray()) {
                for (JsonNode s : arr) {
                    if (s.canConvertToLong() && !samples.contains(s.asLong())) {
                        samples.add(s.asLong());
                    }
                }
            }
            Recipe recipe = new Recipe(preset, sourcesOf(n, sampleId), sampleId, clonesOf(n, sampleId),
                    params.tags() != null ? params.tags() : List.of(),
                    textOrNull(n.path("gates")), params, List.copyOf(samples));
            recipe.validate();
            return recipe;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return defaults(params);
        }
    }

    /** 不变量：SPLICE 必须多书取材（阶段二验收口径 N≥2）。其余键缺失一律 fail-open。 */
    private void validate() {
        if (preset == Preset.SPLICE && sources != Sources.MULTI_SAMPLE) {
            throw new BizException(ErrorCode.PARAM_ERROR, "拼接配方必须选材至少 2 本书（sources.samples）");
        }
    }

    private static Recipe defaults(DeriveSupport.Cfg params) {
        return new Recipe(Preset.ORIGINAL, Sources.NONE, null, Clones.none(),
                params.tags() != null ? params.tags() : List.of(), null, params, List.of());
    }

    private static Preset presetOf(JsonNode n) {
        String raw = textOrNull(n.path("preset"));
        if (raw == null) {
            raw = textOrNull(n.path("mode"));
        }
        return ofRaw(raw);
    }

    /**
     * 原始 mode/preset 字符串 → 预设（写侧归一与读侧解析的**唯一**旧值映射点）：
     * 缺省/未知 → ORIGINAL（fail-open）；SPLICE → SPLICE；
     * RESKIN 与历史 MIGRATE → RESKIN（D1 拍板：MIGRATE 产线下线，归档 RESKIN 能力集——
     * 两者能力表一致，且迁移书续写依赖蓝图口径提示词段，归 ORIGINAL 会被差异红线压制）。
     */
    public static Preset ofRaw(String raw) {
        if (raw == null) {
            return Preset.ORIGINAL;
        }
        String v = raw.strip();
        if ("SPLICE".equalsIgnoreCase(v)) {
            return Preset.SPLICE;
        }
        if ("RESKIN".equalsIgnoreCase(v) || "MIGRATE".equalsIgnoreCase(v)) {
            return Preset.RESKIN;
        }
        return Preset.ORIGINAL;
    }

    private static Sources sourcesOf(JsonNode n, Long sampleId) {
        JsonNode s = n.path("sources");
        if (s.isObject()) {
            JsonNode samples = s.path("samples");
            if (samples.isArray() && samples.size() >= 2) {
                return Sources.MULTI_SAMPLE;
            }
            if (samples.isArray() && samples.size() == 1) {
                return Sources.SINGLE_SAMPLE;
            }
            return sampleId != null ? Sources.SINGLE_SAMPLE : Sources.NONE;
        }
        return sampleId != null ? Sources.SINGLE_SAMPLE : Sources.NONE;
    }

    private static Clones clonesOf(JsonNode n, Long sampleId) {
        JsonNode c = n.path("clones");
        if (c.isObject()) {
            return new Clones(c.path("characters").asBoolean(false), c.path("cards").asBoolean(false),
                    c.path("world").asBoolean(false), c.path("plotOutline").asBoolean(false));
        }
        // 缺段时的推导只服务建书默认值；存量书克隆已在历史完成，消费受 CLONE_ASSETS 能力把守。
        return sampleId != null ? Clones.all() : Clones.none();
    }

    private static String textOrNull(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText().strip() : null;
    }
}
