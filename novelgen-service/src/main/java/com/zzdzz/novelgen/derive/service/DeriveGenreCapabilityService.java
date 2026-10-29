package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.derive.mapper.DeriveGenreCapabilityMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreCapabilityDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 解析并校验多题材能力包；通用模型不依赖任何单一题材。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveGenreCapabilityService {

    public enum Capability {
        WORLD, ROMANCE, MYSTERY, FANTASY, SCIFI, URBAN, IDOL, EPIC_FINALE
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DeriveGenreCapabilityMapper mapper;

    /** 按 tags/intent 解析启用能力；WORLD 恒启用。 */
    public List<Capability> resolve(List<String> tags, String intent, String outcome) {
        String text = normalize(tags, intent, outcome);
        Set<Capability> out = new LinkedHashSet<>();
        out.add(Capability.WORLD);
        addIf(out, Capability.ROMANCE, text, "言情", "恋爱", "爱情", "romance");
        addIf(out, Capability.MYSTERY, text, "悬疑", "推理", "案件", "失踪", "凶手", "mystery", "detective");
        addIf(out, Capability.FANTASY, text, "奇幻", "魔法", "异能", "神明", "fantasy", "magic");
        addIf(out, Capability.SCIFI, text, "未来", "科技", "外星", "改造", "科幻", "scifi");
        addIf(out, Capability.URBAN, text, "都市", "现代", "城市", "urban");
        addIf(out, Capability.IDOL, text, "偶像", "舞台", "演艺", "idol");
        if (outcome != null && (outcome.contains("大结局") || outcome.contains("最终")
                || outcome.contains("国家级") || outcome.contains("世界级"))) {
            out.add(Capability.EPIC_FINALE);
        }
        addIf(out, Capability.EPIC_FINALE, text, "史诗", "终极", "世界线收束", "epic");
        return List.copyOf(out);
    }

    /** 为 run 落能力包；重复调用幂等。 */
    public List<Capability> ensure(long runId, List<String> tags, String intent, String outcome) {
        List<Capability> enabled = resolve(tags, intent, outcome);
        List<DeriveGenreCapabilityDO> existing = mapper.selectList(
                new LambdaQueryWrapper<DeriveGenreCapabilityDO>()
                        .eq(DeriveGenreCapabilityDO::getRunId, runId));
        Set<String> present = new LinkedHashSet<>();
        for (DeriveGenreCapabilityDO row : existing) {
            present.add(row.getCapabilityKey());
        }
        int priority = 0;
        for (Capability capability : enabled) {
            if (present.contains(capability.name())) {
                continue;
            }
            DeriveGenreCapabilityDO row = new DeriveGenreCapabilityDO();
            row.setRunId(runId);
            row.setCapabilityKey(capability.name());
            row.setEnabled(true);
            row.setRequiredContract("{}");
            row.setForbiddenRules("[]");
            row.setEventRoles("{}");
            row.setValidationRules("[]");
            row.setPriority(priority++);
            mapper.insertFull(row);
        }
        return enabled;
    }

    public List<Capability> enabled(long runId) {
        List<DeriveGenreCapabilityDO> rows = mapper.selectList(
                new LambdaQueryWrapper<DeriveGenreCapabilityDO>()
                        .eq(DeriveGenreCapabilityDO::getRunId, runId)
                        .eq(DeriveGenreCapabilityDO::getEnabled, true)
                        .orderByAsc(DeriveGenreCapabilityDO::getPriority));
        List<Capability> out = new ArrayList<>();
        for (DeriveGenreCapabilityDO row : rows) {
            try {
                out.add(Capability.valueOf(row.getCapabilityKey()));
            } catch (RuntimeException ignored) {
                log.warn("忽略未知题材能力键：{}", row.getCapabilityKey());
            }
        }
        return out;
    }

    /** 结构化冲突裁决；同批能力若存在资源/真相竞争，必须留下 fusion decision。 */
    public List<ObjectNode> fusionDecisions(List<Capability> capabilities) {
        List<ObjectNode> decisions = new ArrayList<>();
        if (capabilities.contains(Capability.MYSTERY) && capabilities.contains(Capability.FANTASY)) {
            decisions.add(fusion("FUSION-MYSTERY-FANTASY", List.of("MYSTERY", "FANTASY"),
                    "奇幻能力可存在，但不得直接给出最终真相",
                    List.of("MYSTERY_LOGIC", "FANTASY_WORLD")));
        }
        if (capabilities.contains(Capability.ROMANCE) && capabilities.contains(Capability.MYSTERY)) {
            decisions.add(fusion("FUSION-ROMANCE-MYSTERY", List.of("ROMANCE", "MYSTERY"),
                    "关系推进必须受案件证据和压力事件约束",
                    List.of("MYSTERY_LOGIC", "ROMANCE_ARC")));
        }
        if (capabilities.contains(Capability.IDOL) && capabilities.contains(Capability.URBAN)) {
            decisions.add(fusion("FUSION-IDOL-URBAN", List.of("IDOL", "URBAN"),
                    "舞台资源服从城市制度和组织规则",
                    List.of("URBAN_INSTITUTION", "IDOL_ORGANIZATION")));
        }
        return decisions;
    }

    public String snapshotJson(List<Capability> capabilities, List<ObjectNode> decisions) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "ENABLED");
        ArrayNode arr = root.putArray("genreCapabilities");
        for (Capability capability : capabilities) {
            arr.add(capability.name());
        }
        root.set("fusionDecisions", MAPPER.valueToTree(decisions));
        return root.toString();
    }

    public JsonNode readSnapshot(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.isObject() ? node : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static ObjectNode fusion(String key, List<String> capabilities, String resolution, List<String> priority) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("decisionKey", key);
        node.set("conflictingCapabilities", MAPPER.valueToTree(capabilities));
        node.put("resolution", resolution);
        node.set("priority", MAPPER.valueToTree(priority));
        return node;
    }

    private static void addIf(Set<Capability> out, Capability capability, String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key.toLowerCase(Locale.ROOT)) || text.contains(key)) {
                out.add(capability);
                return;
            }
        }
    }

    private static String normalize(List<String> tags, String intent, String outcome) {
        StringBuilder sb = new StringBuilder();
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null) {
                    sb.append(tag.toLowerCase(Locale.ROOT)).append(' ');
                }
            }
        }
        sb.append(intent == null ? "" : intent.toLowerCase(Locale.ROOT)).append(' ');
        sb.append(outcome == null ? "" : outcome.toLowerCase(Locale.ROOT));
        return sb.toString();
    }
}