package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterProfileDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterRelationshipDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreCapabilityDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveOrganizationDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePressureEventDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveWorldInstitutionDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 编排阶段 B~E：能力包、制度/组织、角色档案、关系事实和压力事件。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveCharacterBusinessModelService {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final DeriveGenreCapabilityService capabilityService;
    private final DeriveCharacterProfileService profileService;
    private final DeriveRelationshipService relationshipService;
    private final DeriveOrganizationService organizationService;
    private final DeriveWorldInstitutionService institutionService;
    private final DerivePressureEventService pressureEventService;
    private final DeriveStoryStateLedgerService ledgerService;
    private final DeriveContractService contractService;

    public record BusinessModel(long runId, List<DeriveGenreCapabilityService.Capability> capabilities,
                                List<DeriveCharacterProfileDO> profiles,
                                List<DeriveCharacterRelationshipDO> relationships,
                                List<DeriveOrganizationDO> organizations,
                                List<DeriveWorldInstitutionDO> institutions,
                                List<DerivePressureEventDO> events) {
    }

    /** 能力包必须在采样落 run 后立即建立。 */
    public List<DeriveGenreCapabilityService.Capability> initializeCapabilities(long runId,
            List<String> tags, String intent, String outcome) {
        List<DeriveGenreCapabilityService.Capability> enabled =
                capabilityService.ensure(runId, tags, intent, outcome);
        List<ObjectNode> decisions = capabilityService.fusionDecisions(enabled);
        contractService.mergeBusinessModel(runId, capabilityService.snapshotJson(enabled, decisions));
        return enabled;
    }

    /** SCALE 后、SPINE 前生成制度与组织；校验失败不得进入 SPINE。 */
    public BusinessModel ensureWorldOrganizations(long runId, List<PlanContracts.CharacterContract> characters,
                                                  PlanContracts.ScopeContract scope) {
        List<DeriveGenreCapabilityService.Capability> capabilities = capabilityService.enabled(runId);
        if (capabilities.isEmpty()) {
            capabilities = initializeCapabilities(runId, List.of("都市"), "", "");
        }
        List<String> keys = characterKeys(characters);
        if (keys.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色槽位为空，无法建立制度/组织");
        }
        List<DeriveWorldInstitutionDO> institutions = institutionService.load(runId);
        if (institutions.isEmpty()) {
            DeriveWorldInstitutionService.InstitutionInput input =
                    new DeriveWorldInstitutionService.InstitutionInput("INST-WORLD-1", "SOCIAL",
                            "共同生活秩序", "每个人都必须为共同体的选择付出可见代价",
                            "由组织、舆论和日常规则共同执行", "让资源与保护可获得",
                            "个人目标会被共同体追责", keys);
            institutions.add(institutionService.save(runId, input, keys));
        }
        List<DeriveOrganizationDO> organizations = organizationService.load(runId);
        if (organizations.isEmpty()) {
            DeriveOrganizationService.OrganizationInput input =
                    new DeriveOrganizationService.OrganizationInput("ORG-1", "COMMUNITY",
                            "故事共同体", "维持共同生活与资源分配", "确保组织自身继续掌权",
                            "成员互助与稀缺资源", "重大决定必须由受影响成员共同见证",
                            "规则会让秘密和旧债暴露", "用审查、降权或驱逐施压", keys);
            organizations.add(organizationService.save(runId, input, keys));
        }
        validateCapabilityWorld(capabilities, institutions, organizations, keys, scope);
        List<ObjectNode> decisions = capabilityService.fusionDecisions(capabilities);
        contractService.mergeBusinessModel(runId, capabilityService.snapshotJson(capabilities, decisions));
        return snapshot(runId);
    }

    /** SPINE 后、卷规划前生成档案、关系和压力事件；全部结构化落库。 */
    public BusinessModel ensureCharactersRelationshipsEvents(long runId,
            List<PlanContracts.CharacterContract> characters, PlanContracts.ScopeContract scope) {
        List<DeriveGenreCapabilityService.Capability> capabilities = capabilityService.enabled(runId);
        List<String> keys = characterKeys(characters);
        List<DeriveCharacterProfileDO> profiles = profileService.load(runId);
        if (profiles.isEmpty()) {
            for (PlanContracts.CharacterContract character : characters) {
                DeriveCharacterProfileService.ProfileInput input = toProfile(character);
                profiles.add(profileService.save(runId, input, orgKeys(organizationService.load(runId)),
                        institutionKeys(institutionService.load(runId))));
            }
        }
        List<String> profileKeys = profileKeys(profiles);
        List<DeriveCharacterRelationshipDO> relationships = relationshipService.load(runId);
        if (relationships.isEmpty()) {
            for (int i = 0; i + 1 < profileKeys.size(); i++) {
                String from = profileKeys.get(i);
                String to = profileKeys.get(i + 1);
                boolean romance = capabilities.contains(DeriveGenreCapabilityService.Capability.ROMANCE);
                DeriveRelationshipService.RelationshipInput input = new DeriveRelationshipService.RelationshipInput(
                        from, to, romance ? "ROMANTIC" : "ALLY",
                        "公开可见的合作或亲密关系", "隐藏的债务、秘密或冲突", "一次未清偿的共同代价",
                        55, romance ? 45 : 30, 20,
                        "两人曾共同承担一次不可撤销的选择",
                        "一次选择让彼此的目标出现分歧",
                        romance ? "以代价换取的相互理解" : "关系在冲突后重新定位",
                        "{\"trust\":55,\"intimacy\":45,\"conflict\":20}",
                        "{\"trust\":70,\"intimacy\":50,\"conflict\":10}");
                relationships.add(relationshipService.save(runId, input, profileKeys, romance, true));
            }
        }
        List<DerivePressureEventDO> events = pressureEventService.load(runId);
        if (events.isEmpty()) {
            List<DeriveOrganizationDO> organizations = organizationService.load(runId);
            List<DeriveWorldInstitutionDO> institutions = institutionService.load(runId);
            String trigger = profileKeys.isEmpty() ? null : profileKeys.get(0);
            List<String> targets = profileKeys;
            DerivePressureEventService.PressureEventInput input =
                    new DerivePressureEventService.PressureEventInput("EVT-1", "CHOICE_PRESSURE",
                            "共同体代价事件", trigger, targets,
                            organizations.isEmpty() ? null : organizations.get(0).getOrgKey(),
                            institutions.isEmpty() ? null : institutions.get(0).getInstitutionKey(),
                            "一件公开的共同体事件", "角色必须在私人目标与共同代价之间选择",
                            "{\"arcState\":\"START\",\"trust\":55}",
                            "{\"arcState\":\"MIDPOINT\",\"trust\":70}",
                            "[{\"relationship\":\"" + firstRelation(relationships) + "\",\"trustDelta\":15,\"intimacyDelta\":5,\"conflictDelta\":-10}]",
                            "[{\"characterKey\":\"" + trigger + "\",\"arcState\":\"MIDPOINT\",\"knowledge\":\"组织规则正在收紧\"}]",
                            1, 1);
            events.add(pressureEventService.save(runId, input, profileKeys,
                    orgKeys(organizations), institutionKeys(institutions)));
        }
        validateCapabilityRelations(capabilities, relationships, events, profiles, organizationService.load(runId),
                institutionService.load(runId));
        DeriveStoryStateLedgerService.StoryStateLedger ledger = ledgerService.simulate(profiles, relationships,
                organizationService.load(runId), institutionService.load(runId), events);
        if (!ledger.errors().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "状态账本未闭合：" + String.join("、", ledger.errors()));
        }
        List<ObjectNode> decisions = capabilityService.fusionDecisions(capabilities);
        contractService.mergeBusinessModel(runId, capabilityService.snapshotJson(capabilities, decisions));
        return new BusinessModel(runId, capabilities, profiles, relationships,
                organizationService.load(runId), institutionService.load(runId), events);
    }

    /** P6-A 确定性检查：不调用 LLM，缺失即返回错误码。 */
    public List<String> validateStatic(long runId) {
        List<String> errors = new ArrayList<>();
        List<DeriveCharacterProfileDO> profiles = profileService.load(runId);
        List<DeriveCharacterRelationshipDO> relationships = relationshipService.load(runId);
        List<DeriveOrganizationDO> organizations = organizationService.load(runId);
        List<DeriveWorldInstitutionDO> institutions = institutionService.load(runId);
        List<DerivePressureEventDO> events = pressureEventService.load(runId);
        List<DeriveGenreCapabilityService.Capability> capabilities = capabilityService.enabled(runId);
        for (DeriveCharacterProfileDO profile : profiles) {
            if (blank(profile.getPublicGoal()) || blank(profile.getPrivateNeed())
                    || blank(profile.getContradiction()) || blank(profile.getDecisionRule())) {
                errors.add("P6A_PROFILE_INCOMPLETE:" + profile.getCharacterKey());
            }
        }
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.WORLD)
                && (organizations.isEmpty() || institutions.isEmpty())) {
            errors.add("P6A_WORLD_MISSING");
        }
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.ROMANCE)
                && relationships.stream().allMatch(r -> blank(r.getPayoff()))) {
            errors.add("P6A_ROMANCE_ARC_MISSING");
        }
        if (events.isEmpty()) {
            errors.add("P6A_PRESSURE_EVENT_MISSING");
        }
        for (DerivePressureEventDO event : events) {
            if (event.getChapterNo() == null || event.getSeq() == null) {
                errors.add("P6A_EVENT_UNMAPPED:" + event.getEventKey());
            }
        }
        return errors;
    }

    /** 为 ⑥b 提示词注入结构化业务模型；禁止退化成标签。 */
    public ObjectNode context(long runId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("genreCapabilities", MAPPER.valueToTree(capabilityService.enabled(runId)));
        root.set("characterProfiles", MAPPER.valueToTree(profileService.load(runId)));
        root.set("relationshipFacts", MAPPER.valueToTree(relationshipService.load(runId)));
        root.set("organizations", MAPPER.valueToTree(organizationService.load(runId)));
        root.set("worldInstitutions", MAPPER.valueToTree(institutionService.load(runId)));
        root.set("pressureEvents", MAPPER.valueToTree(pressureEventService.load(runId)));
        root.put("directives", "每章必须交代本章事件、表面事件、隐藏压力、角色选择、关系变化、制度代价和组织压力；不得只写 HOOK/CLUE_PLANT/RELATION_CONFLICT。");
        return root;
    }

    private static void validateCapabilityWorld(List<DeriveGenreCapabilityService.Capability> capabilities,
                                                 List<DeriveWorldInstitutionDO> institutions,
                                                 List<DeriveOrganizationDO> organizations,
                                                 List<String> keys, PlanContracts.ScopeContract scope) {
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.WORLD)
                && (institutions.isEmpty() || organizations.isEmpty())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "WORLD 能力要求制度与组织");
        }
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.IDOL)) {
            for (DeriveOrganizationDO organization : organizations) {
                if (blank(organization.getResource()) || blank(organization.getWeakness())) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "IDOL 能力要求舞台资源与组织弱点");
                }
            }
        }
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.EPIC_FINALE)) {
            for (DeriveWorldInstitutionDO institution : institutions) {
                if (blank(institution.getLawOrDoctrine()) || blank(institution.getCost())) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "EPIC_FINALE 要求宏观制度与代价");
                }
            }
        }
    }

    private static void validateCapabilityRelations(List<DeriveGenreCapabilityService.Capability> capabilities,
                                                     List<DeriveCharacterRelationshipDO> relationships,
                                                     List<DerivePressureEventDO> events,
                                                     List<DeriveCharacterProfileDO> profiles,
                                                     List<DeriveOrganizationDO> organizations,
                                                     List<DeriveWorldInstitutionDO> institutions) {
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.ROMANCE)) {
            boolean arc = relationships.stream().anyMatch(r -> !blank(r.getPayoff())
                    && !blank(r.getInitialState()) && !blank(r.getCurrentTurn()));
            if (!arc) {
                throw new BizException(ErrorCode.PARAM_ERROR, "ROMANCE 能力要求关系弧");
            }
        }
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.FANTASY)
                && profiles.stream().allMatch(p -> blank(p.getCapability()))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "FANTASY 能力要求能力代价/限制");
        }
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.IDOL)
                && (organizations.stream().allMatch(o -> blank(o.getResource()))
                        || profiles.stream().allMatch(p -> blank(p.getContradiction())))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "IDOL 能力要求舞台资源与职业/私人矛盾");
        }
        if (capabilities.contains(DeriveGenreCapabilityService.Capability.EPIC_FINALE)
                && (institutions.isEmpty() || relationships.stream().allMatch(r -> blank(r.getPayoff())))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "EPIC_FINALE 要求宏观制度和个人 payoff 同时闭合");
        }
        if (events.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "压力事件不能为空");
        }
    }

    private static DeriveCharacterProfileService.ProfileInput toProfile(PlanContracts.CharacterContract character) {
        String want = character.want() == null ? "完成公开目标" : character.want();
        String flaw = character.flaw() == null ? "无法独自承担选择" : character.flaw();
        String conflict = character.functionalConflict() == null ? "组织规则和私人需求冲突" : character.functionalConflict();
        String decision = character.highlightChoice() == null ? "在代价出现时仍保护重要关系"
                : character.highlightChoice();
        return new DeriveCharacterProfileService.ProfileInput(character.characterKey(),
                character.displayNameHint(), want, "获得能保护关系的稳定生活", conflict,
                character.flaw(), "ORG-1", "INST-WORLD-1", "", decision,
                "短句、直接、面对压力时先保护他人", "先确认代价，再作出不可撤销选择",
                "START");
    }

    private static List<String> characterKeys(List<PlanContracts.CharacterContract> characters) {
        List<String> out = new ArrayList<>();
        for (PlanContracts.CharacterContract character : characters) {
            if (!blank(character.characterKey())) {
                out.add(character.characterKey());
            }
        }
        return out;
    }

    private static List<String> profileKeys(List<DeriveCharacterProfileDO> profiles) {
        List<String> out = new ArrayList<>();
        for (DeriveCharacterProfileDO profile : profiles) {
            out.add(profile.getCharacterKey());
        }
        return out;
    }

    private static List<String> orgKeys(List<DeriveOrganizationDO> rows) {
        List<String> out = new ArrayList<>();
        for (DeriveOrganizationDO row : rows) {
            out.add(row.getOrgKey());
        }
        return out;
    }

    private static List<String> institutionKeys(List<DeriveWorldInstitutionDO> rows) {
        List<String> out = new ArrayList<>();
        for (DeriveWorldInstitutionDO row : rows) {
            out.add(row.getInstitutionKey());
        }
        return out;
    }

    private static String firstRelation(List<DeriveCharacterRelationshipDO> relationships) {
        return relationships.isEmpty() ? "" : relationships.get(0).getFromCharacterKey() + "->" + relationships.get(0).getToCharacterKey();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BusinessModel snapshot(long runId) {
        return new BusinessModel(runId, capabilityService.enabled(runId), profileService.load(runId),
                relationshipService.load(runId), organizationService.load(runId),
                institutionService.load(runId), pressureEventService.load(runId));
    }
}
