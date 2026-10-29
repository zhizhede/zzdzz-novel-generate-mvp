package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterProfileDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterRelationshipDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreCapabilityDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveOrganizationDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePressureEventDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveWorldInstitutionDO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按章/拍回放角色、关系、组织、制度和知识状态的确定性账本。 */
@Service
public class DeriveStoryStateLedgerService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record LedgerSnapshot(int chapterNo, int seq, Map<String, Integer> relationshipTrust,
                                 Map<String, Integer> relationshipIntimacy,
                                 Map<String, Integer> relationshipConflict,
                                 Map<String, String> characterArc,
                                 Map<String, String> organizationState,
                                 Map<String, String> institutionState,
                                 Map<String, List<String>> knowledge) {
    }

    public record StoryStateLedger(List<LedgerSnapshot> before,
                                   List<LedgerSnapshot> after,
                                   List<String> unresolvedEvents,
                                   List<String> unclosedRelationships,
                                   List<String> unpaidInstitutionCosts,
                                   List<String> errors) {
    }

    /** 确定性模拟所有事件；事件必须带章/拍，否则记账本错误。 */
    public StoryStateLedger simulate(List<DeriveCharacterProfileDO> profiles,
                                     List<DeriveCharacterRelationshipDO> relationships,
                                     List<DeriveOrganizationDO> organizations,
                                     List<DeriveWorldInstitutionDO> institutions,
                                     List<DerivePressureEventDO> events) {
        Map<String, Integer> trust = new LinkedHashMap<>();
        Map<String, Integer> intimacy = new LinkedHashMap<>();
        Map<String, Integer> conflict = new LinkedHashMap<>();
        Map<String, String> arcs = new LinkedHashMap<>();
        for (DeriveCharacterProfileDO profile : profiles) {
            arcs.put(profile.getCharacterKey(), profile.getArcState());
        }
        for (DeriveCharacterRelationshipDO relationship : relationships) {
            String key = relationKey(relationship.getFromCharacterKey(), relationship.getToCharacterKey());
            trust.put(key, nvl(relationship.getTrust()));
            intimacy.put(key, nvl(relationship.getIntimacy()));
            conflict.put(key, nvl(relationship.getConflict()));
        }
        Map<String, String> orgStates = new LinkedHashMap<>();
        for (DeriveOrganizationDO organization : organizations) {
            orgStates.put(organization.getOrgKey(), organization.getPressure());
        }
        Map<String, String> institutionStates = new LinkedHashMap<>();
        for (DeriveWorldInstitutionDO institution : institutions) {
            institutionStates.put(institution.getInstitutionKey(), "UNPAID");
        }
        Map<String, List<String>> knowledge = new HashMap<>();
        List<LedgerSnapshot> before = new ArrayList<>();
        List<LedgerSnapshot> after = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> unclosedRelationships = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (DerivePressureEventDO event : sorted(events)) {
            if (event.getChapterNo() == null || event.getSeq() == null) {
                errors.add(event.getEventKey() + " 未映射章/拍");
                unresolved.add(event.getEventKey());
                continue;
            }
            before.add(snapshot(event.getChapterNo(), event.getSeq(), trust, intimacy, conflict,
                    arcs, orgStates, institutionStates, knowledge));
            JsonNode changes = read(event.getRelationshipChanges());
            for (JsonNode node : changes) {
                String key = node.path("relationship").asText("");
                if (key.isBlank()) {
                    continue;
                }
                trust.put(key, clamp(nvl(trust.get(key)) + node.path("trustDelta").asInt(0)));
                intimacy.put(key, clamp(nvl(intimacy.get(key)) + node.path("intimacyDelta").asInt(0)));
                conflict.put(key, clamp(nvl(conflict.get(key)) + node.path("conflictDelta").asInt(0)));
            }
            JsonNode characterChanges = read(event.getCharacterChanges());
            for (JsonNode node : characterChanges) {
                String key = node.path("characterKey").asText("");
                if (!key.isBlank() && node.hasNonNull("arcState")) {
                    arcs.put(key, node.path("arcState").asText(arcs.get(key)));
                }
                if (!key.isBlank() && node.hasNonNull("knowledge")) {
                    knowledge.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(node.path("knowledge").asText(""));
                }
            }
            if (event.getInstitutionKey() != null && !event.getInstitutionKey().isBlank()) {
                institutionStates.put(event.getInstitutionKey(), "PAID");
            }
            if (event.getOrganizationKey() != null && !event.getOrganizationKey().isBlank()) {
                orgStates.put(event.getOrganizationKey(), "PRESSED");
            }
            after.add(snapshot(event.getChapterNo(), event.getSeq(), trust, intimacy, conflict,
                    arcs, orgStates, institutionStates, knowledge));
        }
        for (DeriveCharacterRelationshipDO relationship : relationships) {
            String key = relationKey(relationship.getFromCharacterKey(), relationship.getToCharacterKey());
            if (trust.getOrDefault(key, 0) < 0 || conflict.getOrDefault(key, 0) > 80) {
                unclosedRelationships.add(key);
            }
        }
        for (Map.Entry<String, String> entry : institutionStates.entrySet()) {
            if ("UNPAID".equals(entry.getValue())) {
                errors.add("制度代价未发生：" + entry.getKey());
            }
        }
        return new StoryStateLedger(before, after, unresolved, unclosedRelationships,
                new ArrayList<>(institutionStates.keySet()), errors);
    }

    private static List<DerivePressureEventDO> sorted(List<DerivePressureEventDO> events) {
        List<DerivePressureEventDO> out = new ArrayList<>(events == null ? List.of() : events);
        out.sort((a, b) -> {
            int chapter = Integer.compare(nvl(a.getChapterNo()), nvl(b.getChapterNo()));
            return chapter != 0 ? chapter : Integer.compare(nvl(a.getSeq()), nvl(b.getSeq()));
        });
        return out;
    }

    private static LedgerSnapshot snapshot(int chapterNo, int seq, Map<String, Integer> trust,
                                           Map<String, Integer> intimacy, Map<String, Integer> conflict,
                                           Map<String, String> arcs, Map<String, String> orgStates,
                                           Map<String, String> institutionStates,
                                           Map<String, List<String>> knowledge) {
        Map<String, Integer> know = new LinkedHashMap<>(trust);
        Map<String, Integer> knowIntimacy = new LinkedHashMap<>(intimacy);
        Map<String, Integer> knowConflict = new LinkedHashMap<>(conflict);
        Map<String, List<String>> known = new LinkedHashMap<>(knowledge);
        known.replaceAll((key, value) -> List.copyOf(value));
        return new LedgerSnapshot(chapterNo, seq, know, knowIntimacy, knowConflict,
                new LinkedHashMap<>(arcs), new LinkedHashMap<>(orgStates),
                new LinkedHashMap<>(institutionStates), known);
    }

    private static JsonNode read(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "[]" : json);
            return node.isArray() ? node : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    private static String relationKey(String from, String to) {
        return from + "->" + to;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

}