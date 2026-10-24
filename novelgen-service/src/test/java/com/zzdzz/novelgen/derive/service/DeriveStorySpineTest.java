package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 故事脊柱与全局线程合同。 */
class DeriveStorySpineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void spineRequiresOneTruthStepPerVolumeAndThreadPayoff() throws Exception {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.MULTI_VOLUME, 4, 48, 2800, List.of());

        PlanContracts.StorySpine spine = DeriveStoryPlanService.parseSpine(
                json(validSpine()), scope);

        assertEquals(4, spine.truthLadder().size());
        assertEquals(1, spine.globalThreads().size());
        assertEquals(PlanContracts.ThreadStatus.OPEN, spine.globalThreads().get(0).status());
    }

    @Test
    void missingVolumeFourFails() throws Exception {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.MULTI_VOLUME, 4, 48, 2800, List.of());

        assertThrows(PlanJson.Bad.class,
                () -> DeriveStoryPlanService.parseSpine(json(missingVolumeFour()), scope));
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private static String validSpine() {
        return """
                {
                  "mainMystery":"十年前海难名单被篡改",
                  "finalTruth":"主谋利用旧事故掩盖失踪名单",
                  "finalChoice":"主角公开真相并承担家族责任",
                  "truthLadder":[
                    {"volumeNo":1,"reveals":["旧事故真实存在"],"forbidden":["主谋身份"],"evidenceThreads":["GM-CLUE-01"]},
                    {"volumeNo":2,"reveals":["作案手法重复"],"forbidden":["完整动机"],"evidenceThreads":["GM-CLUE-02"]},
                    {"volumeNo":3,"reveals":["主角与旧案有关"],"forbidden":["完整作案链"],"evidenceThreads":["GM-CLUE-03"]},
                    {"volumeNo":4,"reveals":["主谋动机与手法兑现"],"forbidden":[],"evidenceThreads":["GM-CLUE-01","GM-CLUE-02","GM-CLUE-03"]}
                  ],
                  "globalThreads":[
                    {"threadKey":"GM-CLUE-01","kind":"CLUE","title":"缺失的名单页",
                     "openedVolume":1,"payoffVolume":4,"ownerCharacterKey":"C1",
                     "contract":{"plantedChapter":3,"payoffChapter":43}}
                  ]
                }
                """;
    }

    private static String missingVolumeFour() {
        return """
                {
                  "mainMystery":"旧案",
                  "finalTruth":"真相",
                  "finalChoice":"选择",
                  "truthLadder":[
                    {"volumeNo":1,"reveals":["A"],"forbidden":[],"evidenceThreads":[]},
                    {"volumeNo":2,"reveals":["B"],"forbidden":[],"evidenceThreads":[]},
                    {"volumeNo":3,"reveals":["C"],"forbidden":[],"evidenceThreads":[]}
                  ],
                  "globalThreads":[]
                }
                """;
    }
}
