package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 四卷合同与角色高光合同。 */
class DeriveVolumeCharacterPlanTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fourVolumesMustCoverFortyEightChaptersWithCharacterSpotlights() throws Exception {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.MULTI_VOLUME, 4, 48, 2800, List.of());

        List<PlanContracts.VolumeContract> volumes = DeriveStoryPlanService.parseVolumes(
                json(volumesJson()), scope);
        List<PlanContracts.CharacterContract> chars = DeriveStoryPlanService.parseCharacters(
                json(charactersJson()), scope);

        assertEquals(List.of(1, 13, 25, 37),
                volumes.stream().map(PlanContracts.VolumeContract::fromChapter).toList());
        assertEquals(List.of(2, 3, 4),
                chars.stream().map(PlanContracts.CharacterContract::spotlightVolume).toList());
    }

    @Test
    void nonFinalVolumeWithoutHookFails() throws Exception {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.MULTI_VOLUME, 4, 48, 2800, List.of());
        String broken = volumesJson().replace("\"nextHook\":\"进入第二卷\"", "\"nextHook\":\"\"");

        assertThrows(RuntimeException.class,
                () -> DeriveStoryPlanService.parseVolumes(json(broken), scope));
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private static String volumesJson() {
        return """
                {"volumes":[
                  {"volumeNo":1,"fromChapter":1,"toChapter":12,"arc":"失踪案",
                   "localQuestion":"谁改了名单","falseAnswer":"管理员自导自演","trueAnswer":"名单被内部人员替换",
                   "mainMysteryAdvance":"旧事故真实存在","focusCharacters":["C1"],
                   "relationshipChanges":["REL-C1-C2"],"requiredThreads":["GM-CLUE-01"],
                   "forbiddenReveals":["主谋身份"],"localClosureProof":"名单原件被找到",
                   "nextHook":"进入第二卷","globalRole":"LOCAL"},
                  {"volumeNo":2,"fromChapter":13,"toChapter":24,"arc":"重复事故",
                   "localQuestion":"事故为何重演","falseAnswer":"巧合","trueAnswer":"同一手法被复制",
                   "mainMysteryAdvance":"手法重复","focusCharacters":["C2"],
                   "relationshipChanges":["REL-C1-C2"],"requiredThreads":["GM-CLUE-02"],
                   "forbiddenReveals":["完整动机"],"localClosureProof":"复制手法被证明",
                   "nextHook":"进入第三卷","globalRole":"LOCAL"},
                  {"volumeNo":3,"fromChapter":25,"toChapter":36,"arc":"旧案重启",
                   "localQuestion":"谁在掩盖旧案","falseAnswer":"主角家族","trueAnswer":"主谋借家族掩护",
                   "mainMysteryAdvance":"主角与旧案有关","focusCharacters":["C3"],
                   "relationshipChanges":["REL-C1-C3"],"requiredThreads":["GM-CLUE-03"],
                   "forbiddenReveals":["完整作案链"],"localClosureProof":"旧案责任部分兑现",
                   "nextHook":"进入最终卷","globalRole":"LOCAL"},
                  {"volumeNo":4,"fromChapter":37,"toChapter":48,"arc":"最终真相",
                   "localQuestion":"谁制造最后假结局","falseAnswer":"替身伏法","trueAnswer":"主谋与完整手法兑现",
                   "mainMysteryAdvance":"完整真相","focusCharacters":["C1","C2","C3"],
                   "relationshipChanges":["REL-C1-C2","REL-C1-C3"],"requiredThreads":["GM-CLUE-01"],
                   "forbiddenReveals":[],"localClosureProof":"责任人兑现且关系收束",
                   "nextHook":"","globalRole":"FINAL"}
                ]}
                """;
    }

    private static String charactersJson() {
        return """
                {"characters":[
                  {"characterKey":"C2","displayNameHint":"林雾","function":"ACCESS",
                   "spotlightVolume":2,"conflictVolume":3,"payoffVolume":4,
                   "uniqueContribution":"能进入封锁区","want":"证明自己","flaw":"独自行动",
                   "conflictTargets":["C1"],"functionalConflict":"救人还是先取证",
                   "highlightChoice":"公开旧身份", "highlightCost":"失去同伴信任",
                   "arcBefore":"只靠自己","arcAfter":"学会协作","payoffProof":"主动请求支援"},
                  {"characterKey":"C3","displayNameHint":"周渡","function":"KNOWLEDGE",
                   "spotlightVolume":3,"conflictVolume":2,"payoffVolume":4,
                   "uniqueContribution":"掌握旧档案","want":"保护家人","flaw":"隐瞒事实",
                   "conflictTargets":["C1"],"functionalConflict":"公开真相会伤害家人",
                   "highlightChoice":"交出档案","highlightCost":"家人受审",
                   "arcBefore":"隐瞒换安全","arcAfter":"承担后果","payoffProof":"主动作证"},
                  {"characterKey":"C1","displayNameHint":"顾砚","function":"DETECTIVE",
                   "spotlightVolume":4,"conflictVolume":3,"payoffVolume":4,
                   "uniqueContribution":"串联所有线索","want":"查明旧案","flaw":"执着真相",
                   "conflictTargets":["C2","C3"],"functionalConflict":"真相与保护同伴冲突",
                   "highlightChoice":"公开家族责任","highlightCost":"失去继承身份",
                   "arcBefore":"真相高于一切","arcAfter":"真相必须有人承担","payoffProof":"公开并承担"}
                ]}
                """;
    }
}
