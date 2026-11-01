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
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.contract.StoryContractValidator;
import com.zzdzz.novelgen.derive.genre.GenreContract;
import com.zzdzz.novelgen.derive.genre.RomanceCharacterSlot;
import com.zzdzz.novelgen.derive.genre.RomanceContract;
import com.zzdzz.novelgen.derive.genre.RomanceRelationship;
import com.zzdzz.novelgen.derive.genre.RomanceStage;
import com.zzdzz.novelgen.derive.genre.RomanceStageKind;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
@Slf4j
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zzdzz.novelgen.derive.mapper.ChapterMapper chapterMapper;
    private final LlmJson llmJson;
    private final DeriveScaffoldService scaffoldService;
    private final DeriveCharacterBusinessModelService businessModelService;
    private final DeriveContractService contractService;
    /** 章级日程：CONTRACT 之后、SPINE 之前的纯函数投影（@Autowired 保留既有构造器签名）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeriveChapterScheduleService scheduleService;

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
        state = backfillLegacyContract(run, state);
        state = ensureScheduleForLegacy(run, state);
        return switch (state.currentUnit()) {
            case SCALE -> advanceScale(run, state);
            case CONTRACT -> advanceContract(run, state);
            case SCHEDULE -> advanceSchedule(run, state);
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
                PlanContracts.PlanUnit.CONTRACT, state.completedUnits(), scope, state.volumeUnits());
        writePlanState(run, next);
        return planVO(run, scope, "CONTRACT", false);
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
                PlanContracts.PlanUnit.CONTRACT,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.SCALE),
                scope, oldState.volumeUnits());
        writePlanState(run, next);
        return planVO(run, scope, "SCALE", false);
    }

    /**
     * CONTRACT 单元：SCALE 之后、SPINE 之前生成并冻结唯一故事合同。
     *
     * <p>合同生成失败即 fail-fast，禁止回退 {@code run.welded} 走旧路径；业务模型（能力包/制度/组织）
     * 在此并入合同快照，且只更新 constraint_kernel，不产生新合同版本。
     */
    private PlanVO advanceContract(RunDO run, PlanContracts.PlanV2State oldState) {
        if (oldState.scope() == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "CONTRACT 缺少规模或成品书骨架");
        }
        if (isRomance(run)) {
            GenreContract existing = contractService.loadGenreContract(run);
            RomanceContract romance = existing instanceof RomanceContract current
                    ? current : deterministicRomanceContract(run, oldState.scope());
            contractService.freezeGenreContract(run, romance, oldState.scope());
            ensureRomanceVolumePlan(run, oldState.scope(), romance);
            PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                    PlanContracts.PlanUnit.SCHEDULE,
                    append(oldState.completedUnits(), PlanContracts.PlanUnit.CONTRACT),
                    oldState.scope(), oldState.volumeUnits());
            writePlanState(run, next);
            log.info("言情合同已冻结：runId={} version={} relation={}",
                    run.getId(), romance.version(), romance.primaryRelationship().threadKey());
            return planVO(run, oldState.scope(), "CONTRACT", false);
        }
        ScaffoldContract scaffold = readScaffold(run);
        StoryContract contract = contractService.generateIfAbsent(run, oldState.scope(), scaffold);
        List<PlanContracts.CharacterContract> slots = castCharacters(contract, oldState.scope());
        businessModelService.ensureWorldOrganizations(run.getId(), slots, oldState.scope());
        businessModelService.ensureCharactersRelationshipsEvents(run.getId(), slots, oldState.scope());
        contractService.assertUnchanged(contract, contractService.require(run), "CONTRACT 单元");
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.SCHEDULE,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.CONTRACT),
                oldState.scope(), oldState.volumeUnits());
        writePlanState(run, next);
        return planVO(run, oldState.scope(), "CONTRACT", false);
    }

    /**
     * SCHEDULE 单元：合同 → 章级日程的纯函数投影并落库（不调 LLM）。
     *
     * <p>日程是 ROUTE/⑥b/P6 唯一的章号来源；投影失败即 fail-fast（CONTRACT_SCHEDULE_INVALID），
     * 禁止带着不自洽的合同继续往下走。
     */
    private PlanVO advanceSchedule(RunDO run, PlanContracts.PlanV2State oldState) {
        if (oldState.scope() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SCHEDULE 缺少规模合同");
        }
        if (scheduleService == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SCHEDULE 单元未装配日程服务");
        }
        GenreContract contract = contractService.loadGenreContract(run);
        if (contract == null) {
            // 旧测试/历史 run 可能只有 StoryContract；日程投影本身已由 project() 完成。
            try {
                contractService.require(run);
            } catch (RuntimeException ignored) {
                // 保持原 fail-fast 语义由后续路由/校验节点给出，不在 SCHEDULE 提前 NPE。
            }
        }
        List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> entries =
                scheduleService.persist(run, scheduleService.project(run));
        boolean romance = contract instanceof RomanceContract;
        PlanContracts.PlanUnit nextUnit = romance
                ? PlanContracts.PlanUnit.SLOT_ROUTE : PlanContracts.PlanUnit.SPINE;
        List<PlanContracts.PlanUnit> completed = append(oldState.completedUnits(),
                PlanContracts.PlanUnit.SCHEDULE);
        Map<Integer, PlanContracts.PlanUnit> volumeUnits = oldState.volumeUnits();
        if (romance) {
            completed = append(completed, PlanContracts.PlanUnit.SPINE);
            completed = append(completed, PlanContracts.PlanUnit.VOLUMES);
            completed = append(completed, PlanContracts.PlanUnit.CHARACTERS);
            volumeUnits = new LinkedHashMap<>();
            for (int volumeNo = 1; volumeNo <= oldState.scope().volumeCount(); volumeNo++) {
                volumeUnits.put(volumeNo, PlanContracts.PlanUnit.SLOT_ROUTE);
            }
        }
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                nextUnit, completed, oldState.scope(), volumeUnits);
        writePlanState(run, next);
        log.info("SCHEDULE 单元完成：runId={} contractType={} 日程章数={}",
                run.getId(), contract == null ? "LEGACY_STORY" : contract.getClass().getSimpleName(),
                entries.size());
        return planVO(run, oldState.scope(), "SCHEDULE", false);
    }

    /**
     * 旧 run 兼容：已在 SPINE 及之后但没有日程行时补投影；日程版本落后于合同时告警并覆盖。
     * 合同不存在时不在此处造日程（由 backfillLegacyContract 先退回 CONTRACT）。
     */
    PlanContracts.PlanV2State ensureScheduleForLegacy(RunDO run,
                                                      PlanContracts.PlanV2State state) {
        if (state == null || scheduleService == null) {
            return state;
        }
        PlanContracts.PlanUnit unit = state.currentUnit();
        boolean downstream = unit == PlanContracts.PlanUnit.SPINE
                || unit == PlanContracts.PlanUnit.VOLUMES
                || unit == PlanContracts.PlanUnit.CHARACTERS
                || unit == PlanContracts.PlanUnit.SLOT_ROUTE
                || unit == PlanContracts.PlanUnit.VALIDATE
                || unit == PlanContracts.PlanUnit.DONE;
        if (!downstream) {
            return state;
        }
        StoryContract contract = contractService.load(run);
        if (contract == null) {
            return state;
        }
        List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> existing =
                scheduleService.load(run);
        if (existing.isEmpty()) {
            scheduleService.persist(run, scheduleService.project(run));
            log.info("旧 run 补投影章级日程：runId={} 原单元={} 合同版本={}",
                    run.getId(), unit, contract.version());
            return state;
        }
        try {
            scheduleService.assertCurrent(run);
        } catch (BizException stale) {
            log.warn("旧 run 日程版本落后于合同，重新投影覆盖：runId={} 原因={}",
                    run.getId(), stale.getMessage());
            scheduleService.persist(run, scheduleService.project(run));
        }
        return state;
    }

    /**
     * SPINE：合同存在时只做确定性重建，不再调 LLM——同一份冻结合同永远得到同一根脊柱，
     * 从机制上消灭“脊柱与真相卡两套案情”。没有合同一律 CONTRACT_REQUIRED，绝不回退 welded。
     */
    private PlanVO advanceSpine(RunDO run, PlanContracts.PlanV2State oldState) {
        StoryContract contract = contractService.require(run);
        if (oldState.scope() == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SPINE 缺少规模或成品书骨架");
        }
        PlanContracts.StorySpine spine = spineFromContract(contract, oldState.scope());
        persistSpine(run, spine);
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.VOLUMES,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.SPINE),
                oldState.scope(), oldState.volumeUnits());
        writePlanState(run, next);
        return planVO(run, oldState.scope(), "SPINE", false);
    }
    /**
     * VOLUMES：卷合同只细化冻结合同——trueAnswer/forbidden/requiredThreads/focusCharacters
     * 一律取自合同，LLM 只负责 localQuestion/falseAnswer/arc 等叙述字段。
     */
    private PlanVO advanceVolumes(RunDO run, PlanContracts.PlanV2State oldState) {
        if (oldState.scope() == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "VOLUMES 缺少规模或成品书骨架");
        }
        StoryContract contract = contractService.require(run);
        ScaffoldContract scaffold = readScaffold(run);
        ObjectNode user = MAPPER.createObjectNode();
        user.set("scope", MAPPER.valueToTree(oldState.scope()));
        user.set("storySpine", readSteps(run.getStepStatus()).path("STORY_SPINE"));
        if (scaffold != null && scaffold.volumeRanges() != null
                && !scaffold.volumeRanges().isEmpty()) {
            user.set("volumeRanges（只读：分卷边界必须逐字一致）",
                    MAPPER.valueToTree(scaffold.volumeRanges()));
        }
        putFrozenContract(user, contract);
        user.put("创作框架", nullToEmpty(run.getParamIntent()));
        putMainline(user, run);
        putGateDirective(user, run);
        putTruthContract(user, run);
        List<PlanContracts.VolumeContract> volumes = llmJson.ask(
                new LlmPort.ChatRequest(StoryPlanningPrompts.NODE_VOLUMES, run.getNovelId(), null,
                        run.getId(),
                        List.of(LlmPort.Message.system(StoryPlanningPrompts.VOLUMES),
                                LlmPort.Message.user(user.toString())),
                        0.3),
                root -> {
                    List<PlanContracts.VolumeContract> parsed =
                            parseVolumes(root, oldState.scope());
                    requireTruthAlignment(parsed, canonicalContract(run));
                    return parsed;
                }, 3);
        volumes = reconcileVolumes(volumes, contract, oldState.scope());
        validateVolumeSchedule(volumes, contract, scaffold);
        persistVolumes(run, volumes);
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.CHARACTERS,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.VOLUMES),
                oldState.scope(), oldState.volumeUnits());
        writePlanState(run, next);
        return planVO(run, oldState.scope(), "VOLUMES", false);
    }
    /**
     * CHARACTERS：角色叙事计划只能细化合同 cast——key 与职能来自合同槽位，姓名不许改，
     * 不许出现合同之外的第四个角色（CONTRACT_CAST_DRIFT）。
     */
    private PlanVO advanceCharacters(RunDO run, PlanContracts.PlanV2State oldState) {
        if (oldState.scope() == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "CHARACTERS 缺少规模或成品书骨架");
        }
        StoryContract contract = contractService.require(run);
        ObjectNode user = MAPPER.createObjectNode();
        user.set("scope", MAPPER.valueToTree(oldState.scope()));
        user.set("storySpine", readSteps(run.getStepStatus()).path("STORY_SPINE"));
        user.set("volumes", MAPPER.valueToTree(loadVolumeContracts(run.getId())));
        putFrozenContract(user, contract);
        NovelDO novel = novelMapper.selectById(run.getNovelId());
        if (novel != null) {
            JsonNode assets = readTreeSafe(novel.getStoryAssets());
            user.set("brief", assets.path("brief"));
        }
        user.put("创作框架", nullToEmpty(run.getParamIntent()));
        putGateDirective(user, run);
        putTruthContract(user, run);
        List<PlanContracts.CharacterContract> characters = llmJson.ask(
                new LlmPort.ChatRequest(StoryPlanningPrompts.NODE_CHARACTERS, run.getNovelId(), null,
                        run.getId(),
                        List.of(LlmPort.Message.system(StoryPlanningPrompts.CHARACTERS),
                                LlmPort.Message.user(user.toString())),
                        0.3),
                root -> reconcileCharacters(parseCharacters(root, oldState.scope()), contract,
                        oldState.scope()), 3);
        List<String> drift = StoryContractValidator.validateCharacters(characters, contract);
        if (!drift.isEmpty()) {
            throw new PlanJson.Bad(String.join(";", drift));
        }
        persistCharacters(run, characters);
        // P6 Task4：角色合同回炉重做时，已 VALIDATE 的卷路由保留（局部修复，禁止整本重路由）。
        Map<Integer, PlanContracts.PlanUnit> volumeUnits = new java.util.LinkedHashMap<>();
        for (int volumeNo = 1; volumeNo <= oldState.scope().volumeCount(); volumeNo++) {
            PlanContracts.PlanUnit existing = oldState.volumeUnits().get(volumeNo);
            volumeUnits.put(volumeNo, existing == PlanContracts.PlanUnit.VALIDATE
                    ? PlanContracts.PlanUnit.VALIDATE : PlanContracts.PlanUnit.SLOT_ROUTE);
        }
        PlanContracts.PlanV2State next = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.SLOT_ROUTE,
                append(oldState.completedUnits(), PlanContracts.PlanUnit.CHARACTERS),
                oldState.scope(), volumeUnits);
        writePlanState(run, next);
        return planVO(run, oldState.scope(), "CHARACTERS", false);
    }

    /** 把冻结合同放进提示词输入（结构化节点，模型只能照抄不能改写）。 */
    private void putFrozenContract(ObjectNode user, StoryContract contract) {
        user.set("FROZEN_CONTRACT（唯一合同：cast/真相/线索/线程/禁令，禁止改写）",
                readTreeSafe(contractService.promptContext(contract)));
    }

    /** 读 ⑥a SCALE 段落下的结构骨架（合同提示词的结构事实来源）。 */
    private ScaffoldContract readScaffold(RunDO run) {
        JsonNode node = readSteps(run.getStepStatus()).path("SCAFFOLD");
        if (!node.isObject()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(node, ScaffoldContract.class);
        } catch (Exception e) {
            log.warn("SCAFFOLD 子合同反序列化失败，合同生成降级为无骨架输入：{}", e.getMessage());
            return null;
        }
    }

    /** 合同 cast → 角色叙事计划槽位（业务模型阶段与角色计划共用同一份角色 key）。 */
    static List<PlanContracts.CharacterContract> castCharacters(StoryContract contract,
                                                                PlanContracts.ScopeContract scope) {
        List<PlanContracts.CharacterContract> out = new ArrayList<>();
        List<String> keys = contract.castKeys();
        for (int i = 0; i < contract.cast().size(); i++) {
            StoryContract.CharacterSlot slot = contract.cast().get(i);
            List<String> targets = keys.stream().filter(key -> !key.equals(slot.key())).toList();
            out.add(new PlanContracts.CharacterContract(
                    slot.key(), slot.name(), functionOf(slot.role()),
                    isBlank(slot.identity()) ? "承担不可替代的剧情条件" : slot.identity(),
                    "完成公开目标", "无法独自承担代价", List.of(), targets,
                    isBlank(slot.identity()) ? "角色职能与共同代价冲突" : slot.identity(),
                    "在代价出现时做出选择", "承担不可撤销的代价", List.of(),
                    "关系与代价在结局闭合", 1, Math.max(1, scope.volumeCount()),
                    Math.max(1, scope.volumeCount()), "START", "PAYOFF",
                    isBlank(slot.identity()) ? "" : slot.identity()));
        }
        return out;
    }

    private static PlanContracts.CharacterFunction functionOf(String role) {
        if (role == null || role.isBlank()) {
            return PlanContracts.CharacterFunction.SUPPORT;
        }
        try {
            return PlanContracts.CharacterFunction.valueOf(role.trim().toUpperCase());
        } catch (Exception e) {
            return PlanContracts.CharacterFunction.SUPPORT;
        }
    }

    /**
     * 合同 → 故事脊柱（确定性，不调 LLM）：mainMystery/finalTruth/finalChoice 取自合同真相核，
     * 真相阶梯取每卷 volumeTruth 切片，全局线程取合同线程——同一合同永远得到同一根脊柱。
     */
    static PlanContracts.StorySpine spineFromContract(StoryContract contract,
                                                      PlanContracts.ScopeContract scope) {
        StoryContract.Truth truth = contract.truth();
        String finalTruth = truth.finalTruth();
        String finalChoice = truth.finalChoice() == null || truth.finalChoice().isBlank()
                ? "按唯一真相合同完成最终选择：" + finalTruth : truth.finalChoice();
        List<PlanContracts.TruthStep> ladder = new ArrayList<>();
        for (int volumeNo = 1; volumeNo <= scope.volumeCount(); volumeNo++) {
            StoryContract.VolumeTruth slice = volumeSlice(contract, volumeNo);
            String reveal = slice == null || slice.reveal().isBlank() ? finalTruth : slice.reveal();
            List<String> forbidden = slice != null && !slice.forbidden().isEmpty()
                    ? slice.forbidden() : contract.forbidden();
            List<String> evidence = new ArrayList<>();
            for (StoryContract.ThreadRef thread : contract.threads()) {
                if (thread.openedVolume() <= volumeNo) {
                    evidence.add(thread.threadKey());
                }
            }
            ladder.add(new PlanContracts.TruthStep(volumeNo, List.of(reveal), forbidden, evidence));
        }
        List<PlanContracts.ThreadContract> threads = new ArrayList<>();
        for (StoryContract.ThreadRef ref : contract.threads()) {
            String title = ref.threadKey();
            for (StoryContract.Clue clue : contract.clues()) {
                if (clue.clueKey().equals(ref.threadKey())) {
                    title = clue.clue();
                    break;
                }
            }
            PlanContracts.ThreadKind kind;
            try {
                kind = PlanContracts.ThreadKind.valueOf(ref.kind() == null ? "CLUE" : ref.kind());
            } catch (Exception e) {
                kind = PlanContracts.ThreadKind.CLUE;
            }
            // 合同线索的章号必须随脊柱一起投影下去：下游（route/P6/审计）只认这一份。
            Map<String, Object> extra = new LinkedHashMap<>();
            for (StoryContract.Clue clue : contract.clues()) {
                if (clue.clueKey().equals(ref.threadKey())) {
                    extra.put("plantChapter", clue.plantedChapter());
                    extra.put("payoffChapter", clue.payoffChapter());
                    break;
                }
            }
            threads.add(new PlanContracts.ThreadContract(ref.threadKey(), kind, title,
                    ref.openedVolume(), ref.payoffVolume(), ref.ownerKey(),
                    PlanContracts.ThreadStatus.OPEN, extra));
        }
        return new PlanContracts.StorySpine(finalTruth, finalTruth, finalChoice, ladder, threads);
    }

    /** 合同某卷的真相切片；单卷书保证至少有一条 volumeNo=1。 */
    static StoryContract.VolumeTruth volumeSlice(StoryContract contract, int volumeNo) {
        for (StoryContract.VolumeTruth slice : contract.volumeTruth()) {
            if (slice.volumeNo() == volumeNo) {
                return slice;
            }
        }
        return null;
    }

    /**
     * 卷合同与合同比对：trueAnswer 与合同真相切片语义不一致即 CONTRACT_DRIFT（喂回重试）。
     */
    static void requireVolumeTruthContract(PlanContracts.VolumeContract volume, StoryContract contract) {
        if (contract == null || contract.truth() == null) {
            return;
        }
        StoryContract.VolumeTruth slice = volumeSlice(contract, volume.volumeNo());
        String expect = slice == null || slice.reveal().isBlank()
                ? contract.truth().finalTruth() : slice.reveal();
        if (DeriveTruthContractService.truthConflicts(expect, volume.trueAnswer())) {
            throw new PlanJson.Bad("CONTRACT_DRIFT：第 " + volume.volumeNo()
                    + " 卷 trueAnswer 与合同真相切片不一致：" + volume.trueAnswer());
        }
    }

    /** 卷合同收敛到合同：trueAnswer/forbiddenReveals/requiredThreads/focusCharacters 只来自合同。 */
    static List<PlanContracts.VolumeContract> reconcileVolumes(
            List<PlanContracts.VolumeContract> volumes, StoryContract contract,
            PlanContracts.ScopeContract scope) {
        List<PlanContracts.VolumeContract> out = new ArrayList<>();
        Set<String> threadKeys = new LinkedHashSet<>();
        contract.threads().forEach(thread -> threadKeys.add(thread.threadKey()));
        Set<String> castKeys = new LinkedHashSet<>(contract.castKeys());
        for (PlanContracts.VolumeContract volume : volumes) {
            StoryContract.VolumeTruth slice = volumeSlice(contract, volume.volumeNo());
            String trueAnswer = slice == null || slice.reveal().isBlank()
                    ? contract.truth().finalTruth() : slice.reveal();
            List<String> forbidden = slice != null && !slice.forbidden().isEmpty()
                    ? slice.forbidden() : contract.forbidden();
            List<String> required = volume.requiredThreads().stream()
                    .filter(threadKeys::contains).distinct().toList();
            if (required.isEmpty()) {
                required = contract.threads().stream()
                        .filter(thread -> thread.openedVolume() <= volume.volumeNo())
                        .map(StoryContract.ThreadRef::threadKey).distinct().toList();
            }
            List<String> focus = volume.focusCharacters().stream()
                    .filter(castKeys::contains).distinct().toList();
            if (focus.isEmpty()) {
                focus = List.of("C1");
            }
            out.add(new PlanContracts.VolumeContract(
                    volume.volumeNo(), volume.fromChapter(), volume.toChapter(), volume.arc(),
                    volume.localQuestion(), volume.falseAnswer(), trueAnswer,
                    volume.mainMysteryAdvance(), focus, volume.relationshipChanges(), required,
                    forbidden, volume.localClosureProof(), volume.nextHook(),
                    volume.globalRole()));
        }
        return out;
    }

    /**
     * 卷合同 × 日程一致性：分卷边界必须与 scaffold 逐字一致，合同线索的埋设/兑现章必须落在
     * 其线程声明的卷范围内——不一致即 CONTRACT_SCHEDULE_DRIFT（喂回重试，不许下游自己改章号）。
     */
    static void validateVolumeSchedule(List<PlanContracts.VolumeContract> volumes,
                                       StoryContract contract, ScaffoldContract scaffold) {
        if (scaffold != null && scaffold.volumeRanges() != null
                && !scaffold.volumeRanges().isEmpty()) {
            for (PlanContracts.VolumeContract volume : volumes) {
                ScaffoldContract.VolumeRange range = scaffold.volumeRanges().stream()
                        .filter(item -> item.volumeNo() == volume.volumeNo())
                        .findFirst().orElse(null);
                if (range == null
                        || range.fromChapter() != volume.fromChapter()
                        || range.toChapter() != volume.toChapter()) {
                    throw new PlanJson.Bad("CONTRACT_SCHEDULE_DRIFT：第" + volume.volumeNo()
                            + "卷范围 " + volume.fromChapter() + ".." + volume.toChapter()
                            + " 与 scaffold 不一致（期望 "
                            + (range == null ? "缺失" : range.fromChapter() + ".." + range.toChapter())
                            + "）");
                }
            }
        }
        if (contract == null || contract.clues() == null) {
            return;
        }
        Map<String, StoryContract.Clue> clueByKey = new LinkedHashMap<>();
        contract.clues().forEach(clue -> clueByKey.put(clue.clueKey(), clue));
        for (StoryContract.ThreadRef thread : contract.threads()) {
            StoryContract.Clue clue = clueByKey.get(thread.threadKey());
            if (clue == null) {
                continue;
            }
            PlanContracts.VolumeContract opened = volumeOfNo(volumes, thread.openedVolume());
            PlanContracts.VolumeContract payoff = volumeOfNo(volumes, thread.payoffVolume());
            if (opened != null && !inRange(clue.plantedChapter(), opened)) {
                throw new PlanJson.Bad("CONTRACT_SCHEDULE_DRIFT：线索 " + clue.clueKey()
                        + " 埋设章 " + clue.plantedChapter() + " 不在第" + opened.volumeNo()
                        + "卷（" + opened.fromChapter() + ".." + opened.toChapter() + "）");
            }
            if (payoff != null && !inRange(clue.payoffChapter(), payoff)) {
                throw new PlanJson.Bad("CONTRACT_SCHEDULE_DRIFT：线索 " + clue.clueKey()
                        + " 兑现章 " + clue.payoffChapter() + " 不在第" + payoff.volumeNo()
                        + "卷（" + payoff.fromChapter() + ".." + payoff.toChapter() + "）");
            }
        }
    }

    private static PlanContracts.VolumeContract volumeOfNo(
            List<PlanContracts.VolumeContract> volumes, int volumeNo) {
        return volumes.stream().filter(volume -> volume.volumeNo() == volumeNo)
                .findFirst().orElse(null);
    }

    private static boolean inRange(int chapterNo, PlanContracts.VolumeContract volume) {
        return chapterNo >= volume.fromChapter() && chapterNo <= volume.toChapter();
    }

    /** 角色计划收敛到合同 cast：合同外的 key 直接拒绝，职能与姓名按合同槽位回填。 */
    static List<PlanContracts.CharacterContract> reconcileCharacters(
            List<PlanContracts.CharacterContract> characters, StoryContract contract,
            PlanContracts.ScopeContract scope) {
        Map<String, StoryContract.CharacterSlot> slots = new LinkedHashMap<>();
        contract.cast().forEach(slot -> slots.put(slot.key(), slot));
        List<PlanContracts.CharacterContract> out = new ArrayList<>();
        for (PlanContracts.CharacterContract character : characters) {
            StoryContract.CharacterSlot slot = slots.get(character.characterKey());
            if (slot == null) {
                throw new PlanJson.Bad("CONTRACT_CAST_DRIFT：角色 key "
                        + character.characterKey() + " 不在合同 cast " + contract.castKeys());
            }
            PlanContracts.CharacterFunction function = character.function();
            PlanContracts.CharacterFunction contractFunction = functionOf(slot.role());
            String displayName = isBlank(character.displayNameHint())
                    ? slot.name() : character.displayNameHint();
            if (!slot.name().equals(displayName)) {
                log.warn("角色计划姓名与合同不一致，按合同回填：key={} 合同={} 计划={}",
                        slot.key(), slot.name(), displayName);
                displayName = slot.name();
            }
            if (function != contractFunction) {
                log.warn("角色计划职能与合同槽位不一致，按合同回填：key={} 合同={} 计划={}",
                        slot.key(), contractFunction, function);
                function = contractFunction;
            }
            out.add(new PlanContracts.CharacterContract(
                    character.characterKey(), displayName, function,
                    character.uniqueContribution(), character.want(), character.flaw(),
                    character.requiredSetup(), character.conflictTargets(),
                    character.functionalConflict(), character.highlightChoice(),
                    character.highlightCost(), character.relationshipChanges(),
                    character.payoffProof(), character.spotlightVolume(),
                    character.conflictVolume(), character.payoffVolume(),
                    character.arcBefore(), character.arcAfter(),
                    isBlank(slot.identity()) ? character.castRole() : slot.identity()));
        }
        Set<String> seen = new LinkedHashSet<>();
        out.forEach(character -> seen.add(character.characterKey()));
        for (StoryContract.CharacterSlot slot : contract.cast()) {
            if (!seen.contains(slot.key())) {
                throw new PlanJson.Bad("CONTRACT_CAST_DRIFT：合同角色 " + slot.key() + "（"
                        + slot.name() + "）在角色计划中缺失");
            }
        }
        return out;
    }

    /**
     * 旧 run 兼容：规划已过 SCALE 但没有冻结合同、且尚未写正文时，退回 CONTRACT 补冻结合同；
     * 已有正文的 run 拒绝自动迁移（LEGACY_TEXT_REQUIRES_MANUAL），要求新建 run 或人工处理。
     */
    private PlanContracts.PlanV2State backfillLegacyContract(RunDO run,
                                                             PlanContracts.PlanV2State state) {
        if (state == null) {
            return state;
        }
        PlanContracts.PlanUnit unit = state.currentUnit();
        if (unit == PlanContracts.PlanUnit.SCALE
                || unit == PlanContracts.PlanUnit.CONTRACT
                || unit == PlanContracts.PlanUnit.SCHEDULE
                || unit == PlanContracts.PlanUnit.NEED_SCOPE) {
            return state;
        }
        if (contractService.load(run) != null
                || contractService.loadGenreContract(run) != null) {
            return state;
        }
        if (hasWrittenText(run)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "LEGACY_TEXT_REQUIRES_MANUAL：该 run 已有正文却没有冻结合同，请新建 run 或人工处理，禁止自动迁移");
        }
        if (state.scope() == null) {
            return state;
        }
        PlanContracts.PlanV2State backfill = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.CONTRACT, state.completedUnits(), state.scope(),
                state.volumeUnits());
        writePlanState(run, backfill);
        log.info("旧 run 补冻结合同：runId={} 原单元={} → CONTRACT", run.getId(), unit);
        return backfill;
    }

    /** 该 run 是否已有正文（决定能否自动补合同）。 */
    private boolean hasWrittenText(RunDO run) {
        if (chapterMapper == null || run.getNovelId() == null) {
            return false;
        }
        return chapterMapper.selectList(new LambdaQueryWrapper<com.zzdzz.novelgen.model.entity.derive.ChapterDO>()
                        .eq(com.zzdzz.novelgen.model.entity.derive.ChapterDO::getNovelId, run.getNovelId()))
                .stream()
                .anyMatch(chapter -> chapter.getContent() != null && !chapter.getContent().isBlank());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isRomance(RunDO run) {
        return run != null && "ROMANCE".equalsIgnoreCase(run.getGenre());
    }

    private void ensureRomanceVolumePlan(RunDO run, PlanContracts.ScopeContract scope,
                                         RomanceContract romance) {
        int volumeNo = 1;
        VolumePlanDO existing = volumePlanMapper.selectOne(new LambdaQueryWrapper<VolumePlanDO>()
                .eq(VolumePlanDO::getRunId, run.getId())
                .eq(VolumePlanDO::getVolumeNo, volumeNo)
                .last("LIMIT 1"));
        int toChapter = scope == null || scope.chapterCount() < 1
                ? romance.stages().values().stream()
                        .mapToInt(RomanceStage::chapterNo).max().orElse(1)
                : scope.chapterCount();
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                volumeNo, 1, toChapter, "言情关系主线",
                "两人如何从误会走向确认关系",
                "关系会因为冲突彻底结束",
                "经历冲突后仍选择继续靠近",
                "关系阶段逐步推进",
                List.of("C1", "C2"),
                List.of("关系从陌生到确认"),
                List.of(romance.primaryRelationship().threadKey()),
                List.of("不得提前完成告白、和好或关系结局"),
                "每个关系阶段都有可观察事件",
                "", "FINAL");
        if (existing == null) {
            VolumePlanDO row = new VolumePlanDO();
            row.setRunId(run.getId());
            row.setNovelId(run.getNovelId());
            row.setVolumeNo(volumeNo);
            row.setFromChapter(1);
            row.setToChapter(toChapter);
            row.setArc("言情关系主线");
            row.setLocalEvent("两人关系从相遇推进到确认");
            row.setLocalClosure("关系阶段完成一次明确变化");
            row.setNextHook("");
            row.setGlobalRole("FINAL");
            row.setStatus("PLANNING");
            row.setContract(toJson(volume));
            volumePlanMapper.insertFull(row);
        } else {
            existing.setFromChapter(1);
            existing.setToChapter(toChapter);
            existing.setArc("言情关系主线");
            existing.setLocalEvent("两人关系从相遇推进到确认");
            existing.setLocalClosure("关系阶段完成一次明确变化");
            existing.setNextHook("");
            existing.setGlobalRole("FINAL");
            existing.setStatus("PLANNING");
            volumePlanMapper.updateContract(existing.getId(), toJson(volume));
        }
    }

    /**
     * 言情生产链的确定性合同工厂。
     *
     * <p>当前探针阶段不引入第二套悬疑合同模型；先用固定关系骨架保证日程、路由、⑥b 和 QC
     * 能读到合法合同。后续可替换为 LLM 生成，但必须继续输出独立 RomanceContract。
     */
    private static RomanceContract deterministicRomanceContract(
            RunDO run, PlanContracts.ScopeContract scope) {
        String[][] nameSets = {
                {"林夏", "顾言", "苏晴"},
                {"沈宁", "陆川", "乔安"},
                {"许星", "程屿", "周妍"}
        };
        long seed = run.getSeed() == null ? 0L : run.getSeed();
        String[] names = nameSets[(int) Math.floorMod(seed, nameSets.length)];
        List<RomanceCharacterSlot> cast = List.of(
                new RomanceCharacterSlot("C1", names[0], "PROTAGONIST", "女", "故事主角"),
                new RomanceCharacterSlot("C2", names[1], "LOVE_INTEREST", "男", "主角的重要关系对象"),
                new RomanceCharacterSlot("C3", names[2], "RIVAL", "女", "推动关系变化的第三方"));
        String threadKey = "REL-C1-C2";
        RomanceRelationship relationship = new RomanceRelationship(
                threadKey, "C1", "C2", "ROMANTIC", "主角与重要关系对象的关系线");
        int chapterCount = scope == null || scope.chapterCount() < 1
                ? RomanceStageKind.values().length : scope.chapterCount();
        Map<RomanceStageKind, RomanceStage> stages = new LinkedHashMap<>();
        RomanceStageKind[] kinds = RomanceStageKind.values();
        for (int i = 0; i < kinds.length; i++) {
            RomanceStageKind kind = kinds[i];
            int chapterNo = Math.min(chapterCount, i + 1);
            String targetKey = kind == RomanceStageKind.MISUNDERSTANDING
                    || kind == RomanceStageKind.CONFLICT_PEAK ? "C2" : threadKey;
            String proof = switch (kind) {
                case RELATIONSHIP_START -> "两人因事件建立联系";
                case MISUNDERSTANDING -> "误会产生并改变互动";
                case ATTACHMENT_ESCALATION -> "关系投入和依赖加深";
                case CONFLICT_PEAK -> "冲突达到峰值";
                case CONFESSION -> "关键情感选择被说出或行动化";
                case RECONCILIATION -> "冲突后的修复与选择";
                case RELATIONSHIP_CLOSURE -> "关系结果明确收束";
            };
            stages.put(kind, new RomanceStage(kind, chapterNo, targetKey,
                    List.of("C1", "C2"), List.of(threadKey), true, proof, Map.of()));
        }
        return new RomanceContract(1, scope, cast, relationship, stages,
                List.of("不得引入悬疑案件、凶手、动机、手法或线索结构"));
    }

    /** 合同 map 里的章号（缺省/坏值一律 null，让线程行保持可空）。 */
    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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

    /** 在旧角色计划尚未生成时，用稳定槽位为业务模型阶段提供可闭合的角色 key。 */
    private static List<PlanContracts.CharacterContract> temporaryCharacters(RunDO run) {
        List<PlanContracts.CharacterContract> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String key = "C" + i;
            out.add(new PlanContracts.CharacterContract(key, "角色" + i,
                    PlanContracts.CharacterFunction.SUPPORT, "承担共同体选择", "完成公开目标",
                    "无法独自承担代价", List.of(), List.of(), "组织规则与私人需求冲突",
                    "在代价出现时保护重要关系", "承担不可撤销的选择", List.of(),
                    "关系和制度代价在结局闭合", 1, i, 1, "START", "PAYOFF", ""));
        }
        return out;
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
            row.setPlantChapter(intOrNull(thread.contract().get("plantChapter")));
            row.setPayoffChapter(intOrNull(thread.contract().get("payoffChapter")));
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
                    required(node, "arcAfter"),
                    blankToNull(node.path("castRole").asText(""))));
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
        List<PlanContracts.CharacterContract> enriched = withCastRoles(run, characters);
        for (PlanContracts.CharacterContract contract : enriched) {
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

    private List<PlanContracts.CharacterContract> withCastRoles(
            RunDO run, List<PlanContracts.CharacterContract> characters) {
        if (run.getNovelId() == null) {
            return characters;
        }
        NovelDO novel = novelMapper.selectById(run.getNovelId());
        if (novel == null) {
            return characters;
        }
        JsonNode cast = readTreeSafe(novel.getStoryAssets()).path("cast");
        if (!cast.isArray() || cast.isEmpty()) {
            return characters;
        }
        return applyCastRoles(cast, characters);
    }

    /** 按人物表回填 CharacterContract.castRole（key→name→顺序三级兜底）。 */
    static List<PlanContracts.CharacterContract> applyCastRoles(
            JsonNode cast, List<PlanContracts.CharacterContract> characters) {
        if (cast == null || !cast.isArray() || cast.isEmpty()) {
            return characters;
        }
        Map<String, String> roleByKey = new LinkedHashMap<>();
        Map<String, String> roleByName = new LinkedHashMap<>();
        List<JsonNode> members = new ArrayList<>();
        cast.forEach(member -> {
            members.add(member);
            String role = member.path("role").asText("").trim();
            String key = member.path("characterKey").asText("").trim();
            String name = member.path("name").asText("").trim();
            if (!key.isBlank()) {
                roleByKey.put(key, role);
            }
            if (!name.isBlank()) {
                roleByName.put(name, role);
            }
        });
        List<PlanContracts.CharacterContract> out = new ArrayList<>();
        for (int i = 0; i < characters.size(); i++) {
            PlanContracts.CharacterContract contract = characters.get(i);
            String role = contract.castRole();
            if (role == null || role.isBlank()) {
                role = roleByKey.get(contract.characterKey());
            }
            if (role == null || role.isBlank()) {
                role = roleByName.get(contract.displayNameHint());
            }
            if ((role == null || role.isBlank()) && i < members.size()) {
                role = members.get(i).path("role").asText("").trim();
            }
            if (role == null) {
                role = "";
            }
            out.add(new PlanContracts.CharacterContract(
                    contract.characterKey(), contract.displayNameHint(), contract.function(),
                    contract.uniqueContribution(), contract.want(), contract.flaw(),
                    contract.requiredSetup(), contract.conflictTargets(),
                    contract.functionalConflict(), contract.highlightChoice(),
                    contract.highlightCost(), contract.relationshipChanges(),
                    contract.payoffProof(), contract.spotlightVolume(),
                    contract.conflictVolume(), contract.payoffVolume(),
                    contract.arcBefore(), contract.arcAfter(), role));
        }
        return out;
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
        // direct 初稿直通允许模型先给规模，字数带校验降级为报告；严格链路仍按 lenCheck 硬判。
        if (Boolean.TRUE.equals(run.getLenCheck())
                && !Boolean.TRUE.equals(DerivePlanService.DIRECT_PASS.get())) {
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

    private void putMainline(ObjectNode user, RunDO run) {
        JsonNode contract = canonicalContract(run);
        if (contract.isObject() && !contract.path("finalTruth").asText("").isBlank()) {
            user.put("主线", contract.path("finalTruth").asText(""));
        } else {
            user.put("主线", nullToEmpty(run.getWelded()));
        }
    }

    private void putTruthContract(ObjectNode user, RunDO run) {
        JsonNode contract = canonicalContract(run);
        if (contract.isObject()) {
            user.set("TruthContract（唯一真相合同，冲突时以此为准）", contract);
        }
    }

    /** 优先读已冻结合同表；没有冻结真相时才退回 step_status 的旧合同。 */
    private JsonNode canonicalContract(RunDO run) {
        if (contractService != null) {
            JsonNode frozen = contractService.frozenContractNode(run);
            if (frozen != null && frozen.isObject()
                    && !frozen.path("finalTruth").asText("").isBlank()) {
                return frozen;
            }
        }
        return readSteps(run.getStepStatus()).path("TRUTH_CONTRACT");
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
