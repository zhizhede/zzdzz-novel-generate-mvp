package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanJson;
import com.zzdzz.novelgen.derive.plan.compiler.RouteNarrativeContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    void legacyStateMapRejectsDuplicateCharacterKeys() {
        DeriveBeatRouteService.RouteContext ctx = context();

        PlanJson.Bad bad = assertThrows(PlanJson.Bad.class,
                () -> DeriveBeatRouteService.parseLegacyStateMap(
                        "{\"C1\":[\"LOCATION\"],\"C1\":[\"PRESENT\"]}",
                        ctx,
                        "$.chapters[0].requiredStateBefore"));

        assertTrue(bad.getMessage().contains("[SCHEMA_DUPLICATE_KEY]"), bad.getMessage());
        assertTrue(bad.getMessage().contains("C1"), bad.getMessage());
    }

    @Test
    void legacyParserAcceptsCanonicalStateKinds() {
        Map<String, List<String>> states = DeriveBeatRouteService.parseLegacyStateMap(
                "{\"C1\":[\"LOCATION\",\"PRESENT\",\"POSSESSION_GAIN\"]}",
                context(),
                "$.chapters[0].requiredStateAfter");

        assertEquals(List.of("LOCATION", "PRESENT", "POSSESSION_GAIN"), states.get("C1"));
    }

    @Test
    void legacyParserRejectsUnknownStateKind() {
        PlanJson.Bad bad = assertThrows(PlanJson.Bad.class,
                () -> DeriveBeatRouteService.parseLegacyStateMap(
                        "{\"C1\":[\"NOT_A_STATE_KIND\"]}",
                        context(),
                        "$.chapters[0].requiredStateBefore"));

        assertTrue(bad.getMessage().contains("[SCHEMA_ENUM_INVALID]"), bad.getMessage());
        assertTrue(bad.getMessage().contains("NOT_A_STATE_KIND"), bad.getMessage());
    }

    @Test
    void legacyParserRejectsUnknownThreadEffectKind() throws Exception {
        String route = """
                {"beatNarratives":[{"beatKey":"B1","goal":"推进",
                  "threadEffects":[{"threadKey":"GM-CLUE-01","effect":"NOT_AN_EFFECT","detail":"x"}]}],
                 "skipped":[]}
                """;
        RouteNarrativeContract.RouteContractKeys keys = new RouteNarrativeContract.RouteContractKeys(
                Set.of("C1"), Set.of("GM-CLUE-01"), Set.of("B1"));

        RouteNarrativeContract.ParseResult result =
                DeriveBeatRouteService.parseLegacyRouteNarrative(json(route), keys);

        assertTrue(result.violations().stream().anyMatch(v ->
                        "SCHEMA_ENUM_INVALID".equals(v.code())
                                && "$.beatNarratives[0].threadEffects[0].effect".equals(v.path())),
                String.valueOf(result.violations()));
    }

    @Test
    void legacyParserRejectsUnknownBeatKey() throws Exception {
        String route = """
                {"beatNarratives":[{"beatKey":"UNKNOWN","goal":"推进"}],"skipped":[]}
                """;
        RouteNarrativeContract.RouteContractKeys keys = new RouteNarrativeContract.RouteContractKeys(
                Set.of("C1"), Set.of("GM-CLUE-01"), Set.of("B1"));

        RouteNarrativeContract.ParseResult result =
                DeriveBeatRouteService.parseLegacyRouteNarrative(json(route), keys);

        assertTrue(result.violations().stream().anyMatch(v ->
                        "DOMAIN_UNKNOWN_BEAT_KEY".equals(v.code())
                                && "$.beatNarratives[0].beatKey".equals(v.path())),
                String.valueOf(result.violations()));
    }

    @Test
    void duplicateBeatFailsButUnknownThreadIsDropped() throws Exception {
        // P6 run45 实弹：模型自由发明 REL-C1-C2（线程账没有）导致 3 轮重试耗尽停摆；
        // 改为机械归一剔除未知线程引用，拍保留，不再整轮拒绝。
        assertThrows(PlanJson.Bad.class,
                () -> DeriveBeatRouteService.parseRoute(json(duplicateSeq()), context()));

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(
                json(unknownThread()), context());

        assertTrue(plan.assignments().stream().allMatch(
                        a -> a.threadRefs().stream().allMatch(
                                r -> context().threadKeys().contains(r))),
                "未知线程引用必须被剔除：" + plan.assignments());
        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
    }

    @Test
    void missingLegalSeqIsRecoveredToNearestChapter() throws Exception {
        String route = validRoute().replace("\"seq\":2,\"chapterNo\":2", "\"seq\":99,\"chapterNo\":2");

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(route), context());

        assertTrue(plan.assignments().stream().anyMatch(a -> a.seq() == 2));
        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
    }

    @Test
    void futureKnowledgeWriterIsPulledBeforeRequirement() throws Exception {
        String route = validRoute()
                .replace("\"seq\":2,\"chapterNo\":2", "\"seq\":2,\"chapterNo\":2")
                .replace("\"requiresKnowledge\":[],\"writesKnowledge\":[\"C1\"]",
                        "\"requiresKnowledge\":[\"未来事实\"],\"writesKnowledge\":[\"C1\"]")
                .replace("\"seq\":1,\"chapterNo\":1", "\"seq\":1,\"chapterNo\":1")
                .replace("\"requiresKnowledge\":[],\"writesKnowledge\":[\"C1\"]",
                        "\"requiresKnowledge\":[],\"writesKnowledge\":[\"C1\"]");

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(route), context());
        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
    }
    @Test
    void unknownSeqIsIgnoredAndStructuralRolesAreSynthesized() throws Exception {
        String extra = "{\"seq\":99,\"chapterNo\":2,\"storyLayer\":\"CHARACTER_ARC\","
                + "\"beatRole\":\"HIGHLIGHT\",\"focusCharacters\":[\"C2\"],"
                + "\"threadRefs\":[],\"dependsOn\":[],\"payoffTarget\":\"\","
                + "\"appearanceMode\":\"LIVE\",\"requiresKnowledge\":[],"
                + "\"writesKnowledge\":[],\"requiresState\":{},\"changesState\":{}},";
        String route = validRoute().replace("\"assignments\":[", "\"assignments\":[" + extra).replace("\"requiredBeatRoles\":[\"HIGHLIGHT\"]", "\"requiredBeatRoles\":[\"HIGHLIGHT\",\"COST\"]");

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(route), context());

        assertTrue(plan.assignments().stream().noneMatch(a -> a.seq() == 99));
        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
        assertTrue(plan.synthetic().stream()
                .anyMatch(s -> s.chapterNo() == 2 && s.beatRole() == PlanContracts.BeatRole.COST));
    }
    @Test
    void syntheticSlotCoversRequiredBeatRole() throws Exception {
        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(
                json(syntheticRoute("抉择", false)), context());

        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
        assertTrue(plan.synthetic().size() >= 1);
        assertTrue(plan.synthetic().stream().anyMatch(item -> "抉择".equals(item.beatType())));
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
    void syntheticSlotRetainsKnowledgeAndStateContract() throws Exception {
        String route = validRoute().replace(
                "\"syntheticSlots\":[]",
                "\"syntheticSlots\":[{\"chapterNo\":2,\"storyLayer\":\"LOCAL_CASE\","
                        + "\"beatRole\":\"HIGHLIGHT\",\"beatType\":\"抉择\","
                        + "\"goal\":\"补齐高光结构\",\"focusCharacters\":[\"C1\"],"
                        + "\"threadRefs\":[\"GM-CLUE-01\"],\"dependsOn\":[1],"
                        + "\"payoffTarget\":\"VC1\",\"appearanceMode\":\"LIVE\","
                        + "\"requiresKnowledge\":[\"名单缺页\"],"
                        + "\"writesKnowledge\":[\"林雾承认进入封锁区\"],"
                        + "\"requiresState\":{},"
                        + "\"changesState\":{\"REL-C1-C2\":\"TRUST_DAMAGED\"}}]");

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(route), context());
        PlanContracts.SyntheticAssignment synthetic = plan.synthetic().stream()
                .filter(s -> s.chapterNo() == 2 && s.beatRole() == PlanContracts.BeatRole.HIGHLIGHT)
                .findFirst()
                .orElseThrow();

        assertEquals(List.of(1), synthetic.dependsOn());
        assertEquals("VC1", synthetic.payoffTarget());
        assertEquals(List.of("名单缺页"), synthetic.requiresKnowledge());
        assertEquals(List.of("林雾承认进入封锁区"), synthetic.writesKnowledge());
        assertEquals("TRUST_DAMAGED",
                synthetic.changesState().get("REL-C1-C2"));
        JsonNode persisted = MAPPER.valueToTree(synthetic);
        assertEquals("林雾承认进入封锁区",
                persisted.path("writesKnowledge").get(0).asText());
        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
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
        List<ChapterScheduleEntry> schedule = List.of(
                new ChapterScheduleEntry(8, 1, false, List.of(),
                        List.of("GM-CLUE-01")));

        var slots = DeriveBeatRouteService.chapterSlots(volume, schedule);
        Set<PlanContracts.BeatRole> roles = slots.stream()
                .flatMap(slot -> slot.requiredBeatRoles().stream())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(8, slots.size());
        assertTrue(roles.contains(PlanContracts.BeatRole.HIGHLIGHT));
        assertTrue(roles.contains(PlanContracts.BeatRole.CHARACTER_CHOICE));
        assertTrue(roles.contains(PlanContracts.BeatRole.COST));
        assertTrue(roles.contains(PlanContracts.BeatRole.LOCAL_CLOSURE));
        assertTrue(roles.contains(PlanContracts.BeatRole.CLUE_PAYOFF), "兑现拍来自日程");
        assertTrue(roles.contains(PlanContracts.BeatRole.FINAL_CLOSURE));
        assertTrue(slots.stream().allMatch(slot -> slot.requiredBeatRoles().size() <= 4));
    }

    @Test
    void chapterSlotsFollowSchedule() {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 13, "值班室失踪", "谁改了记录", "自导失踪", "陆敬为掩盖旧账制造失踪",
                "确认伪证", List.of("C1"), List.of("REL-C1-C2"),
                List.of("GM-CLUE-01"), List.of("主谋"), "真相揭穿",
                "", "FINAL");

        var slots = DeriveBeatRouteService.chapterSlots(volume, schedule13());
        Map<Integer, List<PlanContracts.BeatRole>> roles = new java.util.HashMap<>();
        slots.forEach(slot -> roles.put(slot.chapterNo(), slot.requiredBeatRoles()));

        for (int chapter : List.of(3, 4, 5, 6, 8, 9)) {
            assertTrue(roles.get(chapter).contains(PlanContracts.BeatRole.CLUE_PLANT),
                    "第" + chapter + "章应有 CLUE_PLANT：" + roles.get(chapter));
        }
        for (int chapter = 10; chapter <= 13; chapter++) {
            assertTrue(roles.get(chapter).contains(PlanContracts.BeatRole.CLUE_PAYOFF),
                    "第" + chapter + "章应有 CLUE_PAYOFF：" + roles.get(chapter));
        }
        assertTrue(roles.get(11).contains(PlanContracts.BeatRole.FINAL_REVEAL),
                "揭示章必须有 FINAL_REVEAL");
        assertTrue(roles.get(1).contains(PlanContracts.BeatRole.HOOK));
        assertTrue(roles.get(2).contains(PlanContracts.BeatRole.CASE_SETUP));
        assertTrue(roles.get(4).contains(PlanContracts.BeatRole.FUNCTION_USE));
        assertTrue(slots.stream().allMatch(slot -> slot.requiredBeatRoles().size() <= 4),
                "日程角色优先保留、总数封顶 4");
    }

    @Test
    void strayClueRefsRemoved() throws Exception {
        // 第 1 章 HOOK 引用了第 3 章才该出现的线索：归一必须剔除，且不改变拍数。
        DeriveBeatRouteService.RouteContext ctx = context13(schedule13());

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(
                json(thirteenRoute()), ctx);

        assertTrue(plan.assignments().stream()
                        .filter(a -> a.chapterNo() == 1)
                        .flatMap(a -> a.threadRefs().stream())
                        .noneMatch("GM-CLUE-01"::equals),
                "第 1 章不得保留线索引用：" + plan.assignments());
        assertTrue(DeriveBeatRouteService.validateRoute(plan, ctx).isEmpty(),
                String.valueOf(DeriveBeatRouteService.validateRoute(plan, ctx)));
    }

    @Test
    void missingScheduleBeatsInjected() throws Exception {
        DeriveBeatRouteService.RouteContext ctx = context13(schedule13());

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(
                json(thirteenRoute()), ctx);

        assertTrue(plan.synthetic().stream().anyMatch(item -> item.chapterNo() == 3
                        && item.beatRole() == PlanContracts.BeatRole.CLUE_PLANT
                        && item.threadRefs().contains("GM-CLUE-01")),
                "plant 章必须被机械注入 CLUE_PLANT");
        assertTrue(plan.synthetic().stream().anyMatch(item -> item.chapterNo() == 10
                        && item.beatRole() == PlanContracts.BeatRole.CLUE_PAYOFF
                        && item.threadRefs().contains("GM-CLUE-01")),
                "payoff 章必须被机械注入 CLUE_PAYOFF");
        assertTrue(plan.synthetic().stream().anyMatch(item -> item.chapterNo() == 11
                        && item.beatRole() == PlanContracts.BeatRole.FINAL_REVEAL),
                "揭示章必须被机械注入 FINAL_REVEAL");
        assertTrue(DeriveBeatRouteService.validateRoute(plan, ctx).isEmpty(),
                String.valueOf(DeriveBeatRouteService.validateRoute(plan, ctx)));
    }

    @Test
    void validateRouteFailsWhenPlantMissing() throws Exception {
        DeriveBeatRouteService.RouteContext ctx = context13(schedule13());
        // 只给第二章的拍，缺 plant 章的 CLUE_PLANT：validateRoute 必须报 ROUTE_CLUE_PLANT_MISSING。
        PlanContracts.RoutePlan plan = new PlanContracts.RoutePlan(1,
                DeriveBeatRouteService.chapterSlots(ctx.volume(), schedule13()),
                List.of(), List.of(), List.of());

        List<PlanContracts.PlanIssue> issues = DeriveBeatRouteService.validateRoute(plan, ctx);

        assertTrue(issues.stream().anyMatch(issue ->
                        "ROUTE_CLUE_PLANT_MISSING".equals(issue.code())),
                String.valueOf(issues));
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

        var merged = DeriveBeatRouteService.mergeRequiredSlots(
                List.of(parsed), volume, List.of()).get(0);

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
    void requiredThreadPayoffIsAddedToSyntheticClosure() throws Exception {
        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(validRoute()), context());

        assertTrue(plan.synthetic().stream().anyMatch(item ->
                item.beatRole() == PlanContracts.BeatRole.CLUE_PAYOFF
                        && item.threadRefs().contains("GM-CLUE-01")));
        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
    }

    @Test
    void forcedSkipAssignmentIsDroppedWhenModelEchoesIt() throws Exception {
        DeriveBeatRouteService.RouteContext base = context();
        DeriveBeatRouteService.RouteContext ctx = new DeriveBeatRouteService.RouteContext(
                base.volume(), base.inventory(), base.characterKeys(), base.threadKeys(),
                base.syntheticSeqBase(), java.util.Map.of(3, "原拍含尸体，与无死亡红线冲突"));

        String route = validRoute()
                .replace("\"skipped\":[{\"seq\":3,\"reason\":\"情绪重复，不服务本卷闭合\"}]",
                        "\"skipped\":[]")
                .replace("\"assignments\":[",
                        "\"assignments\":[{\"seq\":3,\"chapterNo\":1,\"storyLayer\":\"LOCAL_CASE\","
                                + "\"beatRole\":\"HOOK\",\"focusCharacters\":[\"C1\"],"
                                + "\"threadRefs\":[\"GM-CLUE-01\"],\"dependsOn\":[],"
                                + "\"payoffTarget\":\"VC1\",\"appearanceMode\":\"LIVE\","
                                + "\"requiresKnowledge\":[],\"writesKnowledge\":[\"C1\"],"
                                + "\"requiresState\":{},\"changesState\":{}},");
        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(route), ctx);

        assertTrue(plan.assignments().stream().noneMatch(a -> a.seq() == 3));
        assertTrue(DeriveBeatRouteService.validateRoute(plan, ctx).isEmpty());
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


    @Test
    void scaffoldChapterAndRoleAliasesAreNormalized() throws Exception {
        DeriveBeatRouteService.RouteContext ctx = new DeriveBeatRouteService.RouteContext(
                new PlanContracts.VolumeContract(
                        1, 1, 2, "失踪案", "谁改了名单", "自导自演", "名单被替换",
                        "旧事故真实存在", List.of("C1"), List.of("REL-C1-C2"),
                        List.of("GM-CLUE-01"), List.of("主谋"), "名单原件被找到",
                        "进入第二卷", "LOCAL"),
                List.of(
                        new DerivePlanService.InvBeat(1, "ARC_SEG", null, 11L, 0,
                                "事件", new java.math.BigDecimal("0.75"), "顾砚发现名单缺页", "开头"),
                        new DerivePlanService.InvBeat(2, "ARC_SEG", null, 11L, 1,
                                "反转", new java.math.BigDecimal("0.75"), "林雾承认进入封锁区", "中段"),
                        new DerivePlanService.InvBeat(3, "ASSET", 21L, null, null,
                                "情绪", new java.math.BigDecimal("0.50"), "周渡拒绝交出档案", "中段")),
                Set.of("C1", "C2", "C3"), Set.of("GM-CLUE-01", "REL-C1-C2"), 4,
                java.util.Map.of(), java.util.Map.of(1, 1, 2, 2));
        String route = validRoute()
                .replace("\"seq\":2,\"chapterNo\":2", "\"seq\":2,\"chapterNo\":99")
                .replace("\"beatRole\":\"HIGHLIGHT\"", "\"beatRole\":\"REVERSAL\"");

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(route), ctx);

        assertTrue(DeriveBeatRouteService.validateRoute(plan, ctx).isEmpty());
        assertTrue(plan.assignments().stream().anyMatch(a -> a.seq() == 2 && a.chapterNo() == 2));
        assertTrue(plan.assignments().stream().anyMatch(a -> a.seq() == 2
                && a.beatRole() == PlanContracts.BeatRole.FALSE_ANSWER));
    }
    @Test
    void relationshipChangesAreAutomaticallyPaidOff() throws Exception {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 2, "失踪案", "谁改了名单", "自导自演", "名单被替换",
                "旧事故真实存在", List.of("C1"), List.of("REL-C1-C2"),
                List.of(), List.of(), "名单原件被找到", "", "FINAL");
        DeriveBeatRouteService.RouteContext ctx = new DeriveBeatRouteService.RouteContext(
                volume, List.of(
                        new DerivePlanService.InvBeat(1, "ARC_SEG", null, 11L, 0,
                                "事件", new java.math.BigDecimal("0.75"), "顾砚发现名单缺页", "开头"),
                        new DerivePlanService.InvBeat(2, "ARC_SEG", null, 11L, 1,
                                "反转", new java.math.BigDecimal("0.75"), "林雾承认进入封锁区", "中段"),
                        new DerivePlanService.InvBeat(3, "ASSET", 21L, null, null,
                                "情绪", new java.math.BigDecimal("0.50"), "周渡拒绝交出档案", "中段")),
                Set.of("C1", "C2", "C3"), Set.of("GM-CLUE-01", "REL-C1-C2"), 4);

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(validRoute()), ctx);

        assertTrue(DeriveBeatRouteService.validateRoute(plan, ctx).isEmpty());
        assertTrue(plan.assignments().stream().anyMatch(a ->
                a.beatRole() == PlanContracts.BeatRole.FINAL_CLOSURE
                        && a.threadRefs().contains("REL-C1-C2"))
                || plan.synthetic().stream().anyMatch(s ->
                s.beatRole() == PlanContracts.BeatRole.FINAL_CLOSURE
                        && s.threadRefs().contains("REL-C1-C2")));
    }
    @Test
    void relationshipThreadShorthandIsNormalized() throws Exception {
        String route = validRoute().replace("\"threadRefs\":[\"REL-C1-C2\"]", "\"threadRefs\":[\"C1-C2：协作关系\"]");

        PlanContracts.RoutePlan plan = DeriveBeatRouteService.parseRoute(json(route), context());

        assertTrue(plan.assignments().stream().anyMatch(a -> a.threadRefs().contains("REL-C1-C2")));
        assertTrue(DeriveBeatRouteService.validateRoute(plan, context()).isEmpty());
    }
    @Test
    void overloadRebalanceMovesOrdinaryBeatButKeepsRequiredRole() {
        var hook = new PlanContracts.ChapterSlotContract(
                1, "HOOK", "开篇", "启动案件", "C1介入", "关系建立",
                List.of(PlanContracts.BeatRole.HOOK), List.of(), Map.of(), Map.of());
        var middle = new PlanContracts.ChapterSlotContract(
                2, "MIDPOINT", "中段", "线索升级", "C2暴露", "关系破裂",
                List.of(PlanContracts.BeatRole.HIGHLIGHT), List.of(), Map.of(), Map.of());
        var assignments = List.of(
                assignment(1, 1, PlanContracts.BeatRole.HOOK),
                assignment(2, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(3, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(4, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(5, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(6, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(7, 1, PlanContracts.BeatRole.MAIN_ADVANCE));
        var synthetic = new PlanContracts.SyntheticAssignment(
                2, PlanContracts.StoryLayer.LOCAL_CASE, PlanContracts.BeatRole.HIGHLIGHT,
                "抉择", "补齐高光", List.of("C2"), List.of(),
                PlanContracts.AppearanceMode.LIVE);
        var plan = new PlanContracts.RoutePlan(
                1, List.of(hook, middle), new java.util.ArrayList<>(assignments),
                List.of(synthetic), List.of());

        var balanced = DeriveBeatRouteService.rebalanceRouteLoad(plan, context());

        assertTrue(balanced.assignments().stream().anyMatch(a ->
                a.seq() == 1 && a.chapterNo() == 1));
        assertTrue(balanced.assignments().stream().anyMatch(a ->
                a.seq() == 2 && a.chapterNo() == 2));
    }

    @Test
    void futureKnowledgeGapInFirstChapterDoesNotCrash() {
        // run45 实弹：首章拍的知识缺口只有后置拍会写时，
        // earlierChapterBeat 返回 -1，直接 get(-1) 抛 Index -1 out of bounds 停掉 auto-flow。
        var need = new PlanContracts.BeatAssignment(
                1, 1, PlanContracts.StoryLayer.LOCAL_CASE, PlanContracts.BeatRole.CASE_SETUP,
                List.of("C1"), List.of(), List.of(), "", PlanContracts.AppearanceMode.LIVE,
                List.of("旧楼门锁的撬痕"), List.of(), Map.of(), Map.of());
        var writer = new PlanContracts.BeatAssignment(
                2, 1, PlanContracts.StoryLayer.LOCAL_CASE, PlanContracts.BeatRole.MAIN_ADVANCE,
                List.of("C1"), List.of(), List.of(), "", PlanContracts.AppearanceMode.LIVE,
                List.of(), List.of("旧楼门锁的撬痕"), Map.of(), Map.of());
        var plan = new PlanContracts.RoutePlan(
                1, List.of(), new java.util.ArrayList<>(List.of(need, writer)),
                List.of(), List.of());

        var repaired = DeriveBeatRouteService.repairKnowledgeCausality(plan);

        assertTrue(repaired.assignments().size() == 2, "拍数不得变化");
        assertTrue(repaired.assignments().get(0).writesKnowledge()
                        .contains("旧楼门锁的撬痕"),
                "缺口必须回退写入最早拍而不是抛 Index -1");
    }
    private static PlanContracts.BeatAssignment assignment(
            int seq, int chapterNo, PlanContracts.BeatRole role) {
        return new PlanContracts.BeatAssignment(
                seq, chapterNo, PlanContracts.StoryLayer.LOCAL_CASE, role,
                List.of("C1"), List.of(), List.of(), "", PlanContracts.AppearanceMode.LIVE,
                List.of(), List.of(), Map.of(), Map.of());
    }    private static Set<Integer> assignedSeqs(PlanContracts.RoutePlan plan) {
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

    /** 13 章日程：plant 3/4/5/6/8/9，payoff 10/11(02,03)/12/13，reveal=11。 */
    static List<ChapterScheduleEntry> schedule13() {
        return List.of(
                entry(1), entry(2),
                entry(3, List.of("GM-CLUE-01"), List.of()),
                entry(4, List.of("GM-CLUE-04"), List.of()),
                entry(5, List.of("GM-CLUE-02"), List.of()),
                entry(6, List.of("GM-CLUE-03"), List.of()),
                entry(7),
                entry(8, List.of("GM-CLUE-05"), List.of()),
                entry(9, List.of("GM-CLUE-06"), List.of()),
                entry(10, List.of(), List.of("GM-CLUE-01")),
                reveal(11, List.of("GM-CLUE-02", "GM-CLUE-03")),
                entry(12, List.of(), List.of("GM-CLUE-04", "GM-CLUE-05")),
                entry(13, List.of(), List.of("GM-CLUE-06")));
    }

    private static ChapterScheduleEntry entry(int chapterNo) {
        return new ChapterScheduleEntry(chapterNo, 1, false, List.of(), List.of());
    }

    private static ChapterScheduleEntry entry(int chapterNo, List<String> plant,
                                              List<String> payoff) {
        return new ChapterScheduleEntry(chapterNo, 1, false, plant, payoff);
    }

    private static ChapterScheduleEntry reveal(int chapterNo, List<String> payoff) {
        return new ChapterScheduleEntry(chapterNo, 1, true, List.of(), payoff);
    }

    private static DeriveBeatRouteService.RouteContext context13(
            List<ChapterScheduleEntry> schedule) {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 13, "值班室失踪", "谁改了记录", "自导失踪", "陆敬为掩盖旧账制造失踪",
                "确认伪证", List.of("C1"), List.of("REL-C1-C2"),
                List.of("GM-CLUE-01"), List.of("主谋"), "真相揭穿",
                "", "FINAL");
        List<DerivePlanService.InvBeat> beats = new java.util.ArrayList<>();
        for (int seq = 1; seq <= 13; seq++) {
            beats.add(new DerivePlanService.InvBeat(seq, "ARC_SEG", null, 11L, seq - 1,
                    "事件", new java.math.BigDecimal("0.75"), "第" + seq + "个原始拍", "中段"));
        }
        return new DeriveBeatRouteService.RouteContext(volume, beats,
                Set.of("C1", "C2", "C3"),
                Set.of("GM-CLUE-01", "GM-CLUE-02", "GM-CLUE-03", "GM-CLUE-04",
                        "GM-CLUE-05", "GM-CLUE-06", "REL-C1-C2"),
                14, java.util.Map.of(), java.util.Map.of(), schedule);
    }

    /** 13 章与 13 拍的合法路由；第 1 章故意引用日程外的 GM-CLUE-01（plant 在第 3 章）。 */
    private static String thirteenRoute() {
        StringBuilder chapters = new StringBuilder();
        StringBuilder assignments = new StringBuilder();
        for (int i = 1; i <= 13; i++) {
            if (i > 1) {
                chapters.append(',');
                assignments.append(',');
            }
            chapters.append("{\"chapterNo\":").append(i)
                    .append(",\"chapterFunction\":\"CH").append(i)
                    .append("\",\"localCaseGoal\":\"第").append(i).append("章局部目标")
                    .append("\",\"mainMysteryAdvance\":\"推进").append(i)
                    .append("\",\"characterGoal\":\"C1行动\",\"relationshipGoal\":\"关系推进\"")
                    .append(",\"requiredBeatRoles\":[],\"forbiddenAppearances\":[]")
                    .append(",\"requiredStateBefore\":{},\"requiredStateAfter\":{}}");
            String role = switch (i) {
                case 1 -> "HOOK";
                case 2 -> "CASE_SETUP";
                case 4 -> "FUNCTION_USE";
                default -> "MAIN_ADVANCE";
            };
            String refs = i == 1 ? "[\"GM-CLUE-01\"]" : "[]";
            assignments.append("{\"seq\":").append(i)
                    .append(",\"chapterNo\":").append(i)
                    .append(",\"storyLayer\":\"LOCAL_CASE\",\"beatRole\":\"").append(role)
                    .append("\",\"focusCharacters\":[\"C1\"],\"threadRefs\":").append(refs)
                    .append(",\"dependsOn\":[],\"payoffTarget\":\"\",\"appearanceMode\":\"LIVE\"")
                    .append(",\"requiresKnowledge\":[],\"writesKnowledge\":[]")
                    .append(",\"requiresState\":{},\"changesState\":{}}");
        }
        return "{\"volumeNo\":1,\"chapters\":[" + chapters + "],\"assignments\":["
                + assignments + "],\"syntheticSlots\":[],\"skipped\":[]}";
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

    @Test
    void rebalanceKeepsKnowledgeAndThreads() {
        var chapter1 = new PlanContracts.ChapterSlotContract(
                1, "推进", "开篇", "启动案件", "C1介入", "关系建立",
                List.of(), List.of(), Map.of(), Map.of());
        var chapter2 = new PlanContracts.ChapterSlotContract(
                2, "高光", "中段", "线索升级", "C2暴露", "关系破裂",
                List.of(PlanContracts.BeatRole.HIGHLIGHT), List.of(), Map.of(), Map.of());
        var knowledge = new PlanContracts.BeatAssignment(
                1, 1, PlanContracts.StoryLayer.LOCAL_CASE, PlanContracts.BeatRole.MAIN_ADVANCE,
                List.of("C1"), List.of(), List.of(), "", PlanContracts.AppearanceMode.LIVE,
                List.of("旧楼门禁记录"), List.of(), Map.of(), Map.of());
        var thread = new PlanContracts.BeatAssignment(
                2, 1, PlanContracts.StoryLayer.MAIN_MYSTERY, PlanContracts.BeatRole.MAIN_ADVANCE,
                List.of("C1"), List.of("REL-C1-C2"), List.of(), "",
                PlanContracts.AppearanceMode.LIVE, List.of(), List.of(), Map.of(), Map.of());
        var assignments = new java.util.ArrayList<>(List.of(knowledge, thread,
                assignment(3, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(4, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(5, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(6, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(7, 1, PlanContracts.BeatRole.MAIN_ADVANCE)));
        var synthetic = new PlanContracts.SyntheticAssignment(
                2, PlanContracts.StoryLayer.LOCAL_CASE, PlanContracts.BeatRole.HIGHLIGHT,
                "抉择", "补齐高光", List.of("C2"), List.of(),
                PlanContracts.AppearanceMode.LIVE);
        var plan = new PlanContracts.RoutePlan(
                1, List.of(chapter1, chapter2), assignments, List.of(synthetic), List.of());

        var balanced = DeriveBeatRouteService.rebalanceRouteLoad(plan, context());

        assertTrue(balanced.assignments().stream()
                .anyMatch(a -> a.seq() == 1 && a.chapterNo() == 1));
        assertTrue(balanced.assignments().stream()
                .anyMatch(a -> a.seq() == 2 && a.chapterNo() == 1));
        assertTrue(balanced.assignments().stream()
                .anyMatch(a -> a.chapterNo() == 2 && !List.of(1, 2).contains(a.seq())),
                "普通拍才可归一，知识/线程拍保持原章");
    }
}
