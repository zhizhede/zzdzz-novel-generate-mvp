package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleProjector;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.DeriveChapterScheduleMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.genre.GenreProfile;
import com.zzdzz.novelgen.derive.genre.GenreProfileRegistry;
import com.zzdzz.novelgen.derive.genre.GenreContract;
import com.zzdzz.novelgen.derive.genre.MysteryContract;
import com.zzdzz.novelgen.derive.genre.RomanceContract;
import com.zzdzz.novelgen.derive.genre.RomanceStage;
import com.zzdzz.novelgen.derive.genre.RomanceStageKind;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.derive.genre.RomanceProfile;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryObligationProjector;
import com.zzdzz.novelgen.model.entity.derive.DeriveChapterScheduleDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 章级日程的持久化与版本守卫（合同的纯函数投影落库，不是第二个 LLM 节点）。
 *
 * <p>写入只发生在 SCHEDULE 单元；ROUTE/⑥b/P6 只读。合同一旦重新冻结（version 变化），
 * 旧日程立即视为过期（{@code SCHEDULE_STALE}），禁止被下游继续使用。
 *
 * <p>范围解析顺序固定为 {@code step_status.SCAFFOLD → derive_volume_plan 行 → 单卷兜底}，
 * **禁止按“平均分卷”猜**——猜错卷边界等于让下游拿错章号写内容。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveChapterScheduleService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeriveChapterScheduleMapper scheduleMapper;
    private final DeriveStoryObligationMapper obligationMapper;
    private final RunMapper runMapper;
    private final DeriveContractService contractService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired(required = false)
    private VolumePlanMapper volumePlanMapper;
    @Autowired(required = false)
    private CharacterPlanMapper characterPlanMapper;
    @Autowired(required = false)
    private GenreProfileRegistry genreProfileRegistry;
    @Autowired(required = false)
    private com.zzdzz.novelgen.derive.story.ContractVersionService contractVersionService;

    /** 纯投影（不落库）：合同 + 章数 + 卷范围 → 每章日程。 */
    public List<ChapterScheduleEntry> project(RunDO run) {
        GenreContract genreContract = contractService.loadGenreContract(run);
        if (genreContract instanceof RomanceContract romance) {
            return romanceSchedule(romance);
        }
        StoryContract contract = contractService.require(run);
        int chapterCount = chapterCount(run, contract);
        return ChapterScheduleProjector.project(contract, chapterCount,
                volumeRanges(run, contract, chapterCount));
    }

    /** 先删后插，并写 step_status 审计快照（读方仍以表为准）。 */
    @Transactional
    public List<ChapterScheduleEntry> persist(RunDO run, List<ChapterScheduleEntry> entries) {
        if (run == null || run.getId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SCHEDULE_REQUIRED：缺少 run");
        }
        List<ChapterScheduleEntry> rows = entries == null ? List.of() : entries;
        GenreContract genreContract = contractService.requireGenreContract(run);
        if (genreContract == null) {
            genreContract = new MysteryContract(contractService.require(run));
        }
        int version = contractVersion(genreContract);
        scheduleMapper.deleteByRun(run.getId());
        for (ChapterScheduleEntry entry : rows) {
            DeriveChapterScheduleDO row = new DeriveChapterScheduleDO();
            row.setRunId(run.getId());
            row.setContractVersion(version);
            row.setChapterNo(entry.chapterNo());
            row.setVolumeNo(entry.volumeNo());
            row.setReveal(entry.reveal());
            row.setMustPlant(toJson(entry.mustPlant()));
            row.setMustPayoff(toJson(entry.mustPayoff()));
            scheduleMapper.insertFull(row);
        }
        writeSnapshot(run, version, rows);
        persistObligations(run, genreContract, rows);
        log.info("章级日程已投影：runId={} contractVersion={} 章数={} 埋点章={} 兑现章={}",
                run.getId(), version, rows.size(),
                rows.stream().filter(entry -> !entry.mustPlant().isEmpty()).count(),
                rows.stream().filter(entry -> !entry.mustPayoff().isEmpty()).count());
        return rows;
    }

    private void persistObligations(RunDO run, GenreContract contract,
                                    List<ChapterScheduleEntry> entries) {
        if (obligationMapper == null) {
            return;
        }
        obligationMapper.deleteByRun(run.getId());
        GenreProfile profile = profileForRun(run);
        List<PlanContracts.VolumeContract> volumes = volumeContracts(run);
        List<PlanContracts.CharacterContract> characters = characterContracts(run);
        List<com.zzdzz.novelgen.derive.story.StoryObligation> obligations =
                StoryObligationProjector.project(profile, contract, entries, volumes, characters);
        for (com.zzdzz.novelgen.derive.story.StoryObligation obligation : obligations) {
            DeriveStoryObligationDO row = new DeriveStoryObligationDO();
            row.setRunId(run.getId());
            row.setObligationKey(obligation.obligationKey());
            row.setChapterNo(obligation.chapterNo());
            row.setKind(obligation.kind());
            row.setTargetKey(obligation.targetKey());
            row.setRequired(obligation.required());
            row.setForbidden(obligation.forbidden());
            row.setSeverity(obligation.severity());
            row.setSourceContract(obligation.sourceContract());
            row.setStatus("PLANNED");
            row.setPayload(toJson(obligation.payload()));
            obligationMapper.insertFull(row);
        }
    }

    private List<ChapterScheduleEntry> romanceSchedule(RomanceContract contract) {
        int maxChapter = contract.stages().values().stream()
                .mapToInt(RomanceStage::chapterNo).max()
                .orElse(contract.scope() == null ? 1 : contract.scope().chapterCount());
        int closureChapter = contract.stages().get(RomanceStageKind.RELATIONSHIP_CLOSURE)
                .chapterNo();
        List<ChapterScheduleEntry> out = new ArrayList<>();
        for (int chapterNo = 1; chapterNo <= maxChapter; chapterNo++) {
            out.add(new ChapterScheduleEntry(chapterNo, 1, chapterNo == closureChapter,
                    List.of(), List.of()));
        }
        return out;
    }

    private int contractVersion(GenreContract contract) {
        if (contract instanceof MysteryContract mystery) {
            return mystery.story().version();
        }
        if (contract instanceof RomanceContract romance) {
            return romance.version();
        }
        return 1;
    }

    private GenreProfile profileForRun(RunDO run) {
        GenreProfileRegistry registry = genreProfileRegistry;
        if (registry == null) {
            registry = new GenreProfileRegistry(List.of(new MysteryProfile(), new RomanceProfile()));
        }
        return registry.require(run.getGenre() == null || run.getGenre().isBlank()
                ? "MYSTERY" : run.getGenre());
    }

    private List<PlanContracts.VolumeContract> volumeContracts(RunDO run) {
        if (volumePlanMapper == null) {
            return List.of();
        }
        List<VolumePlanDO> rows = volumePlanMapper.selectList(
                new LambdaQueryWrapper<VolumePlanDO>()
                        .eq(VolumePlanDO::getRunId, run.getId())
                        .orderByAsc(VolumePlanDO::getVolumeNo));
        List<PlanContracts.VolumeContract> out = new ArrayList<>();
        for (VolumePlanDO row : rows) {
            JsonNode contract = readJson(row.getContract());
            out.add(new PlanContracts.VolumeContract(
                    nvl(row.getVolumeNo(), 0), nvl(row.getFromChapter(), 0),
                    nvl(row.getToChapter(), 0), nvl(row.getArc(), ""),
                    contract.path("localQuestion").asText(""), contract.path("falseAnswer").asText(""),
                    contract.path("trueAnswer").asText(""), contract.path("mainMysteryAdvance").asText(""),
                    textList(contract.path("focusCharacters")), textList(contract.path("relationshipChanges")),
                    textList(contract.path("requiredThreads")), textList(contract.path("forbiddenReveals")),
                    nvl(row.getLocalClosure(), ""), nvl(row.getNextHook(), ""), nvl(row.getGlobalRole(), "")));
        }
        return out;
    }

    private List<PlanContracts.CharacterContract> characterContracts(RunDO run) {
        if (characterPlanMapper == null) {
            return List.of();
        }
        List<CharacterPlanDO> rows = characterPlanMapper.selectList(
                new LambdaQueryWrapper<CharacterPlanDO>()
                        .eq(CharacterPlanDO::getRunId, run.getId())
                        .orderByAsc(CharacterPlanDO::getCharacterKey));
        List<PlanContracts.CharacterContract> out = new ArrayList<>();
        for (CharacterPlanDO row : rows) {
            JsonNode contract = readJson(row.getContract());
            PlanContracts.CharacterFunction function;
            try {
                function = PlanContracts.CharacterFunction.valueOf(nvl(row.getFunctionCode(), "SUPPORT"));
            } catch (RuntimeException e) {
                function = PlanContracts.CharacterFunction.SUPPORT;
            }
            out.add(new PlanContracts.CharacterContract(
                    nvl(row.getCharacterKey(), ""), nvl(row.getDisplayNameHint(), ""), function,
                    contract.path("uniqueContribution").asText(""), contract.path("want").asText(""),
                    contract.path("flaw").asText(""), textList(contract.path("requiredSetup")),
                    textList(contract.path("conflictTargets")), contract.path("functionalConflict").asText(""),
                    contract.path("highlightChoice").asText(""), contract.path("highlightCost").asText(""),
                    textList(contract.path("relationshipChanges")), contract.path("payoffProof").asText(""),
                    row.getSpotlightVolume(), row.getConflictVolume(), row.getPayoffVolume(),
                    nvl(row.getArcBefore(), ""), nvl(row.getArcAfter(), ""),
                    contract.path("castRole").asText("")));
        }
        return out;
    }

    private JsonNode readJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText("").isBlank()) {
                out.add(item.asText(""));
            }
        });
        return out;
    }

    private static <T> T nvl(T value, T fallback) {
        return value == null ? fallback : value;
    }


    /** 读日程表；空表返回空列表（旧 run 由调用方补投影）。 */
    public List<ChapterScheduleEntry> load(RunDO run) {
        if (run == null || run.getId() == null) {
            return List.of();
        }
        return toEntries(scheduleMapper.selectByRun(run.getId()));
    }

    /** 读日程表；空表 fail-fast（下游不允许“没有日程也继续”）。 */
    public List<ChapterScheduleEntry> require(RunDO run) {
        List<ChapterScheduleEntry> entries = load(run);
        if (entries.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "SCHEDULE_REQUIRED：章级日程尚未投影（先推进 SCHEDULE 单元）");
        }
        return entries;
    }

    /** 版本守卫：表内 contract_version 必须等于当前合同 version，否则 fail-fast。 */
    public void assertCurrent(RunDO run) {
        if (run == null || run.getId() == null) {
            return;
        }
        List<DeriveChapterScheduleDO> rows = scheduleMapper.selectByRun(run.getId());
        if (rows.isEmpty()) {
            return;
        }
        if (contractVersionService != null) {
            contractService.loadGenreContract(run);
            contractVersionService.assertConsistent(run);
            return;
        }
        StoryContract contract = contractService.load(run);
        if (contract == null) {
            return;
        }
        Integer stored = rows.get(0).getContractVersion();
        int actual = stored == null ? 0 : stored;
        if (actual != contract.version()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "SCHEDULE_STALE：日程合同版本 " + actual + " 与合同 " + contract.version()
                            + " 不一致，必须重新投影");
        }
    }

    /** 删除某 run 的全部日程行（清空规划 / SCALE 回炉）。 */
    public void deleteByRun(long runId) {
        scheduleMapper.deleteByRun(runId);
    }

    // ------------------------------------------------------------------
    // 范围/章数解析（SCAFFOLD → volume_plan → 单卷兜底）
    // ------------------------------------------------------------------

    /** 章数：优先 scaffold.chapterCount()，无 scaffold 时用合同 scope，再不行报错。 */
    int chapterCount(RunDO run, StoryContract contract) {
        ScaffoldContract scaffold = readScaffold(run);
        if (scaffold != null && scaffold.chapterCount() > 0) {
            return scaffold.chapterCount();
        }
        if (contract != null && contract.scope() != null && contract.scope().chapterCount() > 0) {
            return contract.scope().chapterCount();
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                ChapterScheduleProjector.INVALID + "：章数不可用（scaffold 与合同 scope 都缺）");
    }

    /** 卷范围：scaffold → derive_volume_plan 行 → 单卷兜底；多卷书缺范围直接报错。 */
    List<ScaffoldContract.VolumeRange> volumeRanges(RunDO run, StoryContract contract,
                                                    int chapterCount) {
        ScaffoldContract scaffold = readScaffold(run);
        if (scaffold != null && scaffold.volumeRanges() != null
                && !scaffold.volumeRanges().isEmpty()) {
            return scaffold.volumeRanges();
        }
        List<ScaffoldContract.VolumeRange> fromPlan = volumePlanRanges(run, contract);
        if (!fromPlan.isEmpty()) {
            return fromPlan;
        }
        int volumeCount = contract != null && contract.scope() != null
                ? contract.scope().volumeCount() : 1;
        if (volumeCount == 1) {
            return List.of(new ScaffoldContract.VolumeRange(1, 1, chapterCount));
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                ChapterScheduleProjector.INVALID + "：多卷书缺少卷范围（scaffold 与 volume_plan 均不可用）");
    }

    /** 已有 derive_volume_plan 行生成卷范围；行数不足或未覆盖全书时退回空（交给兜底/报错）。 */
    private List<ScaffoldContract.VolumeRange> volumePlanRanges(RunDO run, StoryContract contract) {
        if (volumePlanMapper == null || run == null || run.getId() == null) {
            return List.of();
        }
        List<VolumePlanDO> rows = volumePlanMapper.selectList(new LambdaQueryWrapper<VolumePlanDO>()
                .eq(VolumePlanDO::getRunId, run.getId())
                .orderByAsc(VolumePlanDO::getVolumeNo));
        if (rows.isEmpty()) {
            return List.of();
        }
        int expect = contract != null && contract.scope() != null
                ? contract.scope().volumeCount() : rows.size();
        if (rows.size() != expect) {
            return List.of();
        }
        List<ScaffoldContract.VolumeRange> ranges = new ArrayList<>();
        for (VolumePlanDO row : rows) {
            if (row.getVolumeNo() == null || row.getFromChapter() == null
                    || row.getToChapter() == null) {
                return List.of();
            }
            ranges.add(new ScaffoldContract.VolumeRange(row.getVolumeNo(),
                    row.getFromChapter(), row.getToChapter()));
        }
        return ranges;
    }

    private ScaffoldContract readScaffold(RunDO run) {
        if (run == null || run.getStepStatus() == null || run.getStepStatus().isBlank()) {
            return null;
        }
        JsonNode node = readTreeSafe(run.getStepStatus()).path("steps").path("SCAFFOLD");
        if (!node.isObject()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(node, ScaffoldContract.class);
        } catch (Exception e) {
            log.warn("SCAFFOLD 反序列化失败，日程范围回退 volume_plan：runId={} 原因={}",
                    run.getId(), e.getMessage());
            return null;
        }
    }

    /** 表行 → 日程；JSONB 数组按字符串解析。 */
    private List<ChapterScheduleEntry> toEntries(List<DeriveChapterScheduleDO> rows) {
        List<ChapterScheduleEntry> out = new ArrayList<>();
        for (DeriveChapterScheduleDO row : rows) {
            out.add(new ChapterScheduleEntry(
                    row.getChapterNo() == null ? 0 : row.getChapterNo(),
                    row.getVolumeNo() == null ? 1 : row.getVolumeNo(),
                    Boolean.TRUE.equals(row.getReveal()),
                    textList(row.getMustPlant()),
                    textList(row.getMustPayoff())));
        }
        return out;
    }

    /** 写 step_status.steps.CHAPTER_SCHEDULE（仅审计，读方以表为准）。 */
    private void writeSnapshot(RunDO run, int contractVersion, List<ChapterScheduleEntry> entries) {
        if (runMapper == null) {
            return;
        }
        RunDO current = runMapper.selectById(run.getId());
        if (current == null) {
            return;
        }
        ObjectNode root = readTreeSafe(current.getStepStatus()).isObject()
                ? (ObjectNode) readTreeSafe(current.getStepStatus()).deepCopy()
                : MAPPER.createObjectNode();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        ObjectNode node = steps.putObject("CHAPTER_SCHEDULE");
        node.put("contractVersion", contractVersion);
        ArrayNode arr = node.putArray("entries");
        for (ChapterScheduleEntry entry : entries) {
            ObjectNode item = arr.addObject();
            item.put("chapterNo", entry.chapterNo());
            item.put("volumeNo", entry.volumeNo());
            item.put("reveal", entry.reveal());
            item.set("mustPlant", MAPPER.valueToTree(entry.mustPlant()));
            item.set("mustPayoff", MAPPER.valueToTree(entry.mustPayoff()));
        }
        runMapper.updateStepStatus(run.getId(), root.toString());
    }

    private static List<String> textList(String json) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "[]" : json);
            if (node.isArray()) {
                node.forEach(item -> {
                    if (item.isTextual() && !item.asText("").isBlank()) {
                        out.add(item.asText(""));
                    }
                });
            }
        } catch (Exception e) {
            return List.of();
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

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
