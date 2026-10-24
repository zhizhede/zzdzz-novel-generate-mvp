package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 四卷×12章完整规划 fixture。 */
class DeriveStoryPlanValidationFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validFixturePassesStructureArcStateAndClosureChecks() throws Exception {
        JsonNode fixture = resource("/derive/story_plan_four_volumes_12.json");
        RunDO run = new RunDO();
        run.setParamIntent("第一卷：转学。第二卷：A高光。第三卷：B高光。第四卷：C高光。");
        run.setParamWordsMin(80000);
        run.setParamWordsMax(160000);
        run.setLenCheck(false);

        PlanContracts.ScopeContract scope = DeriveStoryPlanService.parseScale(
                fixture.path("scope"), run, 120);
        PlanContracts.StorySpine spine = DeriveStoryPlanService.parseSpine(
                fixture.path("spine"), scope);
        List<PlanContracts.VolumeContract> volumes = DeriveStoryPlanService.parseVolumes(
                fixture.path("volumes"), scope);
        List<PlanContracts.CharacterContract> chars = DeriveStoryPlanService.parseCharacters(
                fixture.path("characters"), scope);

        assertEquals(4, scope.volumeCount());
        assertEquals(48, scope.chapterCount());
        assertEquals(4, spine.truthLadder().size());
        assertEquals(List.of(1, 13, 25, 37),
                volumes.stream().map(PlanContracts.VolumeContract::fromChapter).toList());
        assertEquals(List.of(2, 3, 4),
                chars.stream().map(PlanContracts.CharacterContract::spotlightVolume).toList());
        assertTrue(DerivePlanValidationService.validateVolumeHooks(volumes).isEmpty());
        assertTrue(DerivePlanValidationService.validateThreadClosure(
                spine.globalThreads(), Set.of("GM-CLUE-01")).isEmpty());
        assertTrue(DerivePlanValidationService.validateAppearances(
                appearanceSteps(fixture), Map.of("C2",
                        new DerivePlanValidationService.CharacterState(true, Set.of())))
                .isEmpty());
    }

    @Test
    void invalidFixtureRejectsLiveAppearanceAfterDeath() throws Exception {
        JsonNode fixture = resource("/derive/story_plan_four_volumes_12_invalid.json");

        var issues = DerivePlanValidationService.validateAppearances(
                appearanceSteps(fixture), Map.of("C2",
                        new DerivePlanValidationService.CharacterState(true, Set.of())));

        assertTrue(issues.stream().anyMatch(i -> i.code().equals("CHARACTER_NOT_ALIVE")));
    }

    private static List<DerivePlanValidationService.AppearanceStep> appearanceSteps(JsonNode fixture) {
        List<DerivePlanValidationService.AppearanceStep> out = new ArrayList<>();
        for (JsonNode node : fixture.path("appearanceSteps")) {
            out.add(new DerivePlanValidationService.AppearanceStep(
                        node.path("chapterNo").asInt(),
                        node.path("characterKey").asText(),
                        PlanContracts.AppearanceMode.valueOf(node.path("mode").asText()),
                        List.of(), List.of(), node.path("endsDead").asBoolean(), null));
        }
        return out;
    }

    private static JsonNode resource(String path) throws Exception {
        try (var in = DeriveStoryPlanValidationFixtureTest.class.getResourceAsStream(path)) {
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
