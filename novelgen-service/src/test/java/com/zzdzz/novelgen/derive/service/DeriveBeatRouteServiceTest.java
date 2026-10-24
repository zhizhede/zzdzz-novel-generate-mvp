package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 章功能槽和语义拍路由。 */
class DeriveBeatRouteServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void routingUsesEachBeatOnceAndMarksUnusedBeatWithReason() throws Exception {
        DeriveBeatRouteService.RouteContext ctx = context();

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(validRoute()), ctx);

        assertTrue(DeriveBeatRouteService.validateRoute(plan, ctx).isEmpty());
        assertEquals(Set.of(1, 2), assignedSeqs(plan));
        assertEquals(1, plan.skipped().size());
    }

    @Test
    void duplicateBeatOrUnknownThreadFails() throws Exception {
        assertThrows(PlanJson.Bad.class,
                () -> DeriveBeatRouteService.parseRoute(json(duplicateSeq()), context()));
        assertThrows(PlanJson.Bad.class,
                () -> DeriveBeatRouteService.parseRoute(json(unknownThread()), context()));
    }

    @Test
    void syntheticSlotCoversRequiredBeatRole() throws Exception {
        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(
                json(syntheticRoute("抉择", false)), context());

        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
        assertEquals(1, plan.synthetic().size());
        assertEquals("抉择", plan.synthetic().get(0).beatType());
    }

    @Test
    void syntheticBeatTypeDefaultsFromRole() throws Exception {
        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(
                json(syntheticRoute("环境铺垫与线索浮现", true)), context());

        assertEquals("抉择", plan.synthetic().get(0).beatType());
        assertEquals("CHARACTER_ARC", plan.synthetic().get(0).storyLayer().name());
        assertTrue(plan.synthetic().get(0).goal().contains("HIGHLIGHT"));
    }

    @Test
    void missingRequiredRolesAreSynthesizedDeterministically() throws Exception {
        String route = validRoute().replace(
                "\"requiredBeatRoles\":[\"HIGHLIGHT\"]",
                "\"requiredBeatRoles\":[\"HIGHLIGHT\",\"COST\"]");

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(route), context());

        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
        assertTrue(plan.synthetic().stream()
                .anyMatch(s -> s.chapterNo() == 2 && s.beatRole() == PlanContracts.BeatRole.COST));
        assertTrue(plan.synthetic().stream()
                .filter(s -> s.chapterNo() == 2 && s.beatRole() == PlanContracts.BeatRole.COST)
                .anyMatch(s -> s.focusCharacters().contains("C2")));
    }

    @Test
    void shortVolumeTemplateStillCarriesHighlightChoiceCostAndClosure() {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 8, "档案室失踪", "周禾是否进入", "自导失踪", "钥匙推回",
                "确认伪证", List.of("C1"), List.of("REL-C1-C2"),
                List.of("GM-CLUE-01"), List.of("主谋"), "周禾现身并接受调解",
                "", "FINAL");

        var slots = DeriveBeatRouteService.chapterSlots(volume);
        Set<PlanContracts.BeatRole> roles = slots.stream()
                .flatMap(slot -> slot.requiredBeatRoles().stream())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(8, slots.size());
        assertTrue(roles.contains(PlanContracts.BeatRole.HIGHLIGHT));
        assertTrue(roles.contains(PlanContracts.BeatRole.CHARACTER_CHOICE));
        assertTrue(roles.contains(PlanContracts.BeatRole.COST));
        assertTrue(roles.contains(PlanContracts.BeatRole.LOCAL_CLOSURE));
        assertTrue(roles.contains(PlanContracts.BeatRole.CLUE_PAYOFF));
        assertTrue(roles.contains(PlanContracts.BeatRole.FINAL_CLOSURE));
        assertTrue(slots.stream().allMatch(slot -> slot.requiredBeatRoles().size() <= 4));
    }

    @Test
    void modelCannotDeleteShortVolumeStructuralSlots() {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 8, "档案室失踪", "周禾是否进入", "自导失踪", "钥匙推回",
                "确认伪证", List.of("C1"), List.of("REL-C1-C2"),
                List.of("GM-CLUE-01"), List.of("主谋"), "周禾现身并接受调解",
                "", "FINAL");
        PlanContracts.ChapterSlotContract parsed = new PlanContracts.ChapterSlotContract(
                7, "MODEL_SLOT", "模型目标", "模型推进", "", "",
                List.of(PlanContracts.BeatRole.HOOK), List.of(), java.util.Map.of(),
                java.util.Map.of());

        var merged = DeriveBeatRouteService.mergeRequiredSlots(List.of(parsed), volume).get(0);

        assertTrue(merged.requiredBeatRoles().contains(PlanContracts.BeatRole.HIGHLIGHT));
        assertTrue(merged.requiredBeatRoles().contains(PlanContracts.BeatRole.CHARACTER_CHOICE));
        assertTrue(merged.requiredBeatRoles().contains(PlanContracts.BeatRole.COST));
        assertEquals("C1", merged.characterGoal());
    }

    @Test
    void redlineConflictingSourceBeatIsSkippedDeterministically() {
        String intent = "现代都市轻小说单案。没有死亡、超自然、第二案件。只写这一卷。";

        assertTrue(DeriveBeatRouteService.conflictSkipReason(
                intent, "[辨识] 幼位辨识出与长姐同记的尸体而绝望").contains("死亡"));
        assertTrue(DeriveBeatRouteService.conflictSkipReason(
                intent, "[惊扰] 幼位两度收到诡异人形").contains("超自然"));
        assertEquals(null, DeriveBeatRouteService.conflictSkipReason(
                intent, "顾川检查门底擦痕与钥匙棉纤维"));
    }

    @Test
    void forcedSkipCoversItsOwnSeqWithoutModelEcho() throws Exception {
        DeriveBeatRouteService.RouteContext base = context();
        DeriveBeatRouteService.RouteContext ctx = new DeriveBeatRouteService.RouteContext(
                base.volume(), base.inventory(), base.characterKeys(), base.threadKeys(),
                base.syntheticSeqBase(), java.util.Map.of(3, "原拍含尸体，与无死亡红线冲突"));

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(
                json(validRoute().replace("\"skipped\":[{\"seq\":3,\"reason\":\"情绪重复，不服务本卷闭合\"}]",
                        "\"skipped\":[]")), ctx);

        assertTrue(DeriveBeatRouteService.validateRoute(plan, ctx).isEmpty());
        assertTrue(plan.skipped().stream().anyMatch(skip -> skip.seq() == 3
                && skip.reason().contains("无死亡")));
    }

    private static Set<Integer> assignedSeqs(PlanContracts.RoutePlan plan) {
        return plan.assignments().stream()
                .map(PlanContracts.BeatAssignment::seq)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static DeriveBeatRouteService.RouteContext context() {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 2, "失踪案", "谁改了名单", "自导自演", "名单被替换",
                "旧事故真实存在", List.of("C1"), List.of("REL-C1-C2"),
                List.of("GM-CLUE-01"), List.of("主谋"), "名单原件被找到",
                "进入第二卷", "LOCAL");
        List<DerivePlanService.InvBeat> beats = List.of(
                new DerivePlanService.InvBeat(1, "ARC_SEG", null, 11L, 0,
                        "事件", new java.math.BigDecimal("0.75"), "顾砚发现名单缺页", "开头"),
                new DerivePlanService.InvBeat(2, "ARC_SEG", null, 11L, 1,
                        "反转", new java.math.BigDecimal("0.75"), "林雾承认进入封锁区", "中段"),
                new DerivePlanService.InvBeat(3, "ASSET", 21L, null, null,
                        "情绪", new java.math.BigDecimal("0.50"), "周渡拒绝交出档案", "中段"));
        return new DeriveBeatRouteService.RouteContext(
                volume, beats, Set.of("C1", "C2", "C3"),
                Set.of("GM-CLUE-01", "REL-C1-C2"), 4);
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private static String validRoute() {
        return """
                {"volumeNo":1,"chapters":[
                  {"chapterNo":1,"chapterFunction":"HOOK","localCaseGoal":"发现名单缺页",
                   "mainMysteryAdvance":"出现旧事故编号","characterGoal":"顾砚介入",
                   "relationshipGoal":"两人试探合作","requiredBeatRoles":["HOOK"],
                   "forbiddenAppearances":[],"requiredStateBefore":{},"requiredStateAfter":{}},
                  {"chapterNo":2,"chapterFunction":"MIDPOINT_REVERSAL","localCaseGoal":"林雾承认进入封锁区",
                   "mainMysteryAdvance":"线索升级","characterGoal":"C2暴露旧身份",
                   "relationshipGoal":"信任破裂","requiredBeatRoles":["HIGHLIGHT"],
                   "forbiddenAppearances":[],"requiredStateBefore":{},"requiredStateAfter":{}}
                 ],
                 "assignments":[
                  {"seq":1,"chapterNo":1,"storyLayer":"LOCAL_CASE","beatRole":"HOOK",
                   "focusCharacters":["C1"],"threadRefs":["GM-CLUE-01"],"dependsOn":[],
                   "payoffTarget":"VC1","appearanceMode":"LIVE","requiresKnowledge":[],
                   "writesKnowledge":["C1"],"requiresState":{},"changesState":{}},
                  {"seq":2,"chapterNo":2,"storyLayer":"CHARACTER_ARC","beatRole":"HIGHLIGHT",
                   "focusCharacters":["C2"],"threadRefs":["REL-C1-C2"],"dependsOn":[1],
                   "payoffTarget":"VC1","appearanceMode":"LIVE","requiresKnowledge":[],
                   "writesKnowledge":["C1"],"requiresState":{},"changesState":{}}
                 ],
                 "syntheticSlots":[],
                 "skipped":[{"seq":3,"reason":"情绪重复，不服务本卷闭合"}]}
                """;
    }

    private static String duplicateSeq() {
        return validRoute().replace("\"seq\":2,\"chapterNo\":2", "\"seq\":1,\"chapterNo\":2");
    }

    private static String unknownThread() {
        return validRoute().replace("\"GM-CLUE-01\"", "\"GM-CLUE-404\"");
    }

    private static String syntheticRoute(String rawBeatType, boolean omitGoalAndLayer) {
        String beatType = rawBeatType == null ? ""
                : "\"beatType\":\"" + rawBeatType + "\",";
        String storyLayer = omitGoalAndLayer ? "" : "\"storyLayer\":\"CHARACTER_ARC\",";
        String goal = omitGoalAndLayer ? "" : "\"goal\":\"林雾公开旧身份\",";
        return validRoute()
                .replace("\"GM-CLUE-404\"", "\"GM-CLUE-01\"")
                .replace("\"syntheticSlots\":[]",
                        "\"syntheticSlots\":[{\"chapterNo\":2," + storyLayer
                                + "\"beatRole\":\"HIGHLIGHT\"," + beatType
                                + goal + "\"focusCharacters\":[\"C2\"],"
                                + "\"threadRefs\":[\"REL-C1-C2\"],\"appearanceMode\":\"LIVE\"}]");
    }
}
