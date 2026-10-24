package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanJson;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V2 规模与卷范围判断。 */
class DeriveStoryScaleV2Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void explicitFourVolumesCannotCollapse() throws Exception {
        RunDO run = runWithIntent("轻小说。第一卷：转学。第二卷：A高光。第三卷：B高光。第四卷：C高光。");

        PlanContracts.ScopeContract scope = DeriveStoryPlanService.parseScale(json("""
                {"storyScale":"LONG","volumeCount":4,"chapterCount":48,"chapterAvg":2800,
                 "scopeDecision":"MULTI_VOLUME","scopeEvidence":["第二卷：A高光"]}
                """), run, 104);

        assertEquals(4, scope.volumeCount());
        assertEquals(48, scope.chapterCount());
    }

    @Test
    void explicitFourVolumesRejectThreeVolumeOutput() throws Exception {
        RunDO run = runWithIntent("第一卷：转学。第二卷：A高光。第三卷：B高光。第四卷：C高光。");

        assertThrows(PlanJson.Bad.class, () -> DeriveStoryPlanService.parseScale(json("""
                {"storyScale":"LONG","volumeCount":3,"chapterCount":36,"chapterAvg":2800,
                 "scopeDecision":"MULTI_VOLUME","scopeEvidence":["显式四卷"]}
                """), run, 104));
    }

    @Test
    void vagueOneVolumeIdeaDefaultsToOneVolume() throws Exception {
        RunDO run = runWithIntent("孤岛灯塔管理员失踪，两天内查明真相。");

        PlanContracts.ScopeContract scope = DeriveStoryPlanService.parseScale(json("""
                {"storyScale":"SHORT","volumeCount":1,"chapterCount":10,"chapterAvg":2400,
                 "scopeDecision":"SINGLE_VOLUME","scopeEvidence":["单一封闭案件"]}
                """), run, 24);

        assertEquals(1, scope.volumeCount());
    }

    private static RunDO runWithIntent(String intent) {
        RunDO run = new RunDO();
        run.setId(9L);
        run.setParamIntent(intent);
        run.setParamWordsMin(80000);
        run.setParamWordsMax(160000);
        run.setLenCheck(false);
        return run;
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }
}
