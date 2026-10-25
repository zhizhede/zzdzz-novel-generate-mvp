package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeriveStoryPlanTruthContractTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void spineCannotContradictPersistedTruthContract() throws Exception {
        var scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 12, 2500, List.of());
        var contract = M.readTree(
                "{\"finalTruth\":\"周砚在容器内导致苏晚缺氧并纵火灭迹\","
                        + "\"finalChoice\":\"按唯一真相合同完成最终选择\"}");
        var spine = M.readTree("""
                {"mainMystery":"旧楼失踪案","finalTruth":"陆崇在走廊杀害苏晚",
                 "finalChoice":"调查者选择传出证据","truthLadder":[
                   {"volumeNo":1,"reveals":["真凶落网"],"forbidden":[],"evidenceThreads":[]}],
                 "globalThreads":[]}
                """);

        assertThrows(PlanJson.Bad.class,
                () -> DeriveStoryPlanService.parseSpine(spine, scope, contract));
    }

    @Test
    void volumeTrueAnswerCannotContradictTruthContract() throws Exception {
        var contract = M.readTree("{\"finalTruth\":\"周砚导致苏晚缺氧并纵火灭迹\"}");
        var volumes = M.readTree("""
                {"volumes":[{"volumeNo":1,"fromChapter":1,"toChapter":12,"arc":"旧案",
                  "localQuestion":"真凶是谁","falseAnswer":"自行离开","trueAnswer":"陆崇走廊杀害苏晚",
                  "mainMysteryAdvance":"查明死因","focusCharacters":["C1"],
                  "relationshipChanges":[],"requiredThreads":[],"forbiddenReveals":[],
                  "localClosureProof":"真凶落网","nextHook":"","globalRole":"FINAL"}]}
                """);

        var parsed = DeriveStoryPlanService.parseVolumes(volumes,
                new PlanContracts.ScopeContract(
                        PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 12, 2500, List.of()));

        assertThrows(PlanJson.Bad.class,
                () -> DeriveStoryPlanService.requireTruthAlignment(parsed, contract));
    }

    @Test
    void deterministicContractRebuildKeepsSingleTruth() throws Exception {
        var scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 12, 2500, List.of());
        var contract = M.readTree("""
                {"truthId":"T-1","finalTruth":"周砚导致苏晚缺氧并纵火灭迹",
                 "finalChoice":"按唯一真相合同完成最终选择","forbidden":["不得新增人物"],
                 "evidenceThreads":["GM-CLUE-01"]}
                """);

        var spine = DeriveStoryPlanService.spineFromTruthContract(scope, contract);
        var volume = DeriveStoryPlanService.volumeFromTruthContract(scope, contract);

        assertEquals(contract.path("finalTruth").asText(), spine.finalTruth());
        assertEquals(contract.path("finalTruth").asText(), volume.trueAnswer());
        assertEquals("GM-CLUE-01", spine.globalThreads().get(0).threadKey());
    }}