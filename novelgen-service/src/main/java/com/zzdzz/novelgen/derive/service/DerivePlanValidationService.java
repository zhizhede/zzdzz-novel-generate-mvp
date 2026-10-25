package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 写作前故事规划校验：结构、状态、知识、角色弧与线程闭环。 */
@Service
@RequiredArgsConstructor
public class DerivePlanValidationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunMapper runMapper;
    private final VolumePlanMapper volumePlanMapper;
    private final CharacterPlanMapper characterPlanMapper;
    private final StoryThreadMapper storyThreadMapper;
    private final BeatInstanceMapper beatInstanceMapper;

    /** 校验单卷拍路由。 */
    public PlanValidationVO validateBeatRoute(long runId, int volumeNo) {
        return buildResult(runId, volumeNo, validateBookInternal(runId, volumeNo));
    }

    /** 校验单卷结构、状态和角色弧。 */
    public PlanValidationVO validateVolume(long runId, int volumeNo) {
        return buildResult(runId, volumeNo, validateBookInternal(runId, volumeNo));
    }

    /** 校验全书闭环。 */
    public PlanValidationVO validateBook(long runId) {
        return buildResult(runId, null, validateBookInternal(runId, null));
    }

    /** 纯状态模拟：死亡、知识和出场方式。 */
    static List<PlanContracts.PlanIssue> validateAppearances(
            List<AppearanceStep> steps,
            Map<String, CharacterState> initial) {
        Map<String, CharacterState> states = new LinkedHashMap<>();
        initial.forEach((key, value) -> states.put(key,
                new CharacterState(value.alive(), new LinkedHashSet<>(value.knowledge()))));
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        List<AppearanceStep> ordered = new ArrayList<>(steps);
        ordered.sort(Comparator.comparingInt(AppearanceStep::chapterNo)
                .thenComparing(step -> step.seq() == null ? Integer.MAX_VALUE : step.seq()));
        Map<String, List<AppearanceStep>> byBeat = new LinkedHashMap<>();
        for (AppearanceStep step : ordered) {
            String key = step.chapterNo() + ":" + (step.seq() == null ? "-" : step.seq());
            byBeat.computeIfAbsent(key, ignored -> new ArrayList<>()).add(step);
        }
        for (List<AppearanceStep> beat : byBeat.values()) {
            Set<String> required = new LinkedHashSet<>();
            for (AppearanceStep step : beat) {
                required.addAll(step.requiresKnowledge());
            }
            required.removeIf(DerivePlanValidationService::isThreadKey);
            for (String knowledge : required) {
                boolean knownByFocus = beat.stream()
                        .map(step -> states.computeIfAbsent(step.characterKey(),
                                key -> new CharacterState(true, new LinkedHashSet<>())))
                        .anyMatch(state -> state.knowledge().stream()
                                .anyMatch(known -> knowledgeMatches(known, knowledge)));
                if (!knownByFocus) {
                    AppearanceStep first = beat.get(0);
                    issues.add(error("KNOWLEDGE_NOT_ACQUIRED",
                            PlanContracts.RepairScope.PLAN_VOLUME,
                            first.volumeNo(), first.chapterNo(),
                            "焦点角色均尚未获得知识：" + knowledge));
                    continue;
                }
                for (AppearanceStep step : beat) {
                    states.computeIfAbsent(step.characterKey(),
                            key -> new CharacterState(true, new LinkedHashSet<>()))
                            .knowledge().add(knowledge);
                }
            }
            for (AppearanceStep step : beat) {
                CharacterState state = states.computeIfAbsent(step.characterKey(),
                        key -> new CharacterState(true, new LinkedHashSet<>()));
                if (step.mode() == PlanContracts.AppearanceMode.LIVE && !state.alive()) {
                    issues.add(error("CHARACTER_NOT_ALIVE",
                            PlanContracts.RepairScope.PLAN_VOLUME,
                            step.volumeNo(), step.chapterNo(),
                            step.characterKey() + " 已死亡，不能以 LIVE 形式出场"));
                }
                state.knowledge().addAll(step.writesKnowledge());
                if (step.endsDead()) {
                    states.put(step.characterKey(), new CharacterState(false, state.knowledge()));
                }
            }
        }
        return issues;
    }

    /** 卷闭合和钩子结构校验。 */
    static List<PlanContracts.PlanIssue> validateVolumeHooks(
            List<PlanContracts.VolumeContract> volumes) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        for (int i = 0; i < volumes.size(); i++) {
            PlanContracts.VolumeContract volume = volumes.get(i);
            boolean last = i == volumes.size() - 1;
            if (!last && (volume.nextHook() == null || volume.nextHook().isBlank())) {
                issues.add(error("NEXT_HOOK_MISSING", PlanContracts.RepairScope.PLAN_VOLUME,
                        volume.volumeNo(), "第" + volume.volumeNo() + "卷缺下一卷钩子"));
            }
            if (last && !"FINAL".equals(volume.globalRole())) {
                issues.add(error("FINAL_ROLE_MISSING", PlanContracts.RepairScope.PLAN_VOLUME,
                        volume.volumeNo(), "最终卷 globalRole 必须为 FINAL"));
            }
            if (volume.localClosureProof() == null || volume.localClosureProof().isBlank()) {
                issues.add(error("LOCAL_CLOSURE_MISSING", PlanContracts.RepairScope.PLAN_VOLUME,
                        volume.volumeNo(), "第" + volume.volumeNo() + "卷缺局部闭合证明"));
            }
        }
        return issues;
    }

    /** 最终卷必须兑现全部必需线程。 */
    static List<PlanContracts.PlanIssue> validateThreadClosure(
            List<PlanContracts.ThreadContract> threads, Set<String> paidThreads) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        for (PlanContracts.ThreadContract thread : threads) {
            if (thread.status() != PlanContracts.ThreadStatus.CLOSED
                    && thread.status() != PlanContracts.ThreadStatus.PAYOFF
                    && !paidThreads.contains(thread.threadKey())) {
                issues.add(error("REQUIRED_THREAD_OPEN", PlanContracts.RepairScope.PLAN_VOLUME,
                        thread.payoffVolume(), "线程未兑现：" + thread.threadKey()));
            }
        }
        return issues;
    }

    private List<PlanContracts.PlanIssue> validateBookInternal(long runId, Integer onlyVolume) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        List<PlanContracts.VolumeContract> volumes = loadVolumes(runId, null);
        List<CharacterPlanDO> characters = characterPlanMapper.selectList(
                new LambdaQueryWrapper<CharacterPlanDO>()
                        .eq(CharacterPlanDO::getRunId, runId));
        List<PlanContracts.ThreadContract> threads = loadThreads(runId);
        List<PlanContracts.CharacterContract> characterContracts = loadCharacterContracts(characters);
        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>()
                        .eq(BeatInstanceDO::getRunId, runId)
                        .orderByAsc(BeatInstanceDO::getSeq));
        if (onlyVolume != null) {
            beats = beats.stream().filter(b -> b.getVolumeNo() != null
                    && b.getVolumeNo() == onlyVolume).toList();
        }

        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        issues.addAll(validateVolumeHooks(volumes));
        issues.addAll(validateContractClosure(volumes, threads, characterContracts));
        List<AppearanceStep> steps = new ArrayList<>();
        Set<String> paidThreads = new LinkedHashSet<>();
        Map<String, Set<PlanContracts.BeatRole>> rolesByCharacterVolume = new HashMap<>();
        for (BeatInstanceDO beat : beats) {
            if ("SKIPPED".equals(beat.getStatus())) {
                continue;
            }
            JsonNode route = readTreeSafe(beat.getRouteContract());
            List<String> focus = strings(route.path("focusCharacters"));
            PlanContracts.AppearanceMode mode = enumOr(
                    PlanContracts.AppearanceMode.class,
                    route.path("appearanceMode").asText("LIVE"),
                    PlanContracts.AppearanceMode.LIVE);
            boolean dead = endsDead(route.path("changesState"));
            for (String key : focus) {
                steps.add(new AppearanceStep(
                        beat.getChapterNo() == null ? 0 : beat.getChapterNo(),
                        beat.getVolumeNo(),
                        key, mode, strings(route.path("requiresKnowledge")),
                        strings(route.path("writesKnowledge")), dead, beat.getSeq()));
                if (beat.getBeatRole() != null) {
                    try {
                        PlanContracts.BeatRole role = PlanContracts.BeatRole.valueOf(beat.getBeatRole());
                        rolesByCharacterVolume
                                .computeIfAbsent(key + ":" + beat.getVolumeNo(),
                                        k -> new LinkedHashSet<>())
                                .add(role);
                    } catch (IllegalArgumentException ignored) {
                        // 非法角色枚举由路由阶段负责报错。
                    }
                }
            }
            if (isPayoffRole(beat.getBeatRole())) {
                paidThreads.addAll(strings(route.path("threadRefs")));
            }
        }
        Map<String, CharacterState> initial = new HashMap<>();
        characters.forEach(c -> initial.put(c.getCharacterKey(),
                new CharacterState(true, new LinkedHashSet<>())));
        issues.addAll(validateAppearances(steps, initial));
        issues.addAll(validateCharacterArcs(characters, rolesByCharacterVolume));

        boolean containsFinal = volumes.stream()
                .anyMatch(v -> "FINAL".equals(v.globalRole()));
        if (onlyVolume == null && containsFinal) {
            issues.addAll(validateThreadClosure(threads, paidThreads));
        }
        return issues;
    }

    static List<PlanContracts.PlanIssue> validateCharacterArcs(
            List<CharacterPlanDO> characters,
            Map<String, Set<PlanContracts.BeatRole>> roles) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        Map<Integer, List<CharacterPlanDO>> byVolume = new LinkedHashMap<>();
        for (CharacterPlanDO character : characters) {
            if (character.getSpotlightVolume() != null) {
                byVolume.computeIfAbsent(character.getSpotlightVolume(),
                        key -> new ArrayList<>()).add(character);
            }
        }
        for (Map.Entry<Integer, List<CharacterPlanDO>> entry : byVolume.entrySet()) {
            int volumeNo = entry.getKey();
            boolean completed = entry.getValue().stream().anyMatch(character -> {
                Set<PlanContracts.BeatRole> actual = roles.getOrDefault(
                        character.getCharacterKey() + ":" + volumeNo, Set.of());
                return actual.contains(PlanContracts.BeatRole.HIGHLIGHT)
                        && actual.contains(PlanContracts.BeatRole.CHARACTER_CHOICE);
            });
            if (!completed) {
                issues.add(error("HIGHLIGHT_CHAIN_MISSING",
                        PlanContracts.RepairScope.PLAN_VOLUME, volumeNo,
                        "第" + volumeNo + "卷缺任一角色的 HIGHLIGHT/CHARACTER_CHOICE"));
            }
            boolean hasCost = entry.getValue().stream().anyMatch(character ->
                    roles.getOrDefault(character.getCharacterKey() + ":" + volumeNo, Set.of())
                            .contains(PlanContracts.BeatRole.COST));
            if (!hasCost) {
                issues.add(warning("HIGHLIGHT_COST_MISSING",
                        PlanContracts.RepairScope.PLAN_VOLUME, volumeNo,
                        "第" + volumeNo + "卷高光未显式分配 COST 拍"));
            }
        }
        return issues;
    }

    /** 角色/线程/卷合同必须闭合，缺 owner 或缺线程直接退回 SPINE，不允许带病进入拍路由。 */
    static List<PlanContracts.PlanIssue> validateContractClosure(
            List<PlanContracts.VolumeContract> volumes,
            List<PlanContracts.ThreadContract> threads,
            List<PlanContracts.CharacterContract> characters) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        Set<String> characterKeys = new LinkedHashSet<>();
        characters.forEach(character -> characterKeys.add(character.characterKey()));
        Set<String> threadKeys = new LinkedHashSet<>();
        threads.forEach(thread -> threadKeys.add(thread.threadKey()));
        for (PlanContracts.ThreadContract thread : threads) {
            String owner = thread.ownerCharacterKey();
            if (owner != null && !owner.isBlank() && !characterKeys.contains(owner)) {
                issues.add(error("THREAD_OWNER_MISSING", PlanContracts.RepairScope.SPINE,
                        thread.payoffVolume(), "线程 " + thread.threadKey()
                                + " 的 ownerCharacterKey=" + owner + " 不在角色叙事计划中"));
            }
        }
        for (PlanContracts.VolumeContract volume : volumes) {
            for (String key : volume.focusCharacters()) {
                if (!characterKeys.contains(key)) {
                    issues.add(error("VOLUME_CHARACTER_MISSING", PlanContracts.RepairScope.SPINE,
                            volume.volumeNo(), "第" + volume.volumeNo() + "卷 focusCharacters 含未知角色 " + key));
                }
            }
            for (String key : volume.requiredThreads()) {
                if (!threadKeys.contains(key)) {
                    issues.add(error("VOLUME_THREAD_MISSING", PlanContracts.RepairScope.SPINE,
                            volume.volumeNo(), "第" + volume.volumeNo() + "卷 requiredThreads 含未知线程 " + key));
                }
            }
        }
        for (PlanContracts.CharacterContract character : characters) {
            for (String target : character.conflictTargets()) {
                if (!characterKeys.contains(target)) {
                    issues.add(error("CHARACTER_TARGET_MISSING", PlanContracts.RepairScope.SPINE,
                            character.payoffVolume(), character.characterKey()
                                    + " 的 conflictTargets 含未知角色 " + target));
                }
            }
            for (String relation : character.relationshipChanges()) {
                if (relation == null || !relation.startsWith("REL-")) {
                    continue;
                }
                String[] parts = relation.split("-");
                if (parts.length != 3 || !characterKeys.contains(parts[1])
                        || !characterKeys.contains(parts[2])) {
                    issues.add(error("RELATION_REFERENCE_MISSING", PlanContracts.RepairScope.SPINE,
                            character.payoffVolume(), character.characterKey()
                                    + " 的关系线 " + relation + " 引用了未知角色"));
                }
            }
        }
        return issues;
    }

    private List<PlanContracts.CharacterContract> loadCharacterContracts(
            List<CharacterPlanDO> rows) {
        List<PlanContracts.CharacterContract> out = new ArrayList<>();
        for (CharacterPlanDO row : rows) {
            try {
                out.add(MAPPER.readValue(row.getContract(), PlanContracts.CharacterContract.class));
            } catch (Exception e) {
                out.add(new PlanContracts.CharacterContract(
                        row.getCharacterKey(), row.getDisplayNameHint(), PlanContracts.CharacterFunction.SUPPORT,
                        "", "", "", List.of(), List.of(), "", "", "", List.of(), "",
                        row.getSpotlightVolume(), row.getConflictVolume(), row.getPayoffVolume(),
                        "", ""));
            }
        }
        return out;
    }

    private List<PlanContracts.VolumeContract> loadVolumes(long runId, Integer onlyVolume) {
        List<VolumePlanDO> rows = volumePlanMapper.selectList(new LambdaQueryWrapper<VolumePlanDO>()
                .eq(VolumePlanDO::getRunId, runId)
                .orderByAsc(VolumePlanDO::getVolumeNo));
        List<PlanContracts.VolumeContract> out = new ArrayList<>();
        for (VolumePlanDO row : rows) {
            if (onlyVolume != null && !onlyVolume.equals(row.getVolumeNo())) {
                continue;
            }
            try {
                out.add(MAPPER.readValue(row.getContract(), PlanContracts.VolumeContract.class));
            } catch (Exception e) {
                out.add(new PlanContracts.VolumeContract(
                        row.getVolumeNo(), row.getFromChapter(), row.getToChapter(),
                        row.getArc(), "", "", "", "", List.of(), List.of(), List.of(),
                        List.of(), "", row.getNextHook(), row.getGlobalRole()));
            }
        }
        return out;
    }

    private List<PlanContracts.ThreadContract> loadThreads(long runId) {
        List<PlanContracts.ThreadContract> out = new ArrayList<>();
        for (StoryThreadDO row : storyThreadMapper.selectList(
                new LambdaQueryWrapper<StoryThreadDO>()
                        .eq(StoryThreadDO::getRunId, runId)
                        .orderByAsc(StoryThreadDO::getThreadKey))) {
            try {
                out.add(new PlanContracts.ThreadContract(
                        row.getThreadKey(), PlanContracts.ThreadKind.valueOf(row.getKind()),
                        row.getTitle(), row.getOpenedVolume(), row.getPayoffVolume(),
                        row.getOwnerCharacterKey(), PlanContracts.ThreadStatus.valueOf(row.getStatus()),
                        MAPPER.convertValue(MAPPER.readTree(row.getContract()),
                                MAPPER.getTypeFactory().constructMapType(
                                        Map.class, String.class, Object.class))));
            } catch (Exception e) {
                out.add(new PlanContracts.ThreadContract(
                        row.getThreadKey(), PlanContracts.ThreadKind.CLUE,
                        row.getTitle(), row.getOpenedVolume(), row.getPayoffVolume(),
                        row.getOwnerCharacterKey(), PlanContracts.ThreadStatus.OPEN, Map.of()));
            }
        }
        return out;
    }

    private static PlanValidationVO buildResult(
            long runId, Integer volumeNo, List<PlanContracts.PlanIssue> all) {
        List<PlanValidationVO.Issue> errors = all.stream()
                .filter(i -> !"WARN".equals(i.level()))
                .map(DerivePlanValidationService::toVO)
                .toList();
        List<PlanValidationVO.Issue> warnings = all.stream()
                .filter(i -> "WARN".equals(i.level()))
                .map(DerivePlanValidationService::toVO)
                .toList();
        return new PlanValidationVO(runId, volumeNo, errors.isEmpty(), errors, warnings);
    }

    private static PlanValidationVO.Issue toVO(PlanContracts.PlanIssue issue) {
        String repairTarget = issue.repairScope() == PlanContracts.RepairScope.PLAN_VOLUME
                ? "PLAN_VOLUME:" + (issue.volumeNo() == null ? "" : issue.volumeNo())
                : issue.repairScope().name();
        return new PlanValidationVO.Issue(issue.level(), issue.code(), repairTarget,
                issue.volumeNo(), issue.chapterNo(), issue.seqs(), issue.message());
    }

    private static boolean isPayoffRole(String role) {
        return "CLUE_PAYOFF".equals(role) || "LOCAL_CLOSURE".equals(role) || "RELATION_CONFLICT".equals(role)
                || "FINAL_REVEAL".equals(role) || "FINAL_CLOSURE".equals(role);
    }

    private static boolean knowledgeMatches(String known, String required) {
        if (known == null || required == null) {
            return false;
        }
        if (known.equals(required)) {
            return true;
        }
        String left = DerivePlanService.factCore(known);
        String right = DerivePlanService.factCore(required);
        return !right.isEmpty() && !left.isEmpty() && (left.contains(right) || right.contains(left));
    }
    private static boolean endsDead(JsonNode changes) {
        if (!changes.isObject()) {
            return false;
        }
        var fields = changes.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            if (value.isTextual() && "DEAD".equalsIgnoreCase(value.asText())) {
                return true;
            }
            if (value.isObject() && value.path("alive").isBoolean()
                    && !value.path("alive").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String raw, E fallback) {
        try {
            return Enum.valueOf(type, raw);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isThreadKey(String value) {
        return value != null && (value.startsWith("GM-") || value.startsWith("VC-")
                || value.startsWith("REL-") || value.startsWith("OBJ-"));
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> out.add(n.asText("")));
        }
        return out;
    }

    private static JsonNode readTreeSafe(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static PlanContracts.PlanIssue error(
            String code, PlanContracts.RepairScope scope, Integer volumeNo, String message) {
        return error(code, scope, volumeNo, null, message);
    }

    private static PlanContracts.PlanIssue error(
            String code, PlanContracts.RepairScope scope, Integer volumeNo,
            Integer chapterNo, String message) {
        return new PlanContracts.PlanIssue(
                "ERROR", code, scope, volumeNo, chapterNo, List.of(), message);
    }

    private static PlanContracts.PlanIssue warning(
            String code, PlanContracts.RepairScope scope, Integer volumeNo, String message) {
        return new PlanContracts.PlanIssue(
                "WARN", code, scope, volumeNo, null, List.of(), message);
    }

    /** 单角色运行状态。 */
    record CharacterState(boolean alive, Set<String> knowledge) {
    }

    /** 一次出场检查。 */
    record AppearanceStep(
            int chapterNo,
            Integer volumeNo,
            String characterKey,
            PlanContracts.AppearanceMode mode,
            List<String> requiresKnowledge,
            List<String> writesKnowledge,
            boolean endsDead,
            Integer seq) {
        AppearanceStep(int chapterNo, String characterKey,
                       PlanContracts.AppearanceMode mode,
                       List<String> requiresKnowledge,
                       List<String> writesKnowledge,
                       boolean endsDead,
                       Integer seq) {
            this(chapterNo, null, characterKey, mode, requiresKnowledge,
                    writesKnowledge, endsDead, seq);
        }
    }
}
