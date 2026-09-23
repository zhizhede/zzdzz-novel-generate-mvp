package com.zzdzz.novelgen.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 题材预设行（is_preset 风格包模板）：应用到书 = 拷贝指纹/门禁/规则。 */
public record PresetVO(long id, String name, String description, Integer budgetMin, Integer budgetMax) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** gateConfigJson 解析章长带（坏 JSON/无带给 null，前端隐藏展示）。 */
    public static PresetVO from(com.zzdzz.novelgen.model.dto.StylePackDTO d, String gateConfigJson) {
        Integer lo = null;
        Integer hi = null;
        try {
            JsonNode n = MAPPER.readTree(gateConfigJson == null || gateConfigJson.isBlank() ? "{}" : gateConfigJson);
            lo = n.path("budget_min").canConvertToInt() && n.path("budget_min").asInt() > 0 ? n.path("budget_min").asInt() : null;
            hi = n.path("budget_max").canConvertToInt() && n.path("budget_max").asInt() > 0 ? n.path("budget_max").asInt() : null;
        } catch (Exception ignored) {
            // 坏 JSON 时字数带给 null
        }
        return new PresetVO(d.getId(), d.getName(), d.getDescription(), lo, hi);
    }
}
