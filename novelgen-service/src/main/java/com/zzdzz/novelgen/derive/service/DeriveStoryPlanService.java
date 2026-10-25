package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanJson;
import com.zzdzz.novelgen.derive.plan.PlanV2StateCodec;
import com.zzdzz.novelgen.derive.plan.StoryPlanningPrompts;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故事规划 V2 的全局合同生成：规模、脊柱、卷、角色。
 *
 * <p>对外只暴露幂等单元推进；章槽与拍路由由 {@link DeriveBeatRouteService} 负责。
 */
@Service
@RequiredArgsConstructor
public class DeriveStoryPlanService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VOLUME_MARKER = Pattern.compile(
            "第\\s*([0-9一二三四五六七八九十百两]+)\\s*卷");

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final AssetBeatMapper assetBeatMapper;
    private final CharacterPlanMapper characterPlanMapper;
    private final StoryThreadMapper storyThreadMapper;
    private final VolumePlanMapper volumePlanMapper;
    private final LlmJson llmJson;
    private final DeriveScaffoldService scaffoldService;

    /** 推进一个 PLAN_V2 单元。 */
    public PlanVO advance(long runId) {
        RunDO run = requirePlanningRun(runId);
        PlanContracts.PlanV2State state = readPlanState(run.getStepStatus());
        if (state == null) {
            if (readSteps(run.getStepStatus()).path("PLAN").isObject()) {
                return new PlanVO(runId, run.getNovelId(), 0, 0, 0, List.of(),
                        "LEGACY", 0, 0, 0, 0, false);
            }
            state = PlanV2StateCodec.initial();
        }
        return switch (state.currentUnit()) {
            case SCALE -> advanceScale(run, state);
            case SPINE -> advanceSpine(run, state);
            case VOLUMES -> advanceVolumes(run, state);
            case CHARACTERS -> advanceCharacters(run, state);
            default -> throw new BizException(ErrorCode.PARAM_ERROR,
                    "PLAN_V2 当前单元 " + state.currentUnit() + " 尚未实现");
        };
    }

    /** 用户确认模糊规模后继续到 SPINE。 */
    public PlanVO confirmScope(long runId, int volumeCount, boolean allowExpansion) {
        if (volumeCount < 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "volumeCount 必须≥1");
        }
        if (volumeCount > 1 && !allowExpansion) {
            throw new BizException(ErrorCode.PARAM_ERROR, "多卷确认必须同时允许扩卷");
        }
        RunDO run = requirePlanningRun(runId);
        PlanContracts.PlanV2State state = readPlanState(run.getStepStatus());
        if (state == null || state.scope() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "当前没有待确认的规模");
        }
        PlanContracts.ScopeContract old = state.scope();
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                volumeCount == 1 ? PlanContracts.ScopeDecision.SINGLE_VOLUME
                        : PlanContracts.ScopeDecision.MULTI_VOLUME,
                volumeCount, old.chapterCount(), old.chapterAvg(),
                append(old.evidence(), "用户确认卷数=" + volumeCount));
        if (run.getNovelId() == null) {
            long novelId = createNovel(run);
            run.setNovelId(novelId);
        }
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.SPINE, state.completedUnits(), scope, state.volumeUnits());
        writePlanState(run, next);
        return planVO(run, scope, "SPINE", false);
    }

    private PlanVO advanceScale(RunDO run, PlanContracts.PlanV2State oldState) {
        int beatCount = countCandidateBeats(run);
        if (beatCount == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "拍清单为空，run 抽样异常");
        }
        String user = buildScaleUser(run, beatCount);
        PlanContracts.ScopeContract scope = llmJson.ask(
                new LlmPort.ChatRequest(StoryPlanningPrompts.NODE_SCALE, run.getNovelId(), null,
                        run.getId(),
                        List.of(LlmPort.Message.system(StoryPlanningPrompts.SCALE),
                                LlmPort.Message.user(user.toString())),
                        0.3),
                root -> parseScale(root, run, beatCount), 3);
        ScaffoldContract scaffold = scaffoldService.buildScaffold(
                run, beatCount, scope.volumeCount(), scope.chapterCount());
        writeSubContract(run, "SCAFFOLD", scaffold);
        if (scope.decision() == PlanContracts.ScopeDecision.NEED_CONFIRMATION) {
            PlanContracts.PlanV2State pending = new PlanContracts.PlanV2State(
                    PlanContracts.PlanUnit.NEED_SCOPE, oldState.completedUnits(), scope,
                    oldState.volumeUnits());
            writePlanState(run, pending);
            return planVO(run, scope, "NEED_SCOPE", false);
        }

        long novelId = run.getNovelId() == null ? createNovel(run) : run.getNovelId();
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.SPINE,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.SCALE),
                scope, oldState.volumeUnits());
        writePlanState(run, next);
        return planVO(run, scope, "SCALE", false);
    }

    private PlanVO advanceSpine(RunDO run, PlanContracts.PlanV2State oldState) {
        if (oldState.scope() == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SPINE 缺少规模或成品书骨架");
        }
        ObjectNode user = MAPPER.createObjectNode();
        user.put("创作框架", nullToEmpty(run.getParamIntent()));
        putMainline(user, run);
        user.put("类别", nullToEmpty(run.getParamTags()));
        user.put("结局形状", run.getParamOutcome());
        user.put("volumeCount", oldState.scope().volumeCount());
        user.put("chapterCount", oldState.scope().chapterCount());
        putGateDirective(user, run);
        putTruthContract(user, run);
        JsonNode existingContract = readSteps(run.getStepStatus()).path("TRUTH_CONTRACT");
        PlanContracts.StorySpine spine;
        if (existingContract.isObject()
                && !existingContract.path("finalTruth").asText("").isBlank()) {
            spine = spineFromTruthContract(oldState.scope(), existingContract);
        } else {
            spine = llmJson.ask(
                    new LlmPort.ChatRequest(StoryPlanningPrompts.NODE_SPINE, run.getNovelId(), null,
                            run.getId(),
                            List.of(LlmPort.Message.system(StoryPlanningPrompts.SPINE),
                                    LlmPort.Message.user(user.toString())),
                            0.3),
                    root -> parseSpine(root, oldState.scope(), existingContract), 3);
        }
        persistSpine(run, spine);
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.VOLUMES,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.SPINE),
                oldState.scope(), oldState.volumeUnits());
        writePlanState(run, next);
        return planVO(run, oldState.scope(), "SPINE", false);
    }

    private PlanVO advanceVolumes(RunDO run, PlanContracts.PlanV2State oldState) {
        if (oldState.scope() == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "VOLUMES 缺少规模或成品书骨架");
        }
        ObjectNode user = MAPPER.createObjectNode();
        user.set("scope", MAPPER.valueToTree(oldState.scope()));
        user.set("storySpine", readSteps(run.getStepStatus()).path("STORY_SPINE"));
        user.put("创作框架", nullToEmpty(run.getParamIntent()));
        putMainline(user, run);
        putGateDirective(user, run);
        putTruthContract(user, run);
        JsonNode volumeContract = readSteps(run.getStepStatus()).path("TRUTH_CONTRACT");
        List<PlanContracts.VolumeContract> volumes;
        if (volumeContract.isObject()
                && !volumeContract.path("finalTruth").asText("").isBlank()) {
            volumes = List.of(volumeFromTruthContract(oldState.scope(), volumeContract));
        } else {
            volumes = llmJson.ask(
                    new LlmPort.ChatRequest(StoryPlanningPrompts.NODE_VOLUMES, run.getNovelId(), null,
                            run.getId(),
                            List.of(LlmPort.Message.system(StoryPlanningPrompts.VOLUMES),
                                    LlmPort.Message.user(user.toString())),
                            0.3),
                    root -> {
                        List<PlanContracts.VolumeContract> parsed =
                                parseVolumes(root, oldState.scope());
                        requireTruthAlignment(parsed, volumeContract);
                        return parsed;
                    }, 3);
        }
        persistVolumes(run, volumes);
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.CHARACTERS,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.VOLUMES),
                oldState.scope(), oldState.volumeUnits());
        writePlanState(run, next);
        return planVO(run, oldState.scope(), "VOLUMES", false);
    }

    private PlanVO advanceCharacters(RunDO run, PlanContracts.PlanV2State oldState) {
        if (oldState.scope() == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "CHARACTERS 缺少规模或成品书骨架");
        }
        ObjectNode user = MAPPER.createObjectNode();
        user.set("scope", MAPPER.valueToTree(oldState.scope()));
        user.set("storySpine", readSteps(run.getStepStatus()).path("STORY_SPINE"));
        user.set("volumes", MAPPER.valueToTree(loadVolumeContracts(run.getId())));
        user.put("创作框架", nullToEmpty(run.getParamIntent()));
        putGateDirective(user, run);
        putTruthContract(user, run);
        List<PlanContracts.CharacterContract> characters = llmJson.ask(
                new LlmPort.ChatRequest(StoryPlanningPrompts.NODE_CHARACTERS, run.getNovelId(), null,
                        run.getId(),
                        List.of(LlmPort.Message.system(StoryPlanningPrompts.CHARACTERS),
                                LlmPort.Message.user(user.toString())),
                        0.3),
                root -> parseCharacters(root, oldState.scope()), 3);
        persistCharacters(run, characters);
        Map<Integer, PlanContracts.PlanUnit> volumeUnits = new java.util.LinkedHashMap<>();
        for (int volumeNo = 1; volumeNo <= oldState.scope().volumeCount(); volumeNo++) {
            volumeUnits.put(volumeNo, PlanContracts.PlanUnit.SLOT_ROUTE);
        }
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.SLOT_ROUTE,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.CHARACTERS),
                oldState.scope(), volumeUnits);
        writePlanState(run, next);
        return planVO(run, oldState.scope(), "CHARACTERS", false);
    }

    /** SPINE 校验：逐卷真相阶梯、全局线程 key 与回收卷。 */
    static PlanContracts.StorySpine parseSpine(JsonNode root, PlanContracts.ScopeContract scope) {
        return parseSpine(root, scope, null);
    }

    static PlanContracts.StorySpine parseSpine(JsonNode root, PlanContracts.ScopeContract scope,
                                               JsonNode truthContract) {
        String mainMystery = root.path("mainMystery").asText("").trim();
        String finalTruth = root.path("finalTruth").asText("").trim();
        String finalChoice = root.path("finalChoice").asText("").trim();
        if (mainMystery.isEmpty() || finalTruth.isEmpty() || finalChoice.isEmpty()) {
            throw new PlanJson.Bad("mainMystery/finalTruth/finalChoice 均不能为空");
        }
        JsonNode ladderNode = root.path("truthLadder");
        if (!ladderNode.isArray() || ladderNode.size() != scope.volumeCount()) {
            throw new PlanJson.Bad("truthLadder 必须恰好覆盖 " + scope.volumeCount() + " 卷");
        }
        List<PlanContracts.TruthStep> ladder = new ArrayList<>();
        boolean[] seen = new boolean[scope.volumeCount() + 1];
        for (JsonNode node : ladderNode) {
            int volumeNo = node.path("volumeNo").asInt(0);
            if (volumeNo < 1 || volumeNo > scope.volumeCount() || seen[volumeNo]) {
                throw new PlanJson.Bad("truthLadder.volumeNo 非法或重复: " + volumeNo);
            }
            seen[volumeNo] = true;
            List<String> reveals = strings(node.path("reveals"));
            if (reveals.isEmpty()) {
                throw new PlanJson.Bad("第" + volumeNo + "卷 reveals 不能为空");
            }
            ladder.add(new PlanContracts.TruthStep(
                    volumeNo, reveals, strings(node.path("forbidden")),
                    strings(node.path("evidenceThreads"))));
        }
        List<PlanContracts.ThreadContract> threads = new ArrayList<>();
        Set<String> threadKeys = new LinkedHashSet<>();
        for (JsonNode node : root.path("globalThreads")) {
            String key = node.path("threadKey").asText("").trim();
            if (key.isEmpty() || !threadKeys.add(key)) {
                throw new PlanJson.Bad("globalThreads.threadKey 为空或重复: " + key);
            }
            int payoffVolume = node.path("payoffVolume").asInt(0);
            if (payoffVolume < 1 || payoffVolume > scope.volumeCount()) {
                throw new PlanJson.Bad("线程 " + key + " 的 payoffVolume 非法");
            }
            threads.add(new PlanContracts.ThreadContract(
                    key,
                    parseEnum(PlanContracts.ThreadKind.class, node.path("kind").asText("CLUE"), "kind"),
                    node.path("title").asText(""),
                    node.path("openedVolume").isNumber() ? node.path("openedVolume").asInt() : 1,
                    payoffVolume,
                    blankToNull(node.path("ownerCharacterKey").asText("")),
                    PlanContracts.ThreadStatus.OPEN,
                    MAPPER.convertValue(node.path("contract"),
                            MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class))));
        }
        if (truthContract != null && truthContract.isObject()) {
            String contractTruth = truthContract.path("finalTruth").asText("");
            String contractChoice = truthContract.path("finalChoice").asText("");
            if (!contractTruth.isBlank()
                    && DeriveTruthContractService.truthConflicts(contractTruth, finalTruth)) {
                throw new PlanJson.Bad("SPINE finalTruth 与 TruthContract 不一致：" + finalTruth);
            }
            if (!contractChoice.isBlank()
                    && !contractChoice.startsWith("按唯一真相合同完成最终选择")
                    && DeriveTruthContractService.truthConflicts(contractChoice, finalChoice)) {
                throw new PlanJson.Bad("SPINE finalChoice 与 TruthContract 不一致：" + finalChoice);
            }
        }
        return new PlanContracts.StorySpine(mainMystery, finalTruth, finalChoice, ladder, threads);
    }

    private void persistSpine(RunDO run, PlanContracts.StorySpine spine) {
        long runId = run.getId();
        long novelId = run.getNovelId();
        for (PlanContracts.ThreadContract thread : spine.globalThreads()) {
            long existing = storyThreadMapper.selectCount(new LambdaQueryWrapper<StoryThreadDO>()
                    .eq(StoryThreadDO::getRunId, runId)
                    .eq(StoryThreadDO::getThreadKey, thread.threadKey()));
            if (existing > 0) {
                continue;
            }
            StoryThreadDO row = new StoryThreadDO();
            row.setRunId(runId);
            row.setNovelId(novelId);
            row.setThreadKey(thread.threadKey());
            row.setKind(thread.kind().name());
            row.setTitle(thread.title());
            row.setOpenedVolume(thread.openedVolume());
            row.setPayoffVolume(thread.payoffVolume());
            row.setOwnerCharacterKey(thread.ownerCharacterKey());
            row.setStatus(thread.status().name());
            row.setContract(toJson(thread.contract()));
            storyThreadMapper.insertFull(row);
        }
        writeSubContract(run, "STORY_SPINE", spine);
    }

    /** 卷合同校验。 */
    static List<PlanContracts.VolumeContract> parseVolumes(
            JsonNode root, PlanContracts.ScopeContract scope) {
        JsonNode arr = root.path("volumes");
        if (!arr.isArray() || arr.size() != scope.volumeCount()) {
            throw new PlanJson.Bad("volumes 数量必须等于 " + scope.volumeCount());
        }
        List<PlanContracts.VolumeContract> out = new ArrayList<>();
        int expectFrom = 1;
        for (int i = 0; i < arr.size(); i++) {
            JsonNode node = arr.get(i);
            int volumeNo = node.path("volumeNo").asInt(0);
            int from = node.path("fromChapter").asInt(0);
            int to = node.path("toChapter").asInt(0);
            boolean last = i == arr.size() - 1;
            String nextHook = node.path("nextHook").asText("").trim();
            if (volumeNo != i + 1 || from != expectFrom || to < from
                    || to > scope.chapterCount()) {
                throw new PlanJson.Bad("卷号或章号范围不连续");
            }
            if (!last && nextHook.isEmpty()) {
                throw new PlanJson.Bad("第" + volumeNo + "卷非末卷但 nextHook 为空");
            }
            if (last && !nextHook.isEmpty()) {
                throw new PlanJson.Bad("最终卷 nextHook 必须为空");
            }
            String localQuestion = required(node, "localQuestion");
            String falseAnswer = required(node, "falseAnswer");
            String trueAnswer = required(node, "trueAnswer");
            String mainAdvance = required(node, "mainMysteryAdvance");
            String closureProof = required(node, "localClosureProof");
            String role = last ? "FINAL" : "LOCAL";
            out.add(new PlanContracts.VolumeContract(
                    volumeNo, from, to, required(node, "arc"),
                    localQuestion, falseAnswer, trueAnswer, mainAdvance,
                    strings(node.path("focusCharacters")),
                    strings(node.path("relationshipChanges")),
                    strings(node.path("requiredThreads")),
                    strings(node.path("forbiddenReveals")),
                    closureProof, nextHook, role));
            expectFrom = to + 1;
        }
        if (expectFrom != scope.chapterCount() + 1) {
            throw new PlanJson.Bad("卷章号未覆盖到 " + scope.chapterCount());
        }
        return out;
    }

    /** 角色叙事计划校验。 */
    static List<PlanContracts.CharacterContract> parseCharacters(
            JsonNode root, PlanContracts.ScopeContract scope) {
        JsonNode arr = root.path("characters");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new PlanJson.Bad("characters 不能为空");
        }
        List<PlanContracts.CharacterContract> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode node : arr) {
            String key = required(node, "characterKey");
            if (!key.matches("C\\d+") || !keys.add(key)) {
                throw new PlanJson.Bad("characterKey 必须唯一且形如 C1: " + key);
            }
            int spotlight = volumeOrZero(node.path("spotlightVolume"));
            int conflict = volumeOrZero(node.path("conflictVolume"));
            int payoff = volumeOrZero(node.path("payoffVolume"));
            requireVolume(spotlight, scope, "spotlightVolume", key);
            requireVolume(conflict, scope, "conflictVolume", key);
            requireVolume(payoff, scope, "payoffVolume", key);
            String choice = required(node, "highlightChoice");
            String cost = required(node, "highlightCost");
            out.add(new PlanContracts.CharacterContract(
                    key,
                    blankToNull(node.path("displayNameHint").asText("")),
                    parseEnum(PlanContracts.CharacterFunction.class,
                            node.path("function").asText(""), "function"),
                    required(node, "uniqueContribution"),
                    required(node, "want"),
                    required(node, "flaw"),
                    strings(node.path("requiredSetup")),
                    strings(node.path("conflictTargets")),
                    required(node, "functionalConflict"),
                    choice, cost,
                    strings(node.path("relationshipChanges")),
                    required(node, "payoffProof"),
                    spotlight, conflict, payoff,
                    required(node, "arcBefore"),
                    required(node, "arcAfter")));
        }
        return out;
    }

    private void persistVolumes(RunDO run, List<PlanContracts.VolumeContract> volumes) {
        for (PlanContracts.VolumeContract contract : volumes) {
            VolumePlanDO row = volumePlanMapper.selectOne(new LambdaQueryWrapper<VolumePlanDO>()
                    .eq(VolumePlanDO::getRunId, run.getId())
                    .eq(VolumePlanDO::getVolumeNo, contract.volumeNo())
                    .last("LIMIT 1"));
            boolean insert = row == null;
            if (insert) {
                row = new VolumePlanDO();
                row.setRunId(run.getId());
                row.setNovelId(run.getNovelId());
                row.setVolumeNo(contract.volumeNo());
            }
            row.setFromChapter(contract.fromChapter());
            row.setToChapter(contract.toChapter());
            row.setArc(contract.arc());
            row.setLocalEvent(contract.localQuestion());
            row.setLocalClosure(contract.localClosureProof());
            row.setNextHook(contract.nextHook());
            row.setGlobalRole(contract.globalRole());
            row.setStatus("PLANNED");
            if (insert) {
                volumePlanMapper.insert(row);
            } else {
                volumePlanMapper.updatePlanning(row);
            }
            volumePlanMapper.updateContract(row.getId(), toJson(contract));
        }
    }

    private List<PlanContracts.VolumeContract> loadVolumeContracts(long runId) {
        List<VolumePlanDO> rows = volumePlanMapper.selectList(new LambdaQueryWrapper<VolumePlanDO>()
                .eq(VolumePlanDO::getRunId, runId)
                .orderByAsc(VolumePlanDO::getVolumeNo));
        List<PlanContracts.VolumeContract> out = new ArrayList<>();
        for (VolumePlanDO row : rows) {
            try {
                out.add(MAPPER.readValue(row.getContract(), PlanContracts.VolumeContract.class));
            } catch (Exception e) {
                throw new IllegalStateException("卷合同 JSON 损坏: " + row.getId(), e);
            }
        }
        return out;
    }

    private void persistCharacters(RunDO run, List<PlanContracts.CharacterContract> characters) {
        for (PlanContracts.CharacterContract contract : characters) {
            CharacterPlanDO row = characterPlanMapper.selectOne(
                    new LambdaQueryWrapper<CharacterPlanDO>()
                            .eq(CharacterPlanDO::getRunId, run.getId())
                            .eq(CharacterPlanDO::getCharacterKey, contract.characterKey())
                            .last("LIMIT 1"));
            boolean insert = row == null;
            if (insert) {
                row = new CharacterPlanDO();
                row.setRunId(run.getId());
                row.setNovelId(run.getNovelId());
                row.setCharacterKey(contract.characterKey());
            }
            row.setDisplayNameHint(contract.displayNameHint());
            row.setFunctionCode(contract.function().name());
            row.setSpotlightVolume(contract.spotlightVolume());
            row.setConflictVolume(contract.conflictVolume());
            row.setPayoffVolume(contract.payoffVolume());
            row.setArcBefore(contract.arcBefore());
            row.setArcAfter(contract.arcAfter());
            row.setStatus("PLANNED");
            if (insert) {
                row.setContract(toJson(contract));
                characterPlanMapper.insertFull(row);
            } else {
                characterPlanMapper.updateScalars(row);
                characterPlanMapper.updateContract(row.getId(), toJson(contract));
            }
            for (String relationKey : contract.relationshipChanges()) {
                persistRelationshipThread(run, contract, relationKey);
            }
        }
    }

    private void persistRelationshipThread(RunDO run, PlanContracts.CharacterContract contract,
                                           String threadKey) {
        if (threadKey == null || threadKey.isBlank()) {
            return;
        }
        long existing = storyThreadMapper.selectCount(new LambdaQueryWrapper<StoryThreadDO>()
                .eq(StoryThreadDO::getRunId, run.getId())
                .eq(StoryThreadDO::getThreadKey, threadKey));
        if (existing > 0) {
            return;
        }
        StoryThreadDO row = new StoryThreadDO();
        row.setRunId(run.getId());
        row.setNovelId(run.getNovelId());
        row.setThreadKey(threadKey);
        row.setKind(PlanContracts.ThreadKind.RELATIONSHIP.name());
        row.setTitle(contract.functionalConflict());
        row.setOpenedVolume(contract.spotlightVolume());
        row.setPayoffVolume(contract.payoffVolume());
        row.setOwnerCharacterKey(contract.characterKey());
        row.setStatus(PlanContracts.ThreadStatus.OPEN.name());
        row.setContract(toJson(Map.of(
                "fromCharacter", contract.characterKey(),
                "functionalConflict", contract.functionalConflict(),
                "highlightChoice", contract.highlightChoice(),
                "highlightCost", contract.highlightCost())));
        storyThreadMapper.insertFull(row);
    }

    /** ⑥a-0 输入：只提供规模判断需要的事实。 */
    static String buildScaleUser(RunDO run, int beatCount) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("创作框架", nullToEmpty(run.getParamIntent()));
        root.put("字数区间", run.getParamWordsMin() + "~" + run.getParamWordsMax());
        root.set("类别", readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace() == null ? "" : run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        root.put("拍数", beatCount);
        root.put("规模判断口径", "显式卷数优先；单卷框架不得为了字数或拍数扩卷");
        return root.toString();
    }

    /** ⑥a-0 校验：显式卷号是硬约束，拍数只做可行性边界。 */
    static PlanContracts.ScopeContract parseScale(JsonNode root, RunDO run, int beatCount) {
        int chapterCount = root.path("chapterCount").asInt(0);
        int chapterAvg = root.path("chapterAvg").asInt(0);
        int volumeCount = root.path("volumeCount").asInt(0);
        PlanContracts.ScopeDecision decision;
        try {
            decision = PlanContracts.ScopeDecision.valueOf(
                    root.path("scopeDecision").asText("").trim());
        } catch (Exception e) {
            throw new PlanJson.Bad("scopeDecision 必须是 SINGLE_VOLUME/MULTI_VOLUME/NEED_CONFIRMATION");
        }
        if (chapterCount < 1) {
            throw new PlanJson.Bad("chapterCount 必须≥1");
        }
        if (chapterAvg < 1) {
            throw new PlanJson.Bad("chapterAvg 必须>0");
        }
        if (volumeCount < 1) {
            throw new PlanJson.Bad("volumeCount 必须≥1");
        }
        if (decision == PlanContracts.ScopeDecision.SINGLE_VOLUME && volumeCount != 1) {
            throw new PlanJson.Bad("SINGLE_VOLUME 必须 volumeCount=1");
        }
        if (decision == PlanContracts.ScopeDecision.MULTI_VOLUME && volumeCount < 2) {
            throw new PlanJson.Bad("MULTI_VOLUME 必须 volumeCount≥2");
        }
        int explicitVolumes = maxExplicitVolume(run.getParamIntent());
        if (explicitVolumes > 0 && volumeCount != explicitVolumes) {
            throw new PlanJson.Bad("创作框架显式写到了第" + explicitVolumes
                    + "卷，volumeCount 必须为 " + explicitVolumes + "，当前 " + volumeCount);
        }
        if (decision == PlanContracts.ScopeDecision.NEED_CONFIRMATION) {
            return new ScopeContractFactory(decision, volumeCount, chapterCount, chapterAvg, root).build();
        }
        if (Boolean.TRUE.equals(run.getLenCheck())) {
            if (chapterCount < DerivePlanService.CHAPTER_COUNT_MIN
                    || chapterCount > DerivePlanService.CHAPTER_COUNT_MAX) {
                throw new PlanJson.Bad("chapterCount 必须在 "
                        + DerivePlanService.CHAPTER_COUNT_MIN + "~"
                        + DerivePlanService.CHAPTER_COUNT_MAX);
            }
            if (chapterAvg < DerivePlanService.CHAPTER_AVG_MIN
                    || chapterAvg > DerivePlanService.CHAPTER_AVG_MAX) {
                throw new PlanJson.Bad("chapterAvg 必须在 "
                        + DerivePlanService.CHAPTER_AVG_MIN + "~"
                        + DerivePlanService.CHAPTER_AVG_MAX);
            }
            long total = (long) chapterCount * chapterAvg;
            if (total < run.getParamWordsMin() || total > run.getParamWordsMax()) {
                throw new PlanJson.Bad("章数×章均=" + total + " 超出字数区间");
            }
        }
        return new ScopeContractFactory(decision, volumeCount, chapterCount, chapterAvg, root).build();
    }

    private record ScopeContractFactory(
            PlanContracts.ScopeDecision decision,
            int volumeCount,
            int chapterCount,
            int chapterAvg,
            JsonNode root) {
        PlanContracts.ScopeContract build() {
            List<String> evidence = strings(root.path("scopeEvidence"));
            return new PlanContracts.ScopeContract(
                    decision, volumeCount, chapterCount, chapterAvg, evidence);
        }
    }

    private long createNovel(RunDO run) {
        NovelDO novel = new NovelDO();
        novel.setTitle("未命名·RUN-" + run.getId());
        novel.setSourceType("GENERATED");
        novel.setCategoryTags(run.getParamTags());
        novel.setForm(run.getParamForm());
        novel.setRunId(run.getId());
        long novelId = novelMapper.insertFull(novel);
        runMapper.updateNovelId(run.getId(), novelId);
        return novelId;
    }

    private int countCandidateBeats(RunDO run) {
        List<Long> arcIds = DerivePlanService.jsonLongArray(run.getPickedOutlineIds());
        Set<Long> arcSet = new LinkedHashSet<>(arcIds);
        int count = 0;
        for (Long arcId : arcIds) {
            count += Math.toIntExact(assetBeatMapper.selectCount(
                    new LambdaQueryWrapper<AssetBeatDO>()
                            .eq(AssetBeatDO::getParentArcId, arcId)));
        }
        for (Long beatId : DerivePlanService.jsonLongArray(run.getPickedBeatIds())) {
            AssetBeatDO beat = assetBeatMapper.selectById(beatId);
            if (beat != null && (beat.getParentArcId() == null
                    || !arcSet.contains(beat.getParentArcId()))) {
                count++;
            }
        }
        return count;
    }

    private RunDO requirePlanningRun(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"PLANNING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "故事规划需要 run 处于 PLANNING（当前 " + run.getStatus() + "）");
        }
        return run;
    }

    private PlanContracts.PlanV2State readPlanState(String stepStatus) {
        JsonNode plan = readSteps(stepStatus).path("PLAN_V2");
        return plan.isObject() ? PlanV2StateCodec.read(plan.toString()) : null;
    }

    private void writePlanState(RunDO run, PlanContracts.PlanV2State state) {
        RunDO current = runMapper.selectById(run.getId());
        ObjectNode root = (ObjectNode) readTreeSafe(current == null
                ? run.getStepStatus() : current.getStepStatus()).deepCopy();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        steps.set("PLAN_V2", MAPPER.valueToTree(state));
        runMapper.updateStepStatus(run.getId(), root.toString());
    }

    private void writeSubContract(RunDO run, String key, Object value) {
        RunDO current = runMapper.selectById(run.getId());
        ObjectNode root = (ObjectNode) readTreeSafe(current == null
                ? run.getStepStatus() : current.getStepStatus()).deepCopy();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        steps.set(key, MAPPER.valueToTree(value));
        runMapper.updateStepStatus(run.getId(), root.toString());
    }

    private static JsonNode readSteps(String stepStatus) {
        return readTreeSafe(stepStatus).path("steps");
    }

    private static JsonNode readTreeSafe(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static PlanVO planVO(RunDO run, PlanContracts.ScopeContract scope,
                                 String unit, boolean allDone) {
        Long novelId = run.getNovelId();
        return new PlanVO(run.getId(), novelId == null ? 0 : novelId,
                scope.chapterCount(), 0, 0, List.of(), unit,
                0, DerivePlanService.totalWindows(scope.chapterCount()), 0, 0, allDone);
    }

    static PlanContracts.StorySpine spineFromTruthContract(
            PlanContracts.ScopeContract scope, JsonNode contract) {
        String truth = contract.path("finalTruth").asText("");
        String choice = contract.path("finalChoice").asText("");
        List<String> forbidden = strings(contract.path("forbidden"));
        List<String> evidence = strings(contract.path("evidenceThreads"));
        List<PlanContracts.TruthStep> ladder = new ArrayList<>();
        List<PlanContracts.ThreadContract> threads = new ArrayList<>();
        for (int volumeNo = 1; volumeNo <= scope.volumeCount(); volumeNo++) {
            ladder.add(new PlanContracts.TruthStep(volumeNo, List.of(truth),
                    forbidden, evidence));
        }
        for (String key : evidence) {
            PlanContracts.ThreadKind kind = key.startsWith("OBJ-")
                    ? PlanContracts.ThreadKind.OBJECT : PlanContracts.ThreadKind.CLUE;
            threads.add(new PlanContracts.ThreadContract(key, kind, key, 1,
                    scope.volumeCount(), "C1", PlanContracts.ThreadStatus.OPEN, Map.of()));
        }
        return new PlanContracts.StorySpine(truth, truth, choice, ladder, threads);
    }

    static PlanContracts.VolumeContract volumeFromTruthContract(
            PlanContracts.ScopeContract scope, JsonNode contract) {
        String truth = contract.path("finalTruth").asText("");
        List<String> evidence = strings(contract.path("evidenceThreads"));
        return new PlanContracts.VolumeContract(1, 1, scope.chapterCount(), truth,
                "真相合同如何在本卷兑现", "任何与真相合同不一致的解释", truth,
                truth, List.of("C1"), List.of(), evidence, List.of(),
                truth, "", "FINAL");
    }

    private static void putMainline(ObjectNode user, RunDO run) {
        JsonNode contract = readSteps(run.getStepStatus()).path("TRUTH_CONTRACT");
        if (contract.isObject() && !contract.path("finalTruth").asText("").isBlank()) {
            user.put("主线", contract.path("finalTruth").asText(""));
        } else {
            user.put("主线", nullToEmpty(run.getWelded()));
        }
    }

    private static void putTruthContract(ObjectNode user, RunDO run) {
        JsonNode contract = readSteps(run.getStepStatus()).path("TRUTH_CONTRACT");
        if (contract.isObject()) {
            user.set("TruthContract（唯一真相合同，冲突时以此为准）", contract);
        }
    }

    static void requireTruthAlignment(List<PlanContracts.VolumeContract> volumes,
                                      JsonNode contract) {
        if (contract == null || !contract.isObject()) {
            return;
        }
        String truth = contract.path("finalTruth").asText("");
        if (truth.isBlank()) {
            return;
        }
        for (PlanContracts.VolumeContract volume : volumes) {
            if (DeriveTruthContractService.truthConflicts(truth, volume.trueAnswer())) {
                throw new PlanJson.Bad("第" + volume.volumeNo()
                        + "卷 trueAnswer 与 TruthContract 不一致：" + volume.trueAnswer());
            }
        }
    }

    private static void putGateDirective(ObjectNode user, RunDO run) {
        String directive = DeriveGateService.gateDirective(run.getStepStatus());
        if (directive != null && !directive.isBlank()) {
            user.put("上轮立项闸减法指令（最高优先级，必须逐条消除）", directive);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> out = new ArrayList<>(values == null ? List.of() : values);
        out.add(value);
        return out;
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> out.add(n.asText("")));
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new PlanJson.Bad(field + " 不能为空");
        }
        return value;
    }

    private static int volumeOrZero(JsonNode node) {
        return node.isNumber() ? node.asInt() : 0;
    }

    private static void requireVolume(int volumeNo, PlanContracts.ScopeContract scope,
                                      String field, String key) {
        if (volumeNo < 1 || volumeNo > scope.volumeCount()) {
            throw new PlanJson.Bad(key + "." + field + " 越界: " + volumeNo);
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw.trim());
        } catch (Exception e) {
            throw new PlanJson.Bad(field + " 非法: " + raw);
        }
    }

    static int maxExplicitVolume(String intent) {
        if (intent == null || intent.isBlank()) {
            return 0;
        }
        int max = 0;
        Matcher matcher = VOLUME_MARKER.matcher(intent);
        while (matcher.find()) {
            max = Math.max(max, parseVolumeNumber(matcher.group(1)));
        }
        return max;
    }

    static int parseVolumeNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(raw);
        }
        int total = 0;
        int current = 0;
        for (char c : raw.toCharArray()) {
            int digit = switch (c) {
                case '一' -> 1;
                case '二', '两' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> 0;
            };
            if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (c == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else {
                current = digit;
            }
        }
        return total + current;
    }
}
