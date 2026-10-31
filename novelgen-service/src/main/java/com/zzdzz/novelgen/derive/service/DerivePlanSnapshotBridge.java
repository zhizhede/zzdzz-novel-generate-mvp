package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionResult;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionService;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompileInput;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompilerSupport;
import com.zzdzz.novelgen.derive.plan.compiler.PlanCompileResult;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.derive.plan.compiler.StoryPlanCompiler;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 把既有 PLAN_V2 的合同、日程、章行和拍行编译成 compiler-v1 快照。
 *
 * <p>这是旧规划入口到不可变计划快照的兼容桥：结构字段仍由 {@link StoryPlanCompiler}
 * 重新求解，已有 route_contract/filled_text 只作为叙事字段回填，不再作为第二套结构真源。
 * 桥只在没有 active revision 且正文尚未写入时执行。
 */
@Service
public class DerivePlanSnapshotBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunMapper runMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final CharacterPlanMapper characterPlanMapper;
    private final VolumePlanMapper volumePlanMapper;
    private final DeriveContractService contractService;
    private final DeriveChapterScheduleService scheduleService;
    private final StoryPlanCompiler compiler;
    private final PlanAdmissionService admissionService;

    private final PlanSnapshotService snapshotService;

    /** 创建计划快照兼容桥。 */
    public DerivePlanSnapshotBridge(
            RunMapper runMapper,
            ChapterMapper chapterMapper,
            BeatInstanceMapper beatInstanceMapper,
            CharacterPlanMapper characterPlanMapper,
            VolumePlanMapper volumePlanMapper,
            DeriveContractService contractService,
            DeriveChapterScheduleService scheduleService,
            StoryPlanCompiler compiler,
            PlanAdmissionService admissionService,
            PlanSnapshotService snapshotService) {
        this.runMapper = Objects.requireNonNull(runMapper);
        this.chapterMapper = Objects.requireNonNull(chapterMapper);
        this.beatInstanceMapper = Objects.requireNonNull(beatInstanceMapper);
        this.characterPlanMapper = Objects.requireNonNull(characterPlanMapper);
        this.volumePlanMapper = Objects.requireNonNull(volumePlanMapper);
        this.contractService = Objects.requireNonNull(contractService);
        this.scheduleService = Objects.requireNonNull(scheduleService);
        this.compiler = Objects.requireNonNull(compiler);
        this.admissionService = Objects.requireNonNull(admissionService);
        this.snapshotService = Objects.requireNonNull(snapshotService);
    }

    /** 从当前已验证的旧规划结果生成 DRAFT snapshot 并执行 admission。 */
    public PlanAdmissionResult materializeAndAdmit(long runId) {
        RunDO run = runMapper.selectById(runId);
        requireUsableRun(run);
        if (run.getActivePlanRevision() != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "PLAN_SNAPSHOT_ALREADY_ACTIVE：" + runId);
        }
        requireNoText(run);
        StoryContract contract = contractService.require(run);
        if (!"FROZEN".equals(contract.status())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "计划快照桥只允许冻结合同，当前状态=" + contract.status());
        }
        List<ChapterScheduleEntry> schedule = scheduleService.require(run);
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, run.getNovelId())
                .orderByAsc(ChapterDO::getChapterNo));
        List<BeatInstanceDO> beatRows = beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>()
                        .eq(BeatInstanceDO::getRunId, runId)
                        .orderByAsc(BeatInstanceDO::getChapterNo)
                        .orderByAsc(BeatInstanceDO::getSeq));
        CompiledStoryPlan plan = buildPlan(run, contract, schedule, chapters, beatRows,
                loadVolumes(runId), loadCharacters(runId));
        int revision = snapshotService.persistDraft(runId, plan).getRevision();
        return admissionService.admit(runId, revision);
    }

    /** 用旧规划行构造 compiler-v1 计划；仅在测试和兼容桥内部使用。 */
    CompiledStoryPlan buildPlan(
            RunDO run,
            StoryContract contract,
            List<ChapterScheduleEntry> schedule,
            List<ChapterDO> chapters,
            List<BeatInstanceDO> beatRows,
            List<PlanContracts.VolumeContract> volumes,
            List<PlanContracts.CharacterContract> characters) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(contract, "contract");
        if (contract.scope() == null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "冻结合同缺少 scope");
        }
        if (schedule == null || schedule.isEmpty()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "计划快照缺少章级日程");
        }
        List<BeatInstanceDO> allBeatRows = beatRows == null ? List.of() : beatRows;
        validateDependencies(allBeatRows);
        List<BeatInstanceDO> usable = beatRows == null ? List.of() : beatRows.stream()
                .filter(row -> row.getChapterNo() != null && row.getSeq() != null)
                .filter(row -> !"SKIPPED".equals(row.getStatus()))
                .sorted(Comparator
                        .comparingInt((BeatInstanceDO row) -> row.getChapterNo())
                        .thenComparingInt(BeatInstanceDO::getSeq))
                .toList();
        List<PlanGraphRecords.BeatCandidate> candidates = candidates(usable);
        ScaffoldContract scaffold = scaffold(contract.scope(), schedule);
        PlanCompileInput input = new PlanCompileInput(
                run.getId(),
                Math.max(1, contract.version()),
                PlanCompilerSupport.contractHash(contract),
                contract.scope(),
                contract,
                scaffold,
                schedule,
                candidates,
                volumes == null ? List.of() : volumes,
                characters == null ? List.of() : characters,
                new BigDecimal("0.50"));
        PlanCompileResult compiled = compiler.compile(input);
        Map<String, BeatInstanceDO> oldByKey = oldByKey(usable);
        Map<Integer, String> oldSeqToKey = oldSeqToKey(usable);
        List<PlanGraphRecords.PlannedBeat> beats = compiled.plan().beats().stream()
                .map(beat -> overlay(beat, oldByKey.get(beat.beatKey()),
                        oldSeqToKey, compiled.plan().beats()))
                .toList();
        Map<Integer, ChapterDO> chapterByNo = chapters == null ? Map.of() : chapters.stream()
                .collect(Collectors.toMap(ChapterDO::getChapterNo, row -> row, (left, right) -> left));
        List<PlanGraphRecords.PlannedChapter> plannedChapters = compiled.plan().chapters().stream()
                .map(chapter -> plannedChapter(chapter, beats, chapterByNo.get(chapter.chapterNo()),
                        contract.scope()))
                .toList();
        return new CompiledStoryPlan(
                run.getId(),
                Math.max(1, contract.version()),
                PlanCompilerSupport.contractHash(contract),
                contract.scope(),
                schedule,
                compiled.plan().volumes(),
                compiled.plan().characters(),
                plannedChapters,
                beats);
    }

    private static void validateDependencies(List<BeatInstanceDO> rows) {
        Map<Integer, String> seqToKey = oldSeqToKey(rows);
        Set<String> candidateKeys = rows.stream()
                .filter(row -> !"SKIPPED".equals(row.getStatus()))
                .map(DerivePlanSnapshotBridge::beatKey)
                .filter(key -> !key.startsWith("SYN-"))
                .collect(Collectors.toSet());
        for (BeatInstanceDO row : rows) {
            if ("SKIPPED".equals(row.getStatus())) {
                continue;
            }
            RouteData route = route(row.getRouteContract());
            for (Integer dependency : route.dependsOn) {
                String key = seqToKey.get(dependency);
                if (key == null) {
                    throw new BizException(ErrorCode.STATE_CONFLICT,
                            "PLAN_DEPENDENCY_UNMAPPABLE：beat=" + beatKey(row)
                                    + " 依赖不存在的 seq=" + dependency);
                }
                if (!key.startsWith("SYN-") && !candidateKeys.contains(key)) {
                    throw new BizException(ErrorCode.STATE_CONFLICT,
                            "PLAN_DEPENDENCY_SKIPPED：beat=" + beatKey(row)
                                    + " 依赖已被跳过的拍=" + key);
                }
            }
        }
    }

    private static void requireUsableRun(RunDO run) {
        if (run == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在或未关联成品书");
        }
        if (!"DRAFTING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "计划快照桥只允许在 DRAFTING 执行，当前=" + run.getStatus());
        }
    }

    private void requireNoText(RunDO run) {
        long nonEmpty = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                        .eq(ChapterDO::getNovelId, run.getNovelId()))
                .stream()
                .filter(row -> row.getContent() != null && !row.getContent().isBlank())
                .count();
        if (nonEmpty > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "已有正文，不允许再生成计划快照：novel=" + run.getNovelId());
        }
    }

    private List<PlanContracts.VolumeContract> loadVolumes(long runId) {
        return volumePlanMapper.selectList(new LambdaQueryWrapper<VolumePlanDO>()
                        .eq(VolumePlanDO::getRunId, runId)
                        .orderByAsc(VolumePlanDO::getVolumeNo))
                .stream()
                .map(row -> read(row.getContract(), PlanContracts.VolumeContract.class,
                        "卷合同 JSON 损坏: " + row.getId()))
                .toList();
    }

    private List<PlanContracts.CharacterContract> loadCharacters(long runId) {
        return characterPlanMapper.selectList(new LambdaQueryWrapper<CharacterPlanDO>()
                        .eq(CharacterPlanDO::getRunId, runId)
                        .orderByAsc(CharacterPlanDO::getCharacterKey))
                .stream()
                .map(row -> read(row.getContract(), PlanContracts.CharacterContract.class,
                        "角色合同 JSON 损坏: " + row.getId()))
                .toList();
    }

    private static <T> T read(String json, Class<T> type, String message) {
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, message + "：内容为空");
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new BizException(ErrorCode.STATE_CONFLICT, message, e);
        }
    }

    private static List<PlanGraphRecords.BeatCandidate> candidates(List<BeatInstanceDO> rows) {
        Map<Integer, String> oldSeqToKey = oldSeqToKey(rows);
        List<BeatInstanceDO> candidateRows = rows.stream()
                .filter(row -> !beatKey(row).startsWith("SYN-"))
                .sorted(Comparator.comparing(DerivePlanSnapshotBridge::beatKey))
                .toList();
        Map<String, Integer> candidateIndex = new HashMap<>();
        for (int index = 0; index < candidateRows.size(); index++) {
            candidateIndex.put(beatKey(candidateRows.get(index)), index + 1);
        }
        List<PlanGraphRecords.BeatCandidate> out = new ArrayList<>();
        for (BeatInstanceDO row : candidateRows) {
            String key = beatKey(row);
            RouteData route = route(row.getRouteContract());
            List<Integer> dependencies = route.dependsOn.stream()
                    .map(oldSeqToKey::get)
                    .map(candidateIndex::get)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            out.add(new PlanGraphRecords.BeatCandidate(
                    key,
                    row.getSource() == null ? "ASSET" : row.getSource(),
                    row.getAssetId(),
                    row.getArcId(),
                    row.getArcSegNo(),
                    row.getChapterNo(),
                    row.getBeatType() == null || row.getBeatType().isBlank()
                            ? "事件" : row.getBeatType(),
                    null,
                    row.getPacing() == null ? new BigDecimal("0.50") : row.getPacing(),
                    dependencies));
        }
        return List.copyOf(out);
    }

    private static Map<String, BeatInstanceDO> oldByKey(List<BeatInstanceDO> rows) {
        Map<String, BeatInstanceDO> out = new LinkedHashMap<>();
        rows.forEach(row -> out.putIfAbsent(beatKey(row), row));
        return out;
    }

    private static Map<Integer, String> oldSeqToKey(List<BeatInstanceDO> rows) {
        Map<Integer, String> out = new HashMap<>();
        rows.forEach(row -> out.put(row.getSeq(), beatKey(row)));
        return out;
    }

    private static String beatKey(BeatInstanceDO row) {
        return row.getBeatKey() == null || row.getBeatKey().isBlank()
                ? "B" + row.getSeq() : row.getBeatKey();
    }

    private static PlanGraphRecords.PlannedBeat overlay(
            PlanGraphRecords.PlannedBeat compiled,
            BeatInstanceDO old,
            Map<Integer, String> oldSeqToKey,
            List<PlanGraphRecords.PlannedBeat> allCompiled) {
        if (old == null) {
            return compiled;
        }
        RouteData route = route(old.getRouteContract());
        PlanContracts.StoryLayer layer = enumValue(
                PlanContracts.StoryLayer.class, old.getStoryLayer(),
                compiled.storyLayer(), PlanContracts.StoryLayer.LOCAL_CASE);
        PlanContracts.BeatRole role = enumValue(
                PlanContracts.BeatRole.class, old.getBeatRole(),
                compiled.beatRole(), PlanContracts.BeatRole.LOCAL_CLOSURE);
        PlanContracts.AppearanceMode appearance = enumValue(
                PlanContracts.AppearanceMode.class, route.appearanceMode,
                compiled.appearanceMode(), PlanContracts.AppearanceMode.LIVE);
        List<Integer> dependencies = route.dependsOn.stream()
                .map(oldSeqToKey::get)
                .filter(Objects::nonNull)
                .map(key -> allCompiled.stream()
                        .filter(beat -> key.equals(beat.beatKey()))
                        .mapToInt(PlanGraphRecords.PlannedBeat::seq)
                        .findFirst()
                        .orElse(0))
                .filter(value -> value > 0 && value < compiled.seq())
                .distinct()
                .sorted()
                .toList();
        List<PlanGraphRecords.KnowledgeEffect> knowledge = route.writesKnowledge.stream()
                .map(fact -> new PlanGraphRecords.KnowledgeEffect(
                        route.focusCharacters.isEmpty() ? List.of("C1") : route.focusCharacters,
                        fact))
                .toList();
        List<PlanGraphRecords.StateEffect> state = stateEffects(route);
        String goal = old.getFilledText() == null || old.getFilledText().isBlank()
                ? compiled.goal() : old.getFilledText().trim();
        return new PlanGraphRecords.PlannedBeat(
                compiled.seq(),
                compiled.beatKey(),
                old.getSource() == null ? compiled.source() : old.getSource(),
                old.getAssetId() == null ? compiled.assetId() : old.getAssetId(),
                old.getArcId() == null ? compiled.arcId() : old.getArcId(),
                old.getArcSegNo() == null ? compiled.arcSegNo() : old.getArcSegNo(),
                old.getBeatType() == null || old.getBeatType().isBlank()
                        ? compiled.beatType() : old.getBeatType(),
                old.getPacing() == null ? compiled.pacing() : old.getPacing(),
                compiled.chapterNo(),
                compiled.volumeNo(),
                compiled.sceneNo(),
                layer,
                role,
                appearance,
                goal,
                route.focusCharacters.isEmpty() ? compiled.focusCharacters() : route.focusCharacters,
                route.threadRefs.isEmpty() ? compiled.threadRefs() : route.threadRefs,
                knowledge,
                state,
                dependencies,
                route.payoffTarget.isBlank() ? compiled.payoffTarget() : route.payoffTarget,
                route.requiresKnowledge,
                route.writesKnowledge);
    }

    private static List<PlanGraphRecords.StateEffect> stateEffects(RouteData route) {
        List<PlanGraphRecords.StateEffect> out = new ArrayList<>();
        route.changesState.forEach((character, values) -> {
            for (StateValue value : values) {
                out.add(new PlanGraphRecords.StateEffect(
                        character, value.kind(), value.detail()));
            }
        });
        return List.copyOf(out);
    }

    private static PlanGraphRecords.PlannedChapter plannedChapter(
            PlanGraphRecords.PlannedChapter compiled,
            List<PlanGraphRecords.PlannedBeat> beats,
            ChapterDO chapter,
            PlanContracts.ScopeContract scope) {
        List<PlanGraphRecords.PlannedBeat> chapterBeats = beats.stream()
                .filter(beat -> beat.chapterNo() == compiled.chapterNo())
                .sorted(Comparator.comparingInt(PlanGraphRecords.PlannedBeat::sceneNo))
                .toList();
        List<PlanGraphRecords.PlannedScene> scenes = new ArrayList<>();
        int sceneNo = 1;
        for (PlanGraphRecords.PlannedBeat beat : chapterBeats) {
            scenes.add(new PlanGraphRecords.PlannedScene(
                    sceneNo++,
                    beat.beatKey(),
                    beat.goal(),
                    List.of(),
                    List.of(),
                    beat.requiresKnowledge(),
                    beat.writesKnowledge()));
        }
        String title = chapter == null || chapter.getTitle() == null || chapter.getTitle().isBlank()
                ? compiled.title() : chapter.getTitle();
        int budget = chapter == null || chapter.getBudget() == null || chapter.getBudget() <= 0
                ? Math.max(1, scope.chapterAvg()) : chapter.getBudget();
        return new PlanGraphRecords.PlannedChapter(
                compiled.chapterNo(), compiled.volumeNo(), title, budget, scenes);
    }

    private static ScaffoldContract scaffold(
            PlanContracts.ScopeContract scope, List<ChapterScheduleEntry> schedule) {
        Map<Integer, List<ChapterScheduleEntry>> byVolume = new LinkedHashMap<>();
        schedule.forEach(entry -> byVolume.computeIfAbsent(entry.volumeNo(),
                key -> new ArrayList<>()).add(entry));
        List<ScaffoldContract.VolumeRange> ranges = new ArrayList<>();
        byVolume.forEach((volume, rows) -> ranges.add(new ScaffoldContract.VolumeRange(
                volume,
                rows.get(0).chapterNo(),
                rows.get(rows.size() - 1).chapterNo())));
        if (ranges.isEmpty()) {
            ranges.add(new ScaffoldContract.VolumeRange(1, 1, scope.chapterCount()));
        }
        ranges.sort(Comparator.comparingInt(ScaffoldContract.VolumeRange::volumeNo));
        return new ScaffoldContract(scope.chapterCount(), scope.volumeCount(),
                List.copyOf(ranges), List.of(), List.of());
    }

    private record RouteData(
            List<String> focusCharacters,
            List<String> threadRefs,
            List<Integer> dependsOn,
            String payoffTarget,
            String appearanceMode,
            List<String> requiresKnowledge,
            List<String> writesKnowledge,
            Map<String, List<StateValue>> changesState) {

        static RouteData empty() {
            return new RouteData(List.of(), List.of(), List.of(), "", "LIVE",
                    List.of(), List.of(), Map.of());
        }
    }

    private record StateValue(PlanContracts.StateChangeKind kind, String detail) {
    }

    private static RouteData route(String json) {
        if (json == null || json.isBlank()) {
            return RouteData.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return new RouteData(
                    strings(node.path("focusCharacters")),
                    strings(node.path("threadRefs")),
                    ints(node.path("dependsOn")),
                    node.path("payoffTarget").asText(""),
                    node.path("appearanceMode").asText("LIVE"),
                    strings(node.path("requiresKnowledge")),
                    strings(node.path("writesKnowledge")),
                    stateMap(node.path("changesState")));
        } catch (Exception e) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "route_contract JSON 无效", e);
        }
    }

    private static Map<String, List<StateValue>> stateMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<StateValue>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual()) {
                out.put(entry.getKey(), List.of(stateValue(value.asText(), value)));
            } else if (value.isArray()) {
                List<StateValue> values = new ArrayList<>();
                value.forEach(item -> values.add(stateValue(item.asText(""), item)));
                out.put(entry.getKey(), List.copyOf(values));
            } else if (value.isObject()) {
                out.put(entry.getKey(), List.of(stateValue("", value)));
            }
        });
        return Map.copyOf(out);
    }

    private static StateValue stateValue(String raw, JsonNode node) {
        if (node != null && node.isObject()
                && node.path("alive").isBoolean()
                && !node.path("alive").asBoolean()) {
            return new StateValue(PlanContracts.StateChangeKind.DEAD, "alive=false");
        }
        String kind = node != null && node.isObject()
                ? node.path("kind").asText(raw) : raw;
        PlanContracts.StateChangeKind parsed;
        try {
            parsed = PlanContracts.StateChangeKind.valueOf(kind.trim().toUpperCase());
        } catch (Exception ignored) {
            parsed = PlanContracts.StateChangeKind.PRESENT;
        }
        String detail = node != null && node.isObject() ? node.toString() : raw;
        return new StateValue(parsed, detail == null || detail.isBlank() ? kind : detail);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> out.add(item.asText("")));
        return out.stream().filter(value -> !value.isBlank()).toList();
    }

    private static List<Integer> ints(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        node.forEach(item -> out.add(item.asInt(0)));
        return out.stream().filter(value -> value > 0).toList();
    }

    private static <T extends Enum<T>> T enumValue(
            Class<T> type, String raw, T fallback, T default_value) {
        if (raw == null || raw.isBlank()) {
            return fallback == null ? default_value : fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback == null ? default_value : fallback;
        }
    }
}
