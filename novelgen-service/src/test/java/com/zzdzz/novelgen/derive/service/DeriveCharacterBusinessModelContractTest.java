package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterProfileDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterRelationshipDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePressureEventDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveCharacterBusinessModelContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void labelOnlyCharacterAndMissingDecisionRuleAreRejected() {
        var service = new DeriveCharacterProfileService(null);
        var profile = new DeriveCharacterProfileService.ProfileInput("C1", "主角",
                "偶像", "偶像", "偶像", "", "偶像", "", "", "", "", "", "START");
        List<String> errors = service.validate(profile, List.of("ORG-1"), List.of("INST-1"));
        assertTrue(errors.contains("CHARACTER_LABEL_ONLY"));
        var missing = new DeriveCharacterProfileService.ProfileInput("C1", "主角",
                "完成公开目标", "获得稳定关系", "组织规则和私人目标冲突", "", "ORG-1", "INST-1", "", "", "", "", "START");
        assertTrue(service.validate(missing, List.of("ORG-1"), List.of("INST-1"))
                .contains("CHARACTER_DECISION_RULE_MISSING"));
    }

    @Test
    void relationshipValuesMustStayInRangeAndHaveHistoryOrTurn() {
        var service = new DeriveRelationshipService(null);
        var relation = new DeriveRelationshipService.RelationshipInput("C1", "C2", "ALLY",
                "公开关系", "", "", 120, 20, 20, "", "", "", "", "");
        List<String> errors = service.validate(relation, List.of("C1", "C2"), false, true);
        assertTrue(errors.contains("RELATIONSHIP_VALUE_OUT_OF_RANGE"));
        assertTrue(errors.contains("RELATIONSHIP_NO_HISTORY"));
        assertTrue(errors.contains("RELATIONSHIP_NO_TURN"));
    }

    @Test
    void organizationAndInstitutionRequireOperationalRules() {
        var organization = new DeriveOrganizationService(null);
        var org = new DeriveOrganizationService.OrganizationInput("ORG-1", "COMMUNITY", "共同体",
                "维持资源分配", "保持权力", "资源", "", "", "", List.of("C9"));
        assertTrue(organization.validate(org, List.of("C1")).contains("ORGANIZATION_RULE_MISSING"));
        assertTrue(organization.validate(org, List.of("C1")).contains("ORGANIZATION_UNKNOWN_MEMBER"));

        var institutions = new DeriveWorldInstitutionService(null);
        var institution = new DeriveWorldInstitutionService.InstitutionInput("INST-1", "SOCIAL", "制度",
                "每个人都必须承担共同体代价", "", "", "", List.of());
        assertTrue(institutions.validate(institution, List.of("C1"))
                .contains("INSTITUTION_ENFORCEMENT_MISSING"));
        assertTrue(institutions.validate(institution, List.of("C1"))
                .contains("INSTITUTION_COST_MISSING"));
        assertTrue(institutions.validate(institution, List.of("C1"))
                .contains("INSTITUTION_NO_AFFECTED_CHARACTER"));
    }

    @Test
    void pressureEventRequiresHiddenPressureStateChangeAndMapping() {
        var service = new DerivePressureEventService(null);
        var event = new DerivePressureEventService.PressureEventInput("EVT-1", "CHOICE", "压力",
                "C1", List.of("C2"), null, null, "表面事件", "", "{}", "{}", "", "", null, null);
        List<String> errors = service.validate(event, List.of("C1", "C2"), List.of(), List.of());
        assertTrue(errors.contains("PRESSURE_EVENT_HIDDEN_PRESSURE_MISSING"));
        assertTrue(errors.contains("PRESSURE_EVENT_NO_STATE_CHANGE"));
        assertTrue(errors.contains("PRESSURE_EVENT_NO_RELATION_OR_CHARACTER_CHANGE"));
        assertTrue(errors.contains("PRESSURE_EVENT_UNMAPPED"));
    }

    @Test
    void ledgerReplaysRelationshipAndArcChanges() throws Exception {
        var ledger = new DeriveStoryStateLedgerService();
        DeriveCharacterProfileDO profile = new DeriveCharacterProfileDO();
        profile.setCharacterKey("C1");
        profile.setArcState("START");
        DeriveCharacterRelationshipDO relationship = new DeriveCharacterRelationshipDO();
        relationship.setFromCharacterKey("C1");
        relationship.setToCharacterKey("C2");
        relationship.setTrust(50);
        relationship.setIntimacy(20);
        relationship.setConflict(30);
        DerivePressureEventDO event = new DerivePressureEventDO();
        event.setEventKey("EVT-1");
        event.setChapterNo(1);
        event.setSeq(1);
        event.setRelationshipChanges("[{\"relationship\":\"C1->C2\",\"trustDelta\":20,\"intimacyDelta\":10,\"conflictDelta\":-5}]");
        event.setCharacterChanges("[{\"characterKey\":\"C1\",\"arcState\":\"MIDPOINT\",\"knowledge\":\"规则收紧\"}]");
        event.setInstitutionKey("INST-1");
        var result = ledger.simulate(List.of(profile), List.of(relationship), List.of(), List.of(), List.of(event));
        assertEquals("MIDPOINT", result.after().get(0).characterArc().get("C1"));
        assertEquals(70, result.after().get(0).relationshipTrust().get("C1->C2"));
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void capabilityResolutionAndNonMysteryTruthStatusAreStructured() {
        var service = new DeriveGenreCapabilityService(null);
        var capabilities = service.resolve(List.of("都市偶像", "恋爱"), "舞台与城市制度", "闭合");
        assertTrue(capabilities.contains(DeriveGenreCapabilityService.Capability.WORLD));
        assertTrue(capabilities.contains(DeriveGenreCapabilityService.Capability.IDOL));
        assertTrue(capabilities.contains(DeriveGenreCapabilityService.Capability.ROMANCE));
        assertFalse(capabilities.contains(DeriveGenreCapabilityService.Capability.MYSTERY));
        assertEquals("ENABLED", readStatus(service.snapshotJson(capabilities, service.fusionDecisions(capabilities))));
    }

    private static String readStatus(String json) {
        try {
            return MAPPER.readTree(json).path("status").asText("");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}