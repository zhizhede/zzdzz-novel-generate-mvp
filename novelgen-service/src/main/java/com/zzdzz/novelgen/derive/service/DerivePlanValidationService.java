package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveBeatPoolMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
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
import java.util.regex.Pattern;

/** 写作前故事规划校验：结构、状态、知识、角色弧与线程闭环。 */
@Service
@RequiredArgsConstructor
public class DerivePlanValidationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeriveContractService contractService;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final VolumePlanMapper volumePlanMapper;
    private final CharacterPlanMapper characterPlanMapper;
    private final ChapterMapper chapterMapper;
    private final StoryThreadMapper storyThreadMapper;
    private final BeatInstanceMapper beatInstanceMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeriveBeatPoolMapper beatPoolMapper;

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
        if (onlyVolume == null && run.getNovelId() != null) {
            NovelDO novel = novelMapper.selectById(run.getNovelId());
            JsonNode cast = novel == null ? null
                    : readTreeSafe(novel.getStoryAssets()).path("cast");
            issues.addAll(validatePersonaAlignment(characters, cast));
        if (onlyVolume == null && run.getNovelId() != null) {
            issues.addAll(validateTruthMetadata(novel,
                    chapterMapper == null ? List.of() : chapterMapper.selectList(
                            new LambdaQueryWrapper<com.zzdzz.novelgen.model.entity.derive.ChapterDO>()
                                    .eq(com.zzdzz.novelgen.model.entity.derive.ChapterDO::getNovelId, run.getNovelId())
                                    .orderByAsc(com.zzdzz.novelgen.model.entity.derive.ChapterDO::getChapterNo))));
        }
        }
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
        if (onlyVolume == null && contractService != null) {
            com.zzdzz.novelgen.derive.contract.StoryContract contract = contractService.load(run);
            if (contract != null) {
                Set<String> referenced = new LinkedHashSet<>();
                for (BeatInstanceDO beat : beats) {
                    referenced.addAll(strings(readTreeSafe(beat.getRouteContract()).path("threadRefs")));
                }
                for (com.zzdzz.novelgen.derive.contract.StoryContract.Clue clue : contract.clues()) {
                    if (!referenced.contains(clue.clueKey())) {
                        issues.add(warning("CLUE_PLANT_MISMATCH",
                                PlanContracts.RepairScope.PLAN_VOLUME, clue.plantedChapter(),
                                "线索 " + clue.clueKey() + " 未被任何拍的 threadRefs 引用（结构缺失，非措辞问题）"));
                    }
                }
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
        issues.addAll(beatPoolChapterIssues(runId, beats));
        return issues;
    }

    /**
     * I2 池拍一致：按 beat_key 对齐 derive_beat_pool.contract.chapterNo 与 beat_instance.chapter_no。
     *
     * <p>池缺失或 beat_key 为空的历史行不参与比较，避免把旧运行误报成章号漂移。
     */
    List<PlanContracts.PlanIssue> beatPoolChapterIssues(long runId,
                                                        List<BeatInstanceDO> beats) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (beatPoolMapper == null) {
            return issues;
        }
        List<com.zzdzz.novelgen.model.entity.derive.DeriveBeatPoolDO> poolRows =
                beatPoolMapper.selectList(new LambdaQueryWrapper<
                        com.zzdzz.novelgen.model.entity.derive.DeriveBeatPoolDO>()
                        .eq(com.zzdzz.novelgen.model.entity.derive.DeriveBeatPoolDO::getRunId, runId));
        Map<String, Integer> poolChapterByBeatKey = new HashMap<>();
        for (com.zzdzz.novelgen.model.entity.derive.DeriveBeatPoolDO row : poolRows) {
            if (row.getBeatKey() == null || row.getBeatKey().isBlank()) {
                continue;
            }
            JsonNode contract = readTreeSafe(row.getContract());
            if (contract.path("chapterNo").isNumber()) {
                poolChapterByBeatKey.put(row.getBeatKey(), contract.path("chapterNo").asInt());
            }
        }
        for (BeatInstanceDO beat : beats) {
            if (beat.getBeatKey() == null || beat.getBeatKey().isBlank()
                    || beat.getChapterNo() == null) {
                continue;
            }
            Integer poolChapter = poolChapterByBeatKey.get(beat.getBeatKey());
            if (poolChapter == null || poolChapter.intValue() == beat.getChapterNo().intValue()) {
                continue;
            }
            Integer volumeNo = beat.getVolumeNo() == null ? 1 : beat.getVolumeNo();
            issues.add(new PlanContracts.PlanIssue("ERROR", "BEAT_POOL_CHAPTER_MISMATCH",
                    PlanContracts.RepairScope.PLAN_VOLUME, volumeNo, beat.getChapterNo(),
                    List.of(beat.getSeq()),
                    "拍 seq=" + beat.getSeq() + "(" + beat.getBeatKey() + ") 实际第 "
                            + beat.getChapterNo() + " 章，路由合同第 " + poolChapter + " 章"));
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

    /**
     * persona/人物表与角色叙事计划对齐（P6 Task4）：
     * C1 必须是 DETECTIVE 主视角；DETECTIVE/OPPOSITION 不得同名；
     * persona 绝对禁令不得与 arc/职能文本重合；castRole 与 function 互斥时报错。
     * cast 缺失（⑥a 首跑）时只做行级检查，persona 类检查跳过。
     */
    static List<PlanContracts.PlanIssue> validatePersonaAlignment(
            List<CharacterPlanDO> rows, JsonNode cast) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return issues;
        }
        Map<String, JsonNode> castByKey = new LinkedHashMap<>();
        Map<String, JsonNode> castByName = new LinkedHashMap<>();
        boolean hasCast = cast != null && cast.isArray() && !cast.isEmpty();
        if (hasCast) {
            cast.forEach(member -> {
                String key = member.path("characterKey").asText("").trim();
                String name = member.path("name").asText("").trim();
                if (!key.isBlank()) {
                    castByKey.put(key, member);
                }
                if (!name.isBlank()) {
                    castByName.put(name, member);
                }
            });
        }
        Set<String> detectiveNames = new LinkedHashSet<>();
        Set<String> oppositionNames = new LinkedHashSet<>();
        for (CharacterPlanDO row : rows) {
            PlanContracts.CharacterFunction function = enumOr(
                    PlanContracts.CharacterFunction.class, row.getFunctionCode(),
                    PlanContracts.CharacterFunction.SUPPORT);
            String planText = personaPlanText(row);
            JsonNode member = castByKey.get(row.getCharacterKey());
            if (member == null && row.getDisplayNameHint() != null) {
                member = castByName.get(row.getDisplayNameHint().trim());
            }
            if (member != null) {
                String conduct = member.path("persona").path("conduct").asText("");
                String conflict = prohibitedActionConflict(conduct, planText);
                if (conflict != null) {
                    issues.add(error("PERSONA_ROLE_MISMATCH",
                            PlanContracts.RepairScope.DRAFT, null,
                            "角色 " + row.getCharacterKey() + " 的 persona 禁止「" + conflict
                                    + "」，但角色计划文本安排了该动作"));
                }
                String castRole = member.path("role").asText("");
                if (function == PlanContracts.CharacterFunction.DETECTIVE
                        && containsAny(castRole, "凶手", "作案", "主谋", "罪犯", "加害")) {
                    issues.add(error("PERSONA_ROLE_MISMATCH",
                            PlanContracts.RepairScope.DRAFT, null,
                            "角色 " + row.getCharacterKey() + " 的人物表 role 指向加害者，"
                                    + "但 function=DETECTIVE（主视角）"));
                }
            }
            boolean hasDetective = rows.stream().anyMatch(other ->
                    PlanContracts.CharacterFunction.DETECTIVE == enumOr(
                            PlanContracts.CharacterFunction.class, other.getFunctionCode(),
                            PlanContracts.CharacterFunction.SUPPORT));
            if ("C1".equals(row.getCharacterKey())
                    && function != PlanContracts.CharacterFunction.DETECTIVE && !hasDetective) {
                issues.add(error("PERSONA_ROLE_MISMATCH",
                        PlanContracts.RepairScope.DRAFT, null,
                        "C1 必须是 DETECTIVE 主视角，当前 function=" + function));
            }
            String name = member != null ? member.path("name").asText("").trim()
                    : row.getDisplayNameHint() == null ? "" : row.getDisplayNameHint().trim();
            if (!name.isBlank()) {
                if (function == PlanContracts.CharacterFunction.DETECTIVE) {
                    detectiveNames.add(name);
                } else if (function == PlanContracts.CharacterFunction.OPPOSITION) {
                    oppositionNames.add(name);
                }
            }
        }
        for (String name : detectiveNames) {
            if (oppositionNames.contains(name)) {
                issues.add(error("CHARACTER_FUNCTION_NAME_COLLISION",
                        PlanContracts.RepairScope.DRAFT, null,
                        "DETECTIVE 与 OPPOSITION 共用姓名「" + name + "」"));
            }
        }
        return issues;
    }

    /** 真相卡元数据与章纲/拍合同对账：先在 P6 前拦截，避免语义轮空转。 */
    static List<PlanContracts.PlanIssue> validateTruthMetadata(
            NovelDO novel, List<com.zzdzz.novelgen.model.entity.derive.ChapterDO> chapters) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (novel == null || chapters == null || chapters.isEmpty()) {
            return issues;
        }
        JsonNode truth = readTreeSafe(novel.getStoryAssets()).path("truth");
        int declaredReveal = truth.path("reveal_chapter").asInt(-1);
        int actualReveal = chapters.stream()
                .filter(c -> c.getDraftOutline() != null
                        && (c.getDraftOutline().contains("坦承") || c.getDraftOutline().contains("揭穿")
                        || c.getDraftOutline().contains("真相")))
                .map(com.zzdzz.novelgen.model.entity.derive.ChapterDO::getChapterNo)
                .min(Integer::compareTo).orElse(-1);
        if (declaredReveal > 0 && actualReveal > 0 && declaredReveal != actualReveal) {
            issues.add(warning("REVEAL_CHAPTER_MISMATCH", PlanContracts.RepairScope.PLAN_VOLUME, 1,
                    "真相卡 reveal_chapter=" + declaredReveal + "，但最早出现坦承/揭穿/真相的章为 " + actualReveal));
        }
        JsonNode clues = truth.path("clues");
        if (clues.isArray()) {
            for (JsonNode clue : clues) {
                int planted = clue.path("planted_chapter").asInt(-1);
                String text = clue.path("clue").asText("").trim();
                var target = chapters.stream()
                        .filter(c -> c.getChapterNo() == planted).findFirst().orElse(null);
                if (planted > 0 && (target == null || target.getDraftOutline() == null
                        || text.length() < 6 || !target.getDraftOutline().contains(text.substring(0, 6)))) {
                    // 真相卡与章纲由两个独立 LLM 节点生成，线索措辞不可能天然逐字一致。
                    // 只要线索 key 与章号对得上，措辞不同一律 WARN，绝不当 P6 硬错误。
                    issues.add(warning("CLUE_PROSE_DIFF", PlanContracts.RepairScope.PLAN_VOLUME, planted,
                            "clue planted_chapter=" + planted + " 的措辞与章纲不同（语义已兑现即可）"));
                }
            }
        }
        return issues;
    }
    /** 角色计划中与 persona 判定相关的文本。 */
    private static String personaPlanText(CharacterPlanDO row) {
        JsonNode contract = readTreeSafe(row.getContract());
        return nullToEmpty(row.getArcBefore()) + "\n" + nullToEmpty(row.getArcAfter()) + "\n"
                + nullToEmpty(contract.path("functionalConflict").asText("")) + "\n"
                + nullToEmpty(contract.path("highlightChoice").asText(""));
    }

    /** 动作前缀以否定/禁令词收尾的合规表述（中文里否定词紧贴动作前）。 */
    private static final String[] NEGATION_PHRASES = {
            "不再会", "避免", "不再", "没有", "拒绝", "杜绝", "克制",
            "戒除", "戒掉", "放下", "放弃", "难以", "无意", "抑制", "压制"
    };

    /**
     * 从 persona conduct 提取绝对禁令动作，若角色计划文本“裸安排”了该动作则判冲突。
     * 例：conduct=“禁止私闯现场搜查”，计划含“私闯现场搜查” → 冲突。
     *
     * <p>run45 实弹（第三次尝试连续 4 轮 PERSONA_ROLE_MISMATCH 熔断）：纯子串包含会把
     * 「复述禁令本身」（arc_before=“冷静克制、绝不提前下结论”）与「否定式使用」
     * （functionalConflict=“必须在不提前下结论的前提下追问”）误判成安排了该动作——
     * 这是合规表述，模型改不动，回炉永不收敛。故逐个出现位置检查紧邻前缀，
     * 只有不带否定/禁令词的裸出现才判冲突。
     */
    static String prohibitedActionConflict(String conduct, String planText) {
        if (conduct == null || conduct.isBlank() || planText == null || planText.isBlank()) {
            return null;
        }
        var matcher = Pattern.compile("(禁止|不得|绝不|严禁|不能|不允许)"
                + "([^，。；、!？\\n]{2,14})").matcher(conduct);
        while (matcher.find()) {
            String action = matcher.group(2).trim();
            if (action.length() < 3) {
                continue;
            }
            String normalizedAction = normalizePersonaText(action);
            String normalizedPlan = normalizePersonaText(planText);
            if (normalizedAction.isEmpty() || normalizedPlan.isEmpty()) {
                continue;
            }
            int idx = normalizedPlan.indexOf(normalizedAction);
            while (idx >= 0) {
                String prefix = idx == 0 ? ""
                        : normalizedPlan.substring(Math.max(0, idx - 6), idx);
                if (!hasNegationPrefix(prefix)) {
                    return action;
                }
                idx = normalizedPlan.indexOf(normalizedAction, idx + normalizedAction.length());
            }
        }
        return null;
    }

    private static boolean hasNegationPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return false;
        }
        for (String phrase : NEGATION_PHRASES) {
            if (prefix.endsWith(phrase)) {
                return true;
            }
        }
        char last = prefix.charAt(prefix.length() - 1);
        return last == '不' || last == '未' || last == '非' || last == '无'
                || last == '止' || last == '得' || last == '能' || last == '许'
                || last == '严';
    }

    private static String normalizePersonaText(String text) {
        return text.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "").toLowerCase();
    }


    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
                        "", "", ""));
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
