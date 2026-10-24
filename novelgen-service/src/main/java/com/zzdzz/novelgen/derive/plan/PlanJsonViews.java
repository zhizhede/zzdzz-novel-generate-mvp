package com.zzdzz.novelgen.derive.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;

import java.util.List;

/** 把库实体投影成提示词 JSON，避免序列化审计时间等非业务字段。 */
public final class PlanJsonViews {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanJsonViews() {
    }

    public static ArrayNode characters(List<CharacterPlanDO> rows) {
        ArrayNode out = MAPPER.createArrayNode();
        rows.forEach(row -> out.add(character(row)));
        return out;
    }

    public static ObjectNode character(CharacterPlanDO row) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("characterKey", row.getCharacterKey());
        node.put("displayNameHint", row.getDisplayNameHint());
        node.put("functionCode", row.getFunctionCode());
        node.put("spotlightVolume", row.getSpotlightVolume());
        node.put("conflictVolume", row.getConflictVolume());
        node.put("payoffVolume", row.getPayoffVolume());
        node.put("arcBefore", row.getArcBefore());
        node.put("arcAfter", row.getArcAfter());
        node.put("status", row.getStatus());
        node.set("contract", json(row.getContract()));
        return node;
    }

    public static ArrayNode threads(List<StoryThreadDO> rows) {
        ArrayNode out = MAPPER.createArrayNode();
        rows.forEach(row -> out.add(thread(row)));
        return out;
    }

    public static ObjectNode thread(StoryThreadDO row) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("threadKey", row.getThreadKey());
        node.put("kind", row.getKind());
        node.put("title", row.getTitle());
        node.put("openedVolume", row.getOpenedVolume());
        node.put("payoffVolume", row.getPayoffVolume());
        node.put("ownerCharacterKey", row.getOwnerCharacterKey());
        node.put("status", row.getStatus());
        node.set("contract", json(row.getContract()));
        return node;
    }

    public static ArrayNode volumes(List<VolumePlanDO> rows) {
        ArrayNode out = MAPPER.createArrayNode();
        for (VolumePlanDO row : rows) {
            ObjectNode node = out.addObject();
            node.put("volumeNo", row.getVolumeNo());
            node.put("fromChapter", row.getFromChapter());
            node.put("toChapter", row.getToChapter());
            node.put("arc", row.getArc());
            node.put("localEvent", row.getLocalEvent());
            node.put("localClosure", row.getLocalClosure());
            node.put("nextHook", row.getNextHook());
            node.put("globalRole", row.getGlobalRole());
            node.put("status", row.getStatus());
            node.set("contract", json(row.getContract()));
        }
        return out;
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
