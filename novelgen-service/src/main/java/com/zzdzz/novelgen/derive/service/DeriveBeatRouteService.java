package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanJson;
import com.zzdzz.novelgen.derive.plan.PlanJsonViews;
import com.zzdzz.novelgen.derive.plan.PlanV2StateCodec;
import com.zzdzz.novelgen.derive.plan.StoryPlanningPrompts;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.vo.derive.BeatRouteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑥a 拍路由：把无指向拍匹配到 12 章功能槽，支持补拍、跳过和单卷重规划。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveBeatRouteService {

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();
    private static final ObjectMapper MAPPER = STATIC_MAPPER;
    private static final Pattern CHARACTER_KEY = Pattern.compile("C\\d+");

    private final RunMapper runMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final AssetOutlineMapper outlineMapper;
    private final AssetBeatMapper beatMapper;
    private final LabelIndexMapper labelIndexMapper;
    private final CharacterPlanMapper characterPlanMapper;
    private final StoryThreadMapper storyThreadMapper;
    private final VolumePlanMapper volumePlanMapper;
    private final LlmJson llmJson;
    private final PlatformTransactionManager transactionManager;

    /** 单卷路由；解析成功后才在短事务内替换该卷路由。 */
    public BeatRouteVO routeVolume(long runId, int volumeNo) {
        return routeVolume(runId, volumeNo, "");
    }

    /** 单卷路由；feedback 为上一版被规划硬校验拒绝时的返工问题。 */
    BeatRouteVO routeVolume(long runId, int volumeNo, String feedback) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!("PLANNING".equals(run.getStatus()) || "REWORK".equals(run.getStatus()))) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "拍路由需要 run 处于 PLANNING/REWORK（当前 " + run.getStatus() + "）");
        }
        PlanContracts.PlanV2State state = readPlanState(run.getStepStatus());
        if (state.scope() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "拍路由前必须先完成 SPINE/VOLUMES/CHARACTERS");
        }
        PlanContracts.VolumeContract volume = loadVolume(runId, volumeNo);
        RouteContext context = buildContext(run, state, volume);
        String user = buildRouteUser(run, volume, context, feedback);
        PlanContracts.RoutePlan plan = llmJson.ask(
                new LlmPort.ChatRequest(StoryPlanningPrompts.NODE_ROUTE, run.getNovelId(), null,
                        runId,
                        List.of(LlmPort.Message.system(StoryPlanningPrompts.ROUTE),
                                LlmPort.Message.user(user)),
                        0.25),
                root -> parseRoute(root, context), 3);
        plan = repairKnowledgeCausality(plan);
        List<PlanContracts.PlanIssue> issues = validateRoute(plan, context);
        if (!issues.isEmpty()) {
            throw new PlanJson.Bad(issues.get(0).message());
        }
        persistRoute(run, state, volume, plan, context);
        return new BeatRouteVO(runId, volumeNo, volume.fromChapter(), volume.toChapter(),
                plan.chapters().size(), plan.assignments().size(), plan.synthetic().size(),
                plan.skipped().size(), List.of(), true);
    }

    /** 单卷重规划：只清目标卷拍与章功能合同。 */
    public BeatRouteVO replanVolume(long runId, int volumeNo) {
        return replanVolume(runId, volumeNo, "");
    }

    /** 单卷重规划，并把上一版硬校验问题一并喂回路由模型。 */
    BeatRouteVO replanVolume(long runId, int volumeNo, String feedback) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 未挂成品书");
        }
        long drafted = chapterMapper.selectCount(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, run.getNovelId())
                .eq(ChapterDO::getVolumeNo, volumeNo)
                .isNotNull(ChapterDO::getContent)
                .ne(ChapterDO::getContent, ""));
        if (drafted > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该卷已有正文，拒绝自动重规划");
        }
        return routeVolume(runId, volumeNo, feedback);
    }

    /** 路由结构校验，供持久化与单测共用。 */
    static List<PlanContracts.PlanIssue> validateRoute(
            PlanContracts.RoutePlan plan, RouteContext ctx) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        int expected = ctx.volume().toChapter() - ctx.volume().fromChapter() + 1;
        if (plan.chapters().size() != expected) {
            issues.add(issue("ROUTE_CHAPTER_COUNT", "本卷章数必须为 " + expected));
        }
        Map<Integer, PlanContracts.ChapterSlotContract> slots = new LinkedHashMap<>();
        for (PlanContracts.ChapterSlotContract slot : plan.chapters()) {
            if (slot.chapterNo() < ctx.volume().fromChapter()
                    || slot.chapterNo() > ctx.volume().toChapter()) {
                issues.add(issue("ROUTE_CHAPTER_RANGE", "章号越界: " + slot.chapterNo()));
            }
            if (slots.put(slot.chapterNo(), slot) != null) {
                issues.add(issue("ROUTE_CHAPTER_DUP", "章功能槽重复: " + slot.chapterNo()));
            }
        }
        Map<Integer, Set<PlanContracts.BeatRole>> rolesByChapter = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (PlanContracts.BeatAssignment assignment : plan.assignments()) {
            if (assignment.seq() == null || !seen.add(assignment.seq())) {
                issues.add(issue("ROUTE_SEQ_DUP", "拍 seq 为空或重复: " + assignment.seq()));
                continue;
            }
            if (ctx.inventoryBySeq().containsKey(assignment.seq()) == false) {
                issues.add(issue("ROUTE_SEQ_UNKNOWN", "拍 seq 不在剩余清单: " + assignment.seq()));
            }
            if (assignment.chapterNo() == null
                    || !slots.containsKey(assignment.chapterNo())) {
                issues.add(issue("ROUTE_BEAT_CHAPTER", "拍 " + assignment.seq() + " 章号非法"));
            } else {
                rolesByChapter.computeIfAbsent(assignment.chapterNo(), k -> new HashSet<>())
                        .add(assignment.beatRole());
            }
            validateRefs(assignment.focusCharacters(), ctx.characterKeys(),
                    "角色", assignment.seq(), issues);
            validateRefs(assignment.threadRefs(), ctx.threadKeys(),
                    "线程", assignment.seq(), issues);
        }
        Set<Integer> skipped = new HashSet<>();
        for (PlanContracts.SkipAssignment skip : plan.skipped()) {
            if (skip.seq() == null || !seen.add(skip.seq()) || !skipped.add(skip.seq())) {
                issues.add(issue("ROUTE_SKIP_DUP", "skip seq 重复或已分配: " + skip.seq()));
            }
            if (ctx.inventoryBySeq().containsKey(skip.seq()) == false) {
                issues.add(issue("ROUTE_SKIP_UNKNOWN", "skip seq 不在剩余清单: " + skip.seq()));
            }
            if (skip.reason() == null || skip.reason().isBlank()) {
                issues.add(issue("ROUTE_SKIP_REASON", "skip seq " + skip.seq() + " 缺原因"));
            }
        }
        Set<Integer> expectedSeqs = new LinkedHashSet<>(ctx.inventoryBySeq().keySet());
        expectedSeqs.removeAll(ctx.forcedSkips().keySet());
        Set<Integer> seenRoutable = new LinkedHashSet<>(seen);
        seenRoutable.removeAll(ctx.forcedSkips().keySet());
        if (!seenRoutable.equals(expectedSeqs)) {
            Set<Integer> missing = new LinkedHashSet<>(expectedSeqs);
            missing.removeAll(seenRoutable);
            Set<Integer> extra = new LinkedHashSet<>(seenRoutable);
            extra.removeAll(expectedSeqs);
            issues.add(issue("ROUTE_COVERAGE",
                    "拍未恰好覆盖剩余清单，missing=" + missing + " extra=" + extra));
        }
        for (PlanContracts.SyntheticAssignment synthetic : plan.synthetic()) {
            if (!slots.containsKey(synthetic.chapterNo())) {
                issues.add(issue("ROUTE_SYNTHETIC_CHAPTER", "补拍章号非法: " + synthetic.chapterNo()));
            } else {
                rolesByChapter.computeIfAbsent(synthetic.chapterNo(), k -> new HashSet<>())
                        .add(synthetic.beatRole());
            }
            validateRefs(synthetic.focusCharacters(), ctx.characterKeys(),
                    "角色", 0, issues);
            validateRefs(synthetic.threadRefs(), ctx.threadKeys(),
                    "线程", 0, issues);
        }
        Map<Integer, Integer> counts = new HashMap<>();
        for (PlanContracts.BeatAssignment assignment : plan.assignments()) {
            counts.merge(assignment.chapterNo(), 1, Integer::sum);
        }
        for (PlanContracts.SyntheticAssignment synthetic : plan.synthetic()) {
            counts.merge(synthetic.chapterNo(), 1, Integer::sum);
        }
        for (int no = ctx.volume().fromChapter(); no <= ctx.volume().toChapter(); no++) {
            int count = counts.getOrDefault(no, 0);
            if (count < 1 || count > 4) {
                issues.add(issue("ROUTE_CHAPTER_BEATS", "第" + no + "章拍数=" + count + "，必须1~4"));
            }
            PlanContracts.ChapterSlotContract slot = slots.get(no);
            if (slot != null) {
                Set<PlanContracts.BeatRole> actual = rolesByChapter.getOrDefault(no, Set.of());
                if (!actual.containsAll(slot.requiredBeatRoles())) {
                    Set<PlanContracts.BeatRole> missing = new LinkedHashSet<>(slot.requiredBeatRoles());
                    missing.removeAll(actual);
                    issues.add(issue("ROUTE_REQUIRED_ROLE", "第" + no + "章缺功能拍: " + missing));
                }
            }
        }
        return issues;
    }

    /** assignments 与 syntheticSlots 共用的扁平 route_contract 字段，避免两类补拍漏读契约。 */
    record RouteContractFields(
            List<String> focusCharacters,
            List<String> threadRefs,
            List<Integer> dependsOn,
            String payoffTarget,
            PlanContracts.AppearanceMode appearanceMode,
            List<String> requiresKnowledge,
            List<String> writesKnowledge,
            Map<String, Object> requiresState,
            Map<String, Object> changesState) {
    }

    static RouteContractFields routeContract(JsonNode node) {
        return new RouteContractFields(
                strings(node.path("focusCharacters")),
                strings(node.path("threadRefs")),
                ints(node.path("dependsOn")),
                node.path("payoffTarget").asText(""),
                enumValue(PlanContracts.AppearanceMode.class,
                        node.path("appearanceMode").asText("LIVE"), "appearanceMode"),
                strings(node.path("requiresKnowledge")),
                strings(node.path("writesKnowledge")),
                map(node.path("requiresState")),
                map(node.path("changesState")));
    }

    static PlanContracts.RoutePlan parseRoute(JsonNode root, RouteContext ctx) {
        int volumeNo = root.path("volumeNo").asInt(0);
        if (volumeNo != ctx.volume().volumeNo()) {
            throw new PlanJson.Bad("route.volumeNo 必须为 " + ctx.volume().volumeNo());
        }
        List<PlanContracts.ChapterSlotContract> chapters = new ArrayList<>();
        for (JsonNode node : root.path("chapters")) {
            chapters.add(new PlanContracts.ChapterSlotContract(
                    node.path("chapterNo").asInt(0),
                    text(node, "chapterFunction"),
                    text(node, "localCaseGoal"),
                    text(node, "mainMysteryAdvance"),
                    text(node, "characterGoal"),
                    text(node, "relationshipGoal"),
                    roles(node.path("requiredBeatRoles")),
                    strings(node.path("forbiddenAppearances")),
                    map(node.path("requiredStateBefore")),
                    map(node.path("requiredStateAfter"))));
        }
        if (ctx.volume().toChapter() - ctx.volume().fromChapter() + 1 >= 6) {
            chapters = mergeRequiredSlots(chapters, ctx.volume());
        }
        List<PlanContracts.BeatAssignment> assignments = new ArrayList<>();
        for (JsonNode node : root.path("assignments")) {
            RouteContractFields fields = routeContract(node);
            assignments.add(new PlanContracts.BeatAssignment(
                    node.path("seq").asInt(0),
                    node.path("chapterNo").asInt(0),
                    enumValue(PlanContracts.StoryLayer.class, node.path("storyLayer").asText(""), "storyLayer"),
                    enumValue(PlanContracts.BeatRole.class, node.path("beatRole").asText(""), "beatRole"),
                    fields.focusCharacters(),
                    fields.threadRefs(),
                    fields.dependsOn(),
                    fields.payoffTarget(),
                    fields.appearanceMode(),
                    fields.requiresKnowledge(),
                    fields.writesKnowledge(),
                    fields.requiresState(),
                    fields.changesState()));
        }

        // 强制跳过是确定性红线：模型即使把它写回 assignments 也丢弃，
        // 后续统一由 forcedSkips 回填 skipped，避免 ROUTE_SKIP_DUP。
        assignments.removeIf(a -> ctx.forcedSkips().containsKey(a.seq()));
        List<PlanContracts.SyntheticAssignment> synthetic = new ArrayList<>();
        for (JsonNode node : root.path("syntheticSlots")) {
            int chapterNo = node.path("chapterNo").asInt(0);
            PlanContracts.BeatRole role = enumValue(
                    PlanContracts.BeatRole.class, node.path("beatRole").asText(""), "beatRole");
            String storyLayerRaw = node.path("storyLayer").asText("").trim();
            PlanContracts.StoryLayer storyLayer = storyLayerRaw.isEmpty()
                    ? defaultStoryLayer(role)
                    : enumValue(PlanContracts.StoryLayer.class, storyLayerRaw, "storyLayer");
            String beatType = node.path("beatType").asText("").trim();
            if (!Set.of("铺垫", "情绪", "事件", "反转", "抉择", "收束").contains(beatType)) {
                beatType = defaultBeatType(role);
            }
            String goal = node.path("goal").asText("").trim();
            if (goal.isEmpty()) {
                goal = "补齐第" + chapterNo + "章 " + role.name() + " 结构功能，服务本卷合同与闭环";
            }
            RouteContractFields fields = routeContract(node);
            synthetic.add(new PlanContracts.SyntheticAssignment(
                    chapterNo,
                    storyLayer,
                    role,
                    beatType,
                    goal,
                    fields.focusCharacters(),
                    fields.threadRefs(),
                    fields.appearanceMode(),
                    fields.dependsOn(),
                    fields.payoffTarget(),
                    fields.requiresKnowledge(),
                    fields.writesKnowledge(),
                    fields.requiresState(),
                    fields.changesState()));
        }

        List<PlanContracts.SkipAssignment> skipped = new ArrayList<>();
        for (JsonNode node : root.path("skipped")) {
            skipped.add(new PlanContracts.SkipAssignment(
                    node.path("seq").asInt(0), node.path("reason").asText("")));
        }
        Set<Integer> skippedSeqs = skipped.stream()
                .map(PlanContracts.SkipAssignment::seq)
                .collect(java.util.stream.Collectors.toSet());
        ctx.forcedSkips().forEach((seq, reason) -> {
            if (skippedSeqs.add(seq)) {
                skipped.add(new PlanContracts.SkipAssignment(seq, reason));
            }
        });
        synthetic.addAll(synthesizeMissingRoles(chapters, assignments, synthetic,
                ctx.volume().requiredThreads(), ctx.threadKeys()));
        ensureRequiredThreadPayoffs(assignments, synthetic, ctx);
        PlanContracts.RoutePlan plan = new PlanContracts.RoutePlan(
                volumeNo, chapters, assignments, synthetic, skipped);
        List<PlanContracts.PlanIssue> issues = validateRoute(plan, ctx);
        if (!issues.isEmpty()) {
            throw new PlanJson.Bad(issues.get(0).message());
        }
        return plan;
    }

    /** 确定性补齐 requiredThreads 的兑现拍，避免把纯结构缺口留给 P6。 */
    static void ensureRequiredThreadPayoffs(
            List<PlanContracts.BeatAssignment> assignments,
            List<PlanContracts.SyntheticAssignment> synthetic,
            RouteContext ctx) {
        Set<String> required = new LinkedHashSet<>(ctx.volume().requiredThreads());
        if (required.isEmpty()) {
            return;
        }
        Set<String> paid = new LinkedHashSet<>();
        for (PlanContracts.BeatAssignment assignment : assignments) {
            if (isPayoffRole(assignment.beatRole().name())) {
                paid.addAll(assignment.threadRefs());
            }
        }
        for (PlanContracts.SyntheticAssignment item : synthetic) {
            if (isPayoffRole(item.beatRole().name())) {
                paid.addAll(item.threadRefs());
            }
        }
        List<String> missing = required.stream().filter(key -> !paid.contains(key)).toList();
        if (missing.isEmpty()) {
            return;
        }
        int finalChapter = ctx.volume().toChapter();
        for (String threadKey : missing) {
            boolean repaired = false;
            for (int i = 0; i < assignments.size(); i++) {
                PlanContracts.BeatAssignment assignment = assignments.get(i);
                if (assignment.chapterNo() != null && assignment.chapterNo() == finalChapter
                        && isPayoffRole(assignment.beatRole().name())) {
                    assignments.set(i, withThreadRef(assignment, threadKey));
                    repaired = true;
                    break;
                }
            }
            if (!repaired) {
                for (int i = 0; i < synthetic.size(); i++) {
                    PlanContracts.SyntheticAssignment item = synthetic.get(i);
                    if (item.chapterNo() == finalChapter && isPayoffRole(item.beatRole().name())) {
                        synthetic.set(i, withThreadRef(item, threadKey));
                        repaired = true;
                        break;
                    }
                }
            }
            if (!repaired) {
                long chapterBeats = assignments.stream()
                        .filter(a -> a.chapterNo() != null && a.chapterNo() == finalChapter).count()
                        + synthetic.stream().filter(s -> s.chapterNo() == finalChapter).count();
                if (chapterBeats < 4) {
                    synthetic.add(finalClosureForThread(ctx, finalChapter, threadKey));
                }
            }
        }
    }

    private static PlanContracts.BeatAssignment withThreadRef(
            PlanContracts.BeatAssignment assignment, String threadKey) {
        List<String> refs = new ArrayList<>(assignment.threadRefs());
        if (!refs.contains(threadKey)) {
            refs.add(threadKey);
        }
        return new PlanContracts.BeatAssignment(
                assignment.seq(), assignment.chapterNo(), assignment.storyLayer(),
                assignment.beatRole(), assignment.focusCharacters(), refs,
                assignment.dependsOn(), assignment.payoffTarget(), assignment.appearanceMode(),
                assignment.requiresKnowledge(), assignment.writesKnowledge(),
                assignment.requiresState(), assignment.changesState());
    }

    private static PlanContracts.SyntheticAssignment withThreadRef(
            PlanContracts.SyntheticAssignment item, String threadKey) {
        List<String> refs = new ArrayList<>(item.threadRefs());
        if (!refs.contains(threadKey)) {
            refs.add(threadKey);
        }
        return new PlanContracts.SyntheticAssignment(
                item.chapterNo(), item.storyLayer(), item.beatRole(), item.beatType(),
                item.goal(), item.focusCharacters(), refs, item.appearanceMode(),
                item.dependsOn(), item.payoffTarget(), item.requiresKnowledge(),
                item.writesKnowledge(), item.requiresState(), item.changesState());
    }

    private static boolean isPayoffRole(String role) {
        return "CLUE_PAYOFF".equals(role)
                || "LOCAL_CLOSURE".equals(role)
                || "FINAL_REVEAL".equals(role)
                || "FINAL_CLOSURE".equals(role);
    }
    private static PlanContracts.SyntheticAssignment finalClosureForThread(
            RouteContext ctx, int chapterNo, String threadKey) {
        List<String> focus = ctx.characterKeys().isEmpty()
                ? List.of("C1") : List.of(ctx.characterKeys().iterator().next());
        PlanContracts.BeatRole role = "FINAL".equals(ctx.volume().globalRole())
                ? PlanContracts.BeatRole.FINAL_CLOSURE
                : PlanContracts.BeatRole.CLUE_PAYOFF;
        return new PlanContracts.SyntheticAssignment(
                chapterNo, PlanContracts.StoryLayer.LOCAL_CASE, role, "收束",
                "补齐线程兑现：" + threadKey, focus, List.of(threadKey),
                PlanContracts.AppearanceMode.LIVE, List.of(), threadKey,
                List.of(), List.of(), Map.of(), Map.of());
    }

    /**
     * 短卷结构底稿：模型可补章目标，但不能删除系统模板里的 HOOK、高光、选择、代价和闭合槽。
     * 每章仍保持 1~4 拍上限，模板角色优先，模型新增角色按序补位。
     */
    static List<PlanContracts.ChapterSlotContract> mergeRequiredSlots(
            List<PlanContracts.ChapterSlotContract> parsed,
            PlanContracts.VolumeContract volume) {
        Map<Integer, PlanContracts.ChapterSlotContract> templates = new LinkedHashMap<>();
        chapterSlots(volume).forEach(slot -> templates.put(slot.chapterNo(), slot));
        List<PlanContracts.ChapterSlotContract> out = new ArrayList<>();
        for (PlanContracts.ChapterSlotContract slot : parsed) {
            PlanContracts.ChapterSlotContract template = templates.get(slot.chapterNo());
            if (template == null) {
                out.add(slot);
                continue;
            }
            LinkedHashSet<PlanContracts.BeatRole> roles =
                    new LinkedHashSet<>(template.requiredBeatRoles());
            roles.addAll(slot.requiredBeatRoles());
            List<PlanContracts.BeatRole> capped = roles.stream().limit(4).toList();
            String characterGoal = containsCharacterKey(slot.characterGoal())
                    ? slot.characterGoal() : template.characterGoal();
            String relationshipGoal = containsCharacterKey(slot.relationshipGoal())
                    ? slot.relationshipGoal() : template.relationshipGoal();
            out.add(new PlanContracts.ChapterSlotContract(
                    slot.chapterNo(), slot.chapterFunction(), slot.localCaseGoal(),
                    slot.mainMysteryAdvance(), characterGoal, relationshipGoal,
                    capped, slot.forbiddenAppearances(), slot.requiredStateBefore(),
                    slot.requiredStateAfter()));
        }
        return out;
    }

    private static boolean containsCharacterKey(String text) {
        return text != null && CHARACTER_KEY.matcher(text).find();
    }

    /**
     * 知识传播修复：后拍引用已知事实时，把缺知的焦点角色补回最早写出该事实的前置拍。
     * 找不到前置获知拍时不伪造知识，继续交给规划硬校验拦截。
     */
    static PlanContracts.RoutePlan repairKnowledgeCausality(PlanContracts.RoutePlan plan) {
        List<PlanContracts.BeatAssignment> assignments = new ArrayList<>(plan.assignments());
        assignments.sort(java.util.Comparator
                .comparingInt(PlanContracts.BeatAssignment::chapterNo)
                .thenComparingInt(PlanContracts.BeatAssignment::seq));
        Map<String, Set<String>> knownBy = new HashMap<>();
        Map<String, Integer> firstWriter = new HashMap<>();
        for (int i = 0; i < assignments.size(); i++) {
            PlanContracts.BeatAssignment current = assignments.get(i);
            for (String required : current.requiresKnowledge()) {
                Set<String> known = knownBy.getOrDefault(required, Set.of());
                List<String> missing = current.focusCharacters().stream()
                        .filter(character -> !known.contains(character))
                        .toList();
                Integer writerIndex = firstWriter.get(required);
                if (!missing.isEmpty() && writerIndex != null) {
                    PlanContracts.BeatAssignment writer = assignments.get(writerIndex);
                    List<String> focus = appendDistinct(writer.focusCharacters(), missing);
                    assignments.set(writerIndex, new PlanContracts.BeatAssignment(
                            writer.seq(), writer.chapterNo(), writer.storyLayer(),
                            writer.beatRole(), focus, writer.threadRefs(),
                            writer.dependsOn(), writer.payoffTarget(),
                            writer.appearanceMode(), writer.requiresKnowledge(),
                            writer.writesKnowledge(), writer.requiresState(),
                            writer.changesState()));
                    knownBy.computeIfAbsent(required, key -> new LinkedHashSet<>()).addAll(missing);
                    log.info("知识因果修复：fact={} 补角色={} 到 seq={}（原 focus={}）",
                            required, missing, writer.seq(), writer.focusCharacters());
                } else if (!missing.isEmpty()) {
                    log.warn("知识因果缺口：fact={} 在早于 seq={} 的拍中无获知来源，缺口={}",
                            required, current.seq(), missing);
                }
            }
            for (String fact : current.writesKnowledge()) {
                knownBy.computeIfAbsent(fact, key -> new LinkedHashSet<>())
                        .addAll(current.focusCharacters());
                firstWriter.putIfAbsent(fact, i);
            }
        }
        return new PlanContracts.RoutePlan(plan.volumeNo(), plan.chapters(),
                assignments, plan.synthetic(), plan.skipped());
    }

    private static List<PlanContracts.SyntheticAssignment> synthesizeMissingRoles(
            List<PlanContracts.ChapterSlotContract> chapters,
            List<PlanContracts.BeatAssignment> assignments,
            List<PlanContracts.SyntheticAssignment> existing,
            List<String> requiredThreads,
            Set<String> allThreads) {
        List<String> payoffThreads = requiredThreads == null || requiredThreads.isEmpty()
                ? new ArrayList<>(allThreads) : requiredThreads;
        Map<Integer, Set<PlanContracts.BeatRole>> covered = new HashMap<>();
        assignments.forEach(a -> covered.computeIfAbsent(a.chapterNo(), k -> new HashSet<>())
                .add(a.beatRole()));
        existing.forEach(s -> covered.computeIfAbsent(s.chapterNo(), k -> new HashSet<>())
                .add(s.beatRole()));
        List<PlanContracts.SyntheticAssignment> out = new ArrayList<>();
        for (PlanContracts.ChapterSlotContract chapter : chapters) {
            Set<PlanContracts.BeatRole> actual = covered.computeIfAbsent(
                    chapter.chapterNo(), k -> new HashSet<>());
            List<String> focusCharacters = extractCharacterKeys(
                    chapter.characterGoal(), chapter.relationshipGoal(), chapter.localCaseGoal());
            for (PlanContracts.BeatRole role : chapter.requiredBeatRoles()) {
                if (actual.contains(role)) {
                    continue;
                }
                out.add(new PlanContracts.SyntheticAssignment(
                        chapter.chapterNo(), defaultStoryLayer(role), role,
                        defaultBeatType(role),
                        "补齐第" + chapter.chapterNo() + "章 " + role.name()
                                + " 结构功能，服务本卷合同与闭环",
                        focusCharacters, isThreadPayoffRole(role) ? payoffThreads : List.of(),
                        PlanContracts.AppearanceMode.LIVE));
                actual.add(role);
            }
        }
        return out;
    }

    private static List<String> extractCharacterKeys(String... texts) {
        Set<String> keys = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = CHARACTER_KEY.matcher(text);
            while (matcher.find()) {
                keys.add(matcher.group());
            }
        }
        return new ArrayList<>(keys);
    }

    private static List<String> appendDistinct(List<String> values, List<String> additions) {
        LinkedHashSet<String> out = new LinkedHashSet<>(values);
        out.addAll(additions);
        return new ArrayList<>(out);
    }

    static String defaultBeatType(PlanContracts.BeatRole role) {
        return switch (role) {
            case FALSE_ANSWER -> "反转";
            case LOCAL_CLOSURE, FINAL_CLOSURE, NEXT_HOOK -> "收束";
            case CHARACTER_CHOICE, FUNCTION_FAILURE, COST, HIGHLIGHT -> "抉择";
            case CLUE_PLANT, CLUE_ECHO, CLUE_PAYOFF, CASE_SETUP -> "铺垫";
            default -> "事件";
        };
    }

    private static boolean isThreadPayoffRole(PlanContracts.BeatRole role) {
        return role == PlanContracts.BeatRole.CLUE_PAYOFF
                || role == PlanContracts.BeatRole.LOCAL_CLOSURE
                || role == PlanContracts.BeatRole.FINAL_REVEAL
                || role == PlanContracts.BeatRole.FINAL_CLOSURE;
    }

    static PlanContracts.StoryLayer defaultStoryLayer(PlanContracts.BeatRole role) {
        return switch (role) {
            case RELATION_CONFLICT -> PlanContracts.StoryLayer.RELATIONSHIP;
            case FUNCTION_USE, FUNCTION_FAILURE, CHARACTER_CHOICE, HIGHLIGHT, COST ->
                    PlanContracts.StoryLayer.CHARACTER_ARC;
            case CLUE_PLANT, CLUE_ECHO, CLUE_PAYOFF, MAIN_ADVANCE,
                    FINAL_REVEAL, NEXT_HOOK -> PlanContracts.StoryLayer.MAIN_MYSTERY;
            default -> PlanContracts.StoryLayer.LOCAL_CASE;
        };
    }

    private void persistRoute(RunDO run, PlanContracts.PlanV2State state,
                              PlanContracts.VolumeContract volume,
                              PlanContracts.RoutePlan plan,
                              RouteContext ctx) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            chapterMapper.clearPlanByVolume(run.getNovelId(), volume.volumeNo());
            beatInstanceMapper.clearByVolume(run.getId(), volume.volumeNo());
            Map<Integer, ChapterDO> chapters = ensureChapters(run, state, volume);
            for (PlanContracts.ChapterSlotContract slot : plan.chapters()) {
                ChapterDO chapter = chapters.get(slot.chapterNo());
                chapterMapper.updatePlanContract(chapter.getId(), toJson(slot));
            }
            for (PlanContracts.BeatAssignment assignment : plan.assignments()) {
                DerivePlanService.InvBeat source = ctx.inventoryBySeq().get(assignment.seq());
                BeatInstanceDO row = beatRow(run, volume, assignment.seq(), assignment.chapterNo(),
                        assignment.storyLayer(), assignment.beatRole(),
                        toJson(assignment), "PLANNED",
                        source.labelText(), source);
                beatInstanceMapper.insertFull(row);
            }
            for (PlanContracts.SkipAssignment skip : plan.skipped()) {
                DerivePlanService.InvBeat source = ctx.inventoryBySeq().get(skip.seq());
                BeatInstanceDO row = beatRow(run, volume, skip.seq(), null,
                        null, null, toJson(Map.of("skipReason", skip.reason())),
                        "SKIPPED", source.labelText(), source);
                beatInstanceMapper.insertFull(row);
            }
            int seq = Math.max(ctx.syntheticSeqBase(), nextSeq(run.getId()) - 1);
            for (PlanContracts.SyntheticAssignment synthetic : plan.synthetic()) {
                BeatInstanceDO row = beatRow(run, volume, ++seq, synthetic.chapterNo(),
                        synthetic.storyLayer(), synthetic.beatRole(), toJson(synthetic),
                        "PLANNED", synthetic.goal(), null);
                row.setSource("SYNTHETIC");
                row.setBeatType(synthetic.beatType());
                row.setPacing(new BigDecimal("0.50"));
                beatInstanceMapper.insertFull(row);
            }
            updateVolumeState(run, volume.volumeNo(), state);
        });
    }

    private Map<Integer, ChapterDO> ensureChapters(
            RunDO run, PlanContracts.PlanV2State state, PlanContracts.VolumeContract volume) {
        List<ChapterDO> rows = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, run.getNovelId())
                .between(ChapterDO::getChapterNo, volume.fromChapter(), volume.toChapter())
                .orderByAsc(ChapterDO::getChapterNo));
        Map<Integer, ChapterDO> byNo = new HashMap<>();
        rows.forEach(c -> byNo.put(c.getChapterNo(), c));
        for (int no = volume.fromChapter(); no <= volume.toChapter(); no++) {
            if (!byNo.containsKey(no)) {
                ChapterDO chapter = new ChapterDO();
                chapter.setNovelId(run.getNovelId());
                chapter.setChapterNo(no);
                chapter.setVolumeNo(volume.volumeNo());
                chapter.setTitle("第" + no + "章");
                chapter.setBudget(state.scope().chapterAvg());
                chapter.setStatus("PLANNED");
                chapterMapper.insert(chapter);
                byNo.put(no, chapter);
            }
        }
        return byNo;
    }

    private BeatInstanceDO beatRow(RunDO run, PlanContracts.VolumeContract volume,
                                   int seq, Integer chapterNo, PlanContracts.StoryLayer storyLayer,
                                   PlanContracts.BeatRole beatRole, String routeContract,
                                   String status, String filledText,
                                   DerivePlanService.InvBeat source) {
        BeatInstanceDO row = new BeatInstanceDO();
        row.setRunId(run.getId());
        row.setSeq(seq);
        row.setSource(source == null ? "SYNTHETIC" : source.source());
        row.setAssetId(source == null ? null : source.assetId());
        row.setArcId(source == null ? null : source.arcId());
        row.setArcSegNo(source == null ? null : source.arcSegNo());
        row.setBeatType(source == null ? "事件" : source.beatType());
        row.setPacing(source == null ? new BigDecimal("0.50") : source.pacing());
        row.setChapterNo(chapterNo);
        row.setVolumeNo(volume.volumeNo());
        row.setFilledText(filledText);
        row.setStatus(status);
        row.setStoryLayer(storyLayer == null ? null : storyLayer.name());
        row.setBeatRole(beatRole == null ? null : beatRole.name());
        row.setRouteContract(routeContract);
        return row;
    }

    private void updateVolumeState(RunDO run, int volumeNo, PlanContracts.PlanV2State old) {
        RunDO current = runMapper.selectById(run.getId());
        JsonNode rootNode = readTreeSafe(current.getStepStatus());
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) rootNode.deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode steps = root.path("steps").isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) root.path("steps")
                : root.putObject("steps");
        PlanContracts.PlanV2State plan = PlanV2StateCodec.read(
                steps.path("PLAN_V2").toString());
        Map<Integer, PlanContracts.PlanUnit> volumeUnits = new LinkedHashMap<>(
                plan.volumeUnits());
        volumeUnits.put(volumeNo, PlanContracts.PlanUnit.VALIDATE);
        PlanContracts.PlanUnit currentUnit = volumeUnits.values().stream()
                .allMatch(unit -> unit == PlanContracts.PlanUnit.VALIDATE)
                ? PlanContracts.PlanUnit.VALIDATE
                : PlanContracts.PlanUnit.SLOT_ROUTE;
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                currentUnit, plan.completedUnits(), plan.scope(), volumeUnits);
        steps.set("PLAN_V2", MAPPER.valueToTree(next));
        runMapper.updateStepStatus(run.getId(), root.toString());
    }

    private PlanContracts.VolumeContract loadVolume(long runId, int volumeNo) {
        VolumePlanDO row = volumePlanMapper.selectOne(new LambdaQueryWrapper<VolumePlanDO>()
                .eq(VolumePlanDO::getRunId, runId)
                .eq(VolumePlanDO::getVolumeNo, volumeNo)
                .last("LIMIT 1"));
        if (row == null || row.getContract() == null || row.getContract().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "卷合同不存在: " + volumeNo);
        }
        try {
            return MAPPER.readValue(row.getContract(), PlanContracts.VolumeContract.class);
        } catch (Exception e) {
            throw new IllegalStateException("卷合同 JSON 损坏: " + row.getId(), e);
        }
    }

    private RouteContext buildContext(RunDO run, PlanContracts.PlanV2State state,
                                      PlanContracts.VolumeContract volume) {
        Map<Integer, DerivePlanService.InvBeat> full = fullInventory(run);
        Set<String> usedKeys = usedBeatKeys(run.getId(), volume.volumeNo());
        Map<Integer, DerivePlanService.InvBeat> remain = new LinkedHashMap<>();
        full.forEach((seq, beat) -> {
            if (!usedKeys.contains(identity(beat))) {
                remain.put(seq, beat);
            }
        });
        Map<Integer, String> forcedSkips = new LinkedHashMap<>();
        remain.forEach((seq, beat) -> {
            String reason = conflictSkipReason(run.getParamIntent(), beat.labelText());
            if (reason != null) {
                forcedSkips.put(seq, reason);
            }
        });
        Set<String> characters = characterPlanMapper.selectList(
                        new LambdaQueryWrapper<CharacterPlanDO>()
                                .eq(CharacterPlanDO::getRunId, run.getId()))
                .stream().map(CharacterPlanDO::getCharacterKey).collect(java.util.stream.Collectors.toSet());
        Set<String> threads = storyThreadMapper.selectList(
                        new LambdaQueryWrapper<StoryThreadDO>()
                                .eq(StoryThreadDO::getRunId, run.getId()))
                .stream().map(StoryThreadDO::getThreadKey).collect(java.util.stream.Collectors.toSet());
        return new RouteContext(volume, new ArrayList<>(remain.values()), characters, threads,
                Math.max(full.size(), nextSeq(run.getId()) - 1), forcedSkips);
    }

    private Set<String> usedBeatKeys(long runId, int currentVolume) {
        Set<String> used = new HashSet<>();
        for (BeatInstanceDO row : beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>().eq(BeatInstanceDO::getRunId, runId))) {
            if (row.getVolumeNo() != null && row.getVolumeNo() != currentVolume) {
                used.add(identity(row.getSource(), row.getArcId(), row.getArcSegNo(), row.getAssetId()));
            }
        }
        return used;
    }

    private Map<Integer, DerivePlanService.InvBeat> fullInventory(RunDO run) {
        List<Long> arcIds = DerivePlanService.jsonLongArray(run.getPickedOutlineIds());
        List<DerivePlanService.InvBeat> out = new ArrayList<>();
        int seq = 1;
        for (Long arcId : arcIds) {
            List<AssetBeatDO> natives = beatMapper.selectList(
                    new LambdaQueryWrapper<AssetBeatDO>()
                            .eq(AssetBeatDO::getParentArcId, arcId)
                            .orderByAsc(AssetBeatDO::getId));
            int seg = 0;
            for (AssetBeatDO beat : natives) {
                LabelIndexDO label = label(beat.getId());
                if (label == null) {
                    continue;
                }
                out.add(new DerivePlanService.InvBeat(seq++, "ARC_SEG", null, arcId, seg++,
                        label.getBeatType(), label.getPacing(), beat.getLabelText(), arcSlot(label)));
            }
        }
        for (Long beatId : DerivePlanService.jsonLongArray(run.getPickedBeatIds())) {
            AssetBeatDO beat = beatMapper.selectById(beatId);
            if (beat == null || (beat.getParentArcId() != null
                    && arcIds.contains(beat.getParentArcId()))) {
                continue;
            }
            LabelIndexDO label = label(beatId);
            if (label == null) {
                continue;
            }
            out.add(new DerivePlanService.InvBeat(seq++, "ASSET", beatId, null, null,
                    label.getBeatType(), label.getPacing(), beat.getLabelText(), arcSlot(label)));
        }
        Map<Integer, DerivePlanService.InvBeat> map = new LinkedHashMap<>();
        out.forEach(beat -> map.put(beat.tempSeq(), beat));
        return map;
    }

    private LabelIndexDO label(long beatId) {
        return labelIndexMapper.selectOne(new LambdaQueryWrapper<LabelIndexDO>()
                .eq(LabelIndexDO::getEntryType, "BEAT")
                .eq(LabelIndexDO::getEntryTypeId, beatId)
                .last("LIMIT 1"));
    }

    private static String arcSlot(LabelIndexDO label) {
        try {
            JsonNode fit = MAPPER.readTree(label.getArcFit());
            String best = "中段";
            double score = Double.NEGATIVE_INFINITY;
            for (String slot : List.of("开头", "中段", "转折", "收尾")) {
                double value = fit.path(slot).asDouble(0);
                if (value > score) {
                    score = value;
                    best = slot;
                }
            }
            return best;
        } catch (Exception e) {
            return "中段";
        }
    }

    private String buildRouteUser(RunDO run, PlanContracts.VolumeContract volume,
                                  RouteContext ctx, String feedback) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("volumeContract", MAPPER.valueToTree(volume));
        if (feedback != null && !feedback.isBlank()) {
            root.put("返工问题", feedback);
            root.put("返工要求", "上一版已被规划硬校验拒绝；逐条修复，禁止原样重复");
        }
        root.put("结局形状", run.getParamOutcome());
        root.put("本卷是否最终卷", "FINAL".equals(volume.globalRole()));
        root.set("characters", PlanJsonViews.characters(characterPlanMapper.selectList(
                new LambdaQueryWrapper<CharacterPlanDO>()
                        .eq(CharacterPlanDO::getRunId, run.getId()))));
        root.set("threads", PlanJsonViews.threads(storyThreadMapper.selectList(
                new LambdaQueryWrapper<StoryThreadDO>()
                        .eq(StoryThreadDO::getRunId, run.getId()))));
        ArrayNode forced = root.putArray("forcedSkips");
        ctx.forcedSkips().forEach((seq, reason) -> {
            ObjectNode item = forced.addObject();
            item.put("seq", seq);
            item.put("reason", reason);
        });
        ArrayNode beats = root.putArray("remainingBeats");
        for (DerivePlanService.InvBeat beat : ctx.inventory()) {
            if (ctx.forcedSkips().containsKey(beat.tempSeq())) {
                continue;
            }
            ObjectNode node = beats.addObject();
            node.put("seq", beat.tempSeq());
            node.put("source", beat.source());
            node.put("beatType", beat.beatType());
            node.put("pacing", beat.pacing().toPlainString());
            node.put("arcSlot", beat.arcSlot());
            node.put("label", beat.labelText());
        }
        root.set("chapterSlots", MAPPER.valueToTree(chapterSlots(volume)));
        return root.toString();
    }

    /** 用户红线与原始拍文案冲突时，路由前确定性跳过并交给 SYNTHETIC 补结构。 */
    static String conflictSkipReason(String intent, String label) {
        if (intent == null || intent.isBlank() || label == null || label.isBlank()) {
            return null;
        }
        boolean noDeath = intent.contains("无死亡") || intent.contains("不写死亡")
                || intent.contains("没有死亡");
        boolean noSupernatural = intent.contains("无超自然") || intent.contains("不写超自然")
                || intent.contains("没有超自然")
                || (intent.contains("没有死亡") && intent.contains("超自然"));
        if (noDeath && containsAny(label, List.of(
                "尸体", "遗骸", "殡葬", "死亡", "死者", "命案", "凶杀"))) {
            return "原始拍含死亡表述，与创作框架「无死亡」红线冲突；已跳过并由结构补拍替代";
        }
        if (noSupernatural && containsAny(label, List.of(
                "诡异", "人形", "化形", "超自然", "怪谈", "灵异", "鬼", "异象"))) {
            return "原始拍含超自然表述，与创作框架「无超自然」红线冲突；已跳过并由结构补拍替代";
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    static List<PlanContracts.ChapterSlotContract> chapterSlots(
            PlanContracts.VolumeContract volume) {
        List<PlanContracts.ChapterSlotContract> out = new ArrayList<>();
        int count = volume.toChapter() - volume.fromChapter() + 1;
        int highlightAt = Math.max(1, count - 1);
        for (int i = 1; i <= count; i++) {
            int no = volume.fromChapter() + i - 1;
            List<PlanContracts.BeatRole> roles;
            if (i == 1) {
                roles = List.of(PlanContracts.BeatRole.HOOK);
            } else if (i == 2) {
                roles = List.of(PlanContracts.BeatRole.CASE_SETUP);
            } else if (i == 3) {
                roles = List.of(PlanContracts.BeatRole.CLUE_PLANT);
            } else if (i == 4) {
                roles = List.of(PlanContracts.BeatRole.FUNCTION_USE);
            } else if (i == highlightAt) {
                roles = List.of(
                        PlanContracts.BeatRole.HIGHLIGHT,
                        PlanContracts.BeatRole.CHARACTER_CHOICE,
                        PlanContracts.BeatRole.COST,
                        PlanContracts.BeatRole.LOCAL_CLOSURE);
            } else if (i == count) {
                roles = "FINAL".equals(volume.globalRole())
                        ? List.of(PlanContracts.BeatRole.CLUE_PAYOFF,
                                PlanContracts.BeatRole.FINAL_CLOSURE)
                        : List.of(PlanContracts.BeatRole.CLUE_PAYOFF,
                                PlanContracts.BeatRole.NEXT_HOOK);
            } else if (i == count - 2) {
                roles = List.of(
                        PlanContracts.BeatRole.RELATION_CONFLICT,
                        PlanContracts.BeatRole.CLUE_ECHO);
            } else if (i == count - 3) {
                roles = List.of(
                        PlanContracts.BeatRole.FALSE_ANSWER,
                        PlanContracts.BeatRole.MAIN_ADVANCE);
            } else {
                roles = List.of(PlanContracts.BeatRole.MAIN_ADVANCE);
            }
            out.add(new PlanContracts.ChapterSlotContract(
                    no, "CH" + i, volume.localQuestion(), volume.mainMysteryAdvance(),
                    volume.focusCharacters().isEmpty() ? "" : volume.focusCharacters().get(0),
                    String.join(",", volume.relationshipChanges()), roles, List.of(),
                    Map.of(), Map.of()));
        }
        return out;
    }

    private int nextSeq(long runId) {
        List<BeatInstanceDO> tail = beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>()
                        .eq(BeatInstanceDO::getRunId, runId)
                        .orderByDesc(BeatInstanceDO::getSeq)
                        .last("LIMIT 1"));
        return tail.isEmpty() ? 1 : tail.get(0).getSeq() + 1;
    }

    private PlanContracts.PlanV2State readPlanState(String stepStatus) {
        JsonNode plan = readTreeSafe(stepStatus).path("steps").path("PLAN_V2");
        return plan.isObject() ? PlanV2StateCodec.read(plan.toString()) : PlanV2StateCodec.initial();
    }

    private static void validateRefs(List<String> refs, Set<String> allowed,
                                     String type, int seq,
                                     List<PlanContracts.PlanIssue> issues) {
        for (String ref : refs) {
            if (!allowed.contains(ref)) {
                issues.add(issue("ROUTE_UNKNOWN_" + type, "拍 " + seq + " 引用未知" + type + ": " + ref));
            }
        }
    }

    private static PlanContracts.PlanIssue issue(String code, String message) {
        return new PlanContracts.PlanIssue("ERROR", code,
                PlanContracts.RepairScope.PLAN_VOLUME, null, null, List.of(), message);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new PlanJson.Bad(field + " 不能为空");
        }
        return value;
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> out.add(n.asText("")));
        }
        return out;
    }

    private static List<Integer> ints(JsonNode node) {
        List<Integer> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> out.add(n.asInt()));
        }
        return out;
    }

    private static List<PlanContracts.BeatRole> roles(JsonNode node) {
        List<PlanContracts.BeatRole> out = new ArrayList<>();
        for (String raw : strings(node)) {
            out.add(enumValue(PlanContracts.BeatRole.class, raw, "requiredBeatRoles"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, Map.class);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw.trim());
        } catch (Exception e) {
            throw new PlanJson.Bad(field + " 非法: " + raw);
        }
    }

    private static String identity(DerivePlanService.InvBeat beat) {
        return identity(beat.source(), beat.arcId(), beat.arcSegNo(), beat.assetId());
    }

    private static String identity(String source, Long arcId, Integer arcSegNo, Long assetId) {
        return source + ":" + arcId + ":" + arcSegNo + ":" + assetId;
    }

    private static JsonNode readTreeSafe(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 单卷路由上下文。 */
    record RouteContext(
            PlanContracts.VolumeContract volume,
            List<DerivePlanService.InvBeat> inventory,
            Set<String> characterKeys,
            Set<String> threadKeys,
            int syntheticSeqBase,
            Map<Integer, String> forcedSkips) {
        RouteContext(PlanContracts.VolumeContract volume,
                     List<DerivePlanService.InvBeat> inventory,
                     Set<String> characterKeys,
                     Set<String> threadKeys,
                     int syntheticSeqBase) {
            this(volume, inventory, characterKeys, threadKeys, syntheticSeqBase, Map.of());
        }

        Map<Integer, DerivePlanService.InvBeat> inventoryBySeq() {
            Map<Integer, DerivePlanService.InvBeat> map = new LinkedHashMap<>();
            inventory.forEach(beat -> map.put(beat.tempSeq(), beat));
            return map;
        }
    }
}
