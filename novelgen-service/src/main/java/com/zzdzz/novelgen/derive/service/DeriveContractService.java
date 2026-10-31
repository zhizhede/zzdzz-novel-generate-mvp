package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.contract.StoryContractParser;
import com.zzdzz.novelgen.derive.contract.StoryContractValidator;
import com.zzdzz.novelgen.derive.contract.TruthContract;
import com.zzdzz.novelgen.derive.genre.GenreProfile;
import com.zzdzz.novelgen.derive.genre.GenreProfileRegistry;
import com.zzdzz.novelgen.derive.genre.GenreContract;
import com.zzdzz.novelgen.derive.genre.MysteryContract;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryContractMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveGenreContractMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.StoryPlanningPrompts;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryContractDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreContractDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical Story Contract：在 ⑥a 的 SCALE 之后、SPINE 之前生成并冻结唯一故事合同。
 *
 * <p>合同表 {@code derive_story_contract} 是唯一事实源：cast、唯一真相、线索、线程与禁令
 * 一次冻结，SPINE/VOLUMES/CHARACTERS/⑥b/P6/⑦/⑧ 只能引用它做细化。
 * {@code run.step_status.TRUTH_CONTRACT} 只是兼容缓存（旧读方仍按它取真相），不能反向覆盖合同表。
 *
 * <p>幂等口径：已有可用合同直接返回（同一 version，不重复插入）；合同生成失败 fail-fast，
 * 禁止回退到 {@code run.welded} 走旧路径。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveContractService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TRIES = 3;

    private final DeriveStoryContractMapper contractMapper;
    private final RunMapper runMapper;
    private final LlmJson llmJson;
    @Autowired(required = false)
    private com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper fingerprintMapper;
    @Autowired(required = false)
    private GenreProfileRegistry genreProfileRegistry;
    @Autowired(required = false)
    private com.zzdzz.novelgen.derive.genre.GenreContractCodecRegistry genreContractCodecRegistry;
    @Autowired(required = false)
    private DeriveGenreContractMapper genreContractMapper;

    // ------------------------------------------------------------------
    // 合同生成与读取（合同前置主链路）
    // ------------------------------------------------------------------

    /**
     * 生成（或复用）冻结合同：已有可用合同直接返回，否则调 LLM 生成、结构校验后落一行 FROZEN。
     *
     * @param run     当前 run（处于 PLANNING）
     * @param scope   ⑥a SCALE 段产出的规模合同（章数/卷数边界）
     * @param scaffold 系统生成的结构骨架（合同提示词的结构事实来源，可为 null）
     * @return 冻结合同（version=1 的新 run，或历史可用合同）
     */
    public StoryContract generateIfAbsent(RunDO run, PlanContracts.ScopeContract scope,
                                          ScaffoldContract scaffold) {
        if (run == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "CONTRACT_REQUIRED：缺少 run");
        }
        StoryContract existing = load(run);
        if (existing != null) {
            return existing;
        }
        if (scope == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "CONTRACT_REQUIRED：缺少规模合同，无法冻结故事合同");
        }
        GenreProfile<?> profile = profileForRun(run, resolveGenreRegistry());
        String user = buildContractUser(run, scope, scaffold);
        GenreContract parsedContract = llmJson.ask(
                new LlmPort.ChatRequest(StoryPlanningPrompts.NODE_CONTRACT, run.getNovelId(), null,
                        run.getId(),
                        List.of(LlmPort.Message.system(StoryPlanningPrompts.CONTRACT),
                                LlmPort.Message.user(user)),
                        0.3),
                root -> {
                    try {
                        return profile.parseContract(root, scope);
                    } catch (IllegalArgumentException e) {
                        throw new LlmJson.Bad(e.getMessage());
                    }
                }, MAX_TRIES);
        if (!(parsedContract instanceof MysteryContract mysteryContract)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "当前旧合同入口只支持 MYSTERY；请走通用类型合同入口");
        }
        StoryContract parsed = mysteryContract.story();
        DeriveStoryContractDO row = persistContract(run.getId(), parsed);
        persistGenreContract(run, new MysteryContract(parsed), scope);
        StoryContract saved = rowToContract(row, scope);
        writeContractCaches(run.getId(), saved);
        log.info("唯一故事合同已冻结：runId={} version={} culprit={} revealChapter={} clues={} threads={}",
                run.getId(), saved.version(), saved.truth().culpritName(),
                saved.truth().revealChapter(), saved.clues().size(), saved.threads().size());
        return saved;
    }

    static GenreProfile<?> profileForRun(RunDO run, GenreProfileRegistry registry) {
        String genre = run == null || run.getGenre() == null || run.getGenre().isBlank()
                ? "MYSTERY" : run.getGenre();
        try {
            return registry.require(genre);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, e.getMessage());
        }
    }

    private GenreProfileRegistry resolveGenreRegistry() {
        if (genreProfileRegistry != null) {
            return genreProfileRegistry;
        }
        return new GenreProfileRegistry(List.of(new MysteryProfile()));
    }

    /** 读取可用合同；返回 null 表示尚无合同或历史残缺行（残缺行由 generateIfAbsent 升版本重建）。 */
    public StoryContract load(RunDO run) {
        if (run == null) {
            return null;
        }
        DeriveStoryContractDO row = latest(run.getId());
        if (row == null) {
            return null;
        }
        PlanContracts.ScopeContract scope = scopeOf(row);
        if (scope == null) {
            return null;
        }
        StoryContract contract = rowToContract(row, scope);
        if (!StoryContractValidator.validate(contract, scope).isEmpty()) {
            return null;
        }
        return contract;
    }

    /** 必须已有冻结合同；否则 fail-fast，禁止任何节点退回 run.welded 旧路径。 */
    public StoryContract require(RunDO run) {
        StoryContract contract = load(run);
        if (contract == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "CONTRACT_REQUIRED：唯一故事合同尚未冻结（先推进 CONTRACT 单元）");
        }
        return contract;
    }

    /** 读取通用类型合同；旧悬疑 run 无新表时通过 MysteryCodec 兼容读取。 */
    public GenreContract loadGenreContract(RunDO run) {
        if (run == null) {
            return null;
        }
        if (genreContractMapper != null && genreContractCodecRegistry != null) {
            DeriveGenreContractDO row = genreContractMapper.findByRun(run.getId());
            if (row != null) {
                try {
                    return genreContractCodecRegistry.require(row.getGenre()).decode(
                            readTreeSafe(row.getPayload()), scopeFromJson(row.getScope()));
                } catch (RuntimeException e) {
                    log.warn("类型合同读取失败：runId={} genre={} 原因={}",
                            run.getId(), row.getGenre(), e.getMessage());
                }
            }
        }
        StoryContract legacy = load(run);
        return legacy == null ? null : new MysteryContract(legacy);
    }

    /** 必须已有冻结合同；否则 fail-fast。 */
    public GenreContract requireGenreContract(RunDO run) {
        GenreContract contract = loadGenreContract(run);
        if (contract == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "CONTRACT_REQUIRED：类型合同尚未冻结（先推进 CONTRACT 单元）");
        }
        return contract;
    }

    /** 冻结通用类型合同；悬疑类型同时写旧合同表以保持旧链兼容。 */
    public GenreContract freezeGenreContract(RunDO run, GenreContract contract,
                                             PlanContracts.ScopeContract scope) {
        if (run == null || run.getId() == null || contract == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "CONTRACT_REQUIRED：缺少 run 或类型合同");
        }
        persistGenreContract(run, contract, scope);
        return contract;
    }

    private void persistGenreContract(RunDO run, GenreContract contract,
                                      PlanContracts.ScopeContract scope) {
        if (genreContractMapper == null || genreContractCodecRegistry == null) {
            return;
        }
        String genre = contract instanceof MysteryContract ? "MYSTERY"
                : contract instanceof com.zzdzz.novelgen.derive.genre.RomanceContract
                ? "ROMANCE" : "";
        if (genre.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "GENRE_CONTRACT_INVALID：未知合同类型");
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        com.zzdzz.novelgen.derive.genre.GenreContractCodec codec =
                genreContractCodecRegistry.require(genre);
        DeriveGenreContractDO row = new DeriveGenreContractDO();
        row.setRunId(run.getId());
        row.setGenre(genre);
        row.setContractVersion(contract instanceof MysteryContract
                ? ((MysteryContract) contract).story().version()
                : ((com.zzdzz.novelgen.derive.genre.RomanceContract) contract).version());
        row.setScope(toJson(scope));
        row.setPayload(codec.encode(contract).toString());
        row.setStatus("FROZEN");
        genreContractMapper.deleteByRun(run.getId());
        genreContractMapper.insertFull(row);
    }

    private PlanContracts.ScopeContract scopeFromJson(String json) {
        try {
            JsonNode node = readTreeSafe(json);
            if (!node.isObject()) {
                return null;
            }
            return MAPPER.treeToValue(node, PlanContracts.ScopeContract.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 断言合同没被下游改写：cast/真相核/线索/线程/禁令任一变化即 CONTRACT_DRIFT。 */
    public void assertUnchanged(StoryContract before, StoryContract after, String stage) {
        if (!StoryContractValidator.sameCanonical(before, after)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "CONTRACT_DRIFT：" + stage + " 改写了已冻结的唯一故事合同");
        }
    }

    /** 合同注入提示词的 JSON 文本（cast/truth/clues/threads/forbidden/volumeTruth）。 */
    public String promptContext(StoryContract contract) {
        if (contract == null) {
            return "{}";
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("status", contract.status());
        node.put("version", contract.version());
        node.set("cast", MAPPER.valueToTree(contract.cast()));
        node.set("truth", MAPPER.valueToTree(contract.truth()));
        node.set("clues", MAPPER.valueToTree(contract.clues()));
        node.set("threads", MAPPER.valueToTree(contract.threads()));
        node.set("forbidden", MAPPER.valueToTree(contract.forbidden()));
        node.set("volumeTruth", MAPPER.valueToTree(contract.volumeTruth()));
        return node.toString();
    }

    /** 合同提示词输入：创作框架、类别、结局、形态、焊接主线、scaffold 事实与规模边界。 */
    private String buildContractUser(RunDO run, PlanContracts.ScopeContract scope,
                                     ScaffoldContract scaffold) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("创作框架", nullToEmpty(run.getParamIntent()));
        root.set("类别", readTreeSafe(run.getParamTags()));
        root.put("结局形状", nullToEmpty(run.getParamOutcome()));
        root.put("产品形态", nullToEmpty(run.getParamForm()));
        root.put("焊接主线", weldedText(run));
        root.put("chapterCount", scope.chapterCount());
        root.put("volumeCount", scope.volumeCount());
        root.put("maxNamedCharacters", StoryContract.MAX_NAMED_CHARACTERS);
        root.put("规模证据", String.join("；", scope.evidence() == null ? List.of() : scope.evidence()));
        List<String> banned = bannedNames(run);
        if (!banned.isEmpty()) {
            root.set("禁用人名（不得用于 cast）", MAPPER.valueToTree(banned));
        }
        if (scaffold != null) {
            ObjectNode facts = MAPPER.createObjectNode();
            facts.put("chapterCount", scaffold.chapterCount());
            facts.put("volumeCount", scaffold.volumeCount());
            facts.set("volumeRanges", MAPPER.valueToTree(scaffold.volumeRanges()));
            facts.set("facts", MAPPER.valueToTree(scaffold.facts()));
            facts.put("beatCount", scaffold.beats() == null ? 0 : scaffold.beats().size());
            root.set("scaffold", facts);
        }
        return root.toString();
    }

    /** 反复生成时禁用的主角名（antiRepro 才有；与 ⑥b HEAD A 同一口径）。 */
    private List<String> bannedNames(RunDO run) {
        if (fingerprintMapper == null || !Boolean.TRUE.equals(run.getAntiRepro())) {
            return List.of();
        }
        return fingerprintMapper.selectList(new LambdaQueryWrapper<com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO>()
                        .eq(com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO::getKind,
                                "PROTAGONIST_NAME"))
                .stream()
                .map(com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO::getValue)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static String weldedText(RunDO run) {
        if (run.getWelded() == null || run.getWelded().isBlank()) {
            return "";
        }
        JsonNode node = readTreeSafe(run.getWelded());
        if (node.isObject()) {
            String text = node.path("text").asText("");
            return text.isBlank() ? node.toString() : text;
        }
        return run.getWelded();
    }

    // ------------------------------------------------------------------
    // 行 ↔ 合同
    // ------------------------------------------------------------------

    private DeriveStoryContractDO persistContract(long runId, StoryContract contract) {
        ObjectNode truth = MAPPER.createObjectNode();
        truth.put("status", "FROZEN");
        truth.put("culpritKey", contract.truth().culpritKey());
        truth.put("culpritName", contract.truth().culpritName());
        truth.put("motive", contract.truth().motive());
        truth.put("method", contract.truth().method());
        truth.put("finalTruth", contract.truth().finalTruth());
        truth.put("finalChoice", contract.truth().finalChoice());
        truth.put("revealChapter", contract.truth().revealChapter());
        truth.put("motiveTag", contract.truth().motiveTag());
        truth.put("methodTag", contract.truth().methodTag());

        ObjectNode slots = MAPPER.createObjectNode();
        slots.put("maxNamedCharacters", StoryContract.MAX_NAMED_CHARACTERS);
        slots.set("cast", MAPPER.valueToTree(contract.cast()));

        ObjectNode events = MAPPER.createObjectNode();
        events.set("volumeTruth", MAPPER.valueToTree(contract.volumeTruth()));

        ObjectNode clues = MAPPER.createObjectNode();
        clues.set("clues", MAPPER.valueToTree(contract.clues()));

        ObjectNode knowledge = MAPPER.createObjectNode();
        knowledge.set("facts", MAPPER.createArrayNode());

        ObjectNode constraints = MAPPER.createObjectNode();
        constraints.set("forbidden", MAPPER.valueToTree(contract.forbidden()));
        constraints.set("threads", MAPPER.valueToTree(contract.threads()));
        constraints.set("volumeTruth", MAPPER.valueToTree(contract.volumeTruth()));
        ObjectNode limits = MAPPER.createObjectNode();
        limits.put("maxNamedCharacters", StoryContract.MAX_NAMED_CHARACTERS);
        limits.put("maxDeaths", 0);
        limits.put("maxIndependentMysteries", 1);
        limits.put("chapterCount", contract.scope().chapterCount());
        limits.put("volumeCount", contract.scope().volumeCount());
        limits.put("chapterAvg", contract.scope().chapterAvg());
        constraints.set("hardLimits", limits);
        constraints.set("scope", MAPPER.valueToTree(contract.scope()));

        DeriveStoryContractDO current = latest(runId);
        int version = current == null ? 1 : current.getVersion() + 1;
        return persist(runId, version, "FROZEN", truth, slots, events, clues, knowledge, constraints);
    }

    /** 行 → 合同（scope 从 constraint_kernel 还原）。 */
    private StoryContract rowToContract(DeriveStoryContractDO row, PlanContracts.ScopeContract scope) {
        ObjectNode slots = parseObject(row.getCharacterSlots());
        JsonNode castNode = slots.path("cast").isArray() ? slots.path("cast")
                : (row.getCharacterSlots() != null && row.getCharacterSlots().trim().startsWith("[")
                ? parseArray(row.getCharacterSlots()) : null);
        List<StoryContract.CharacterSlot> cast = new ArrayList<>();
        if (castNode != null && castNode.isArray()) {
            for (JsonNode node : castNode) {
                cast.add(new StoryContract.CharacterSlot(
                        node.path("key").asText(node.path("characterKey").asText("")),
                        node.path("name").asText(""),
                        node.path("role").asText(""),
                        node.path("gender").asText(""),
                        node.path("identity").asText("")));
            }
        }
        ObjectNode truthNode = parseObject(row.getTruthKernel());
        StoryContract.Truth truth = new StoryContract.Truth(
                truthNode.path("culpritKey").asText(""),
                firstNonBlank(truthNode.path("culpritName").asText(""),
                        truthNode.path("culprit").asText("")),
                truthNode.path("motive").asText(""),
                truthNode.path("method").asText(""),
                truthNode.path("finalTruth").asText(""),
                truthNode.path("finalChoice").asText(""),
                truthNode.path("revealChapter").asInt(0),
                truthNode.path("motiveTag").asText(""),
                truthNode.path("methodTag").asText(""));
        List<StoryContract.Clue> clues = new ArrayList<>();
        JsonNode clueRoot = parseObject(row.getClueRegistry());
        JsonNode clueArr = clueRoot.path("clues").isArray() ? clueRoot.path("clues")
                : (clueRoot.isArray() ? clueRoot : null);
        if (clueArr != null) {
            for (JsonNode node : clueArr) {
                clues.add(new StoryContract.Clue(
                        firstNonBlank(node.path("clueKey").asText(""), node.path("clueId").asText("")),
                        node.path("clue").asText(node.path("text").asText("")),
                        node.path("plantedChapter").isNumber() ? node.path("plantedChapter").asInt()
                                : node.path("planted_chapter").asInt(0),
                        node.path("payoffChapter").isNumber() ? node.path("payoffChapter").asInt()
                                : node.path("payoff_chapter").asInt(0),
                        node.path("ownerKey").asText(node.path("owner").asText(""))));
            }
        }
        ObjectNode constraints = parseObject(row.getConstraintKernel());
        List<StoryContract.ThreadRef> threads = new ArrayList<>();
        JsonNode threadArr = constraints.path("threads");
        if (threadArr.isArray()) {
            for (JsonNode node : threadArr) {
                threads.add(new StoryContract.ThreadRef(
                        node.path("threadKey").asText(""),
                        node.path("kind").asText("CLUE"),
                        node.path("openedVolume").asInt(1),
                        node.path("payoffVolume").asInt(scope.volumeCount()),
                        node.path("ownerKey").asText("")));
            }
        }
        List<String> forbidden = textList(constraints.path("forbidden"));
        List<StoryContract.VolumeTruth> volumeTruth = new ArrayList<>();
        JsonNode slices = parseObject(row.getEventKernel()).path("volumeTruth");
        if (!slices.isArray()) {
            slices = constraints.path("volumeTruth");
        }
        if (slices.isArray()) {
            for (JsonNode node : slices) {
                volumeTruth.add(new StoryContract.VolumeTruth(
                        node.path("volumeNo").asInt(0),
                        node.path("reveal").asText(""),
                        textList(node.path("forbidden"))));
            }
        }
        if (volumeTruth.isEmpty() && scope.volumeCount() == 1 && !truth.finalTruth().isBlank()) {
            volumeTruth.add(new StoryContract.VolumeTruth(1, truth.finalTruth(), forbidden));
        }
        if (threads.isEmpty()) {
            for (StoryContract.Clue clue : clues) {
                threads.add(new StoryContract.ThreadRef(clue.clueKey(), "CLUE", 1,
                        scope.volumeCount(), clue.ownerKey()));
            }
        }
        return new StoryContract(row.getRunId() == null ? 0 : row.getRunId(),
                row.getVersion() == null ? 0 : row.getVersion(),
                row.getStatus() == null ? "FROZEN" : row.getStatus(),
                cast, truth, clues, threads, forbidden, volumeTruth, scope);
    }

    /** 从行的 constraint_kernel 还原规模合同（scope 是合同生成时冻结的边界）。 */
    private PlanContracts.ScopeContract scopeOf(DeriveStoryContractDO row) {
        ObjectNode constraints = parseObject(row.getConstraintKernel());
        JsonNode scopeNode = constraints.path("scope");
        if (scopeNode.isObject()) {
            try {
                return MAPPER.treeToValue(scopeNode, PlanContracts.ScopeContract.class);
            } catch (Exception ignored) {
                // 落到 hardLimits 兜底。
            }
        }
        JsonNode limits = constraints.path("hardLimits");
        int chapterCount = limits.path("chapterCount").asInt(0);
        int volumeCount = limits.path("volumeCount").asInt(0);
        if (chapterCount < 1 || volumeCount < 1) {
            return null;
        }
        PlanContracts.ScopeDecision decision = volumeCount == 1
                ? PlanContracts.ScopeDecision.SINGLE_VOLUME
                : PlanContracts.ScopeDecision.MULTI_VOLUME;
        return new PlanContracts.ScopeContract(decision, volumeCount, chapterCount,
                limits.path("chapterAvg").asInt(0), List.of());
    }

    /** 写 run.step_status 缓存：TRUTH_CONTRACT（兼容读方）+ STORY_CONTRACT（合同快照）。 */
    private void writeContractCaches(long runId, StoryContract contract) {
        if (runMapper == null) {
            return;
        }
        RunDO current = runMapper.selectById(runId);
        if (current == null) {
            return;
        }
        String finalTruth = contract.truth().finalTruth();
        if (finalTruth.isBlank()) {
            return;
        }
        String finalChoice = contract.truth().finalChoice();
        if (finalChoice.isBlank()) {
            finalChoice = "按唯一真相合同完成最终选择：" + finalTruth;
        }
        List<String> threads = new ArrayList<>();
        contract.threads().forEach(thread -> threads.add(thread.threadKey()));
        TruthContract canonical = new TruthContract(
                "T-" + Integer.toHexString((runId + ":" + finalTruth + ":" + finalChoice).hashCode()),
                finalTruth, finalChoice, contract.forbidden(), threads);
        ObjectNode root = current.getStepStatus() == null || current.getStepStatus().isBlank()
                ? MAPPER.createObjectNode()
                : (ObjectNode) readTreeSafe(current.getStepStatus()).deepCopy();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        steps.set("TRUTH_CONTRACT", MAPPER.valueToTree(canonical));
        try {
            steps.set("STORY_CONTRACT", MAPPER.readTree(promptContext(contract)));
        } catch (Exception e) {
            steps.set("STORY_CONTRACT", MAPPER.createObjectNode());
        }
        runMapper.updateStepStatus(runId, root.toString());
    }

    // ------------------------------------------------------------------
    // 既有兼容接口（合同表读方）
    // ------------------------------------------------------------------

    /** 已冻结的真相卡；供 ⑥b HEAD C 只读复用，而不是重新发明一套案情。 */
    public JsonNode frozenTruthCard(RunDO run) {
        StoryContract contract = load(run);
        if (contract == null) {
            return null;
        }
        StoryContract.Truth truth = contract.truth();
        if (!"FROZEN".equals(contract.status()) || truth.finalTruth().isBlank()) {
            return null;
        }
        ObjectNode card = MAPPER.createObjectNode();
        card.put("reveal_chapter", truth.revealChapter());
        ArrayNode clues = card.putArray("clues");
        for (StoryContract.Clue clue : contract.clues()) {
            ObjectNode node = clues.addObject();
            node.put("clueKey", clue.clueKey());
            node.put("clue", clue.clue());
            node.put("planted_chapter", clue.plantedChapter());
            node.put("payoff_chapter", clue.payoffChapter());
            node.put("ownerKey", clue.ownerKey());
        }
        ObjectNode solution = card.putObject("solution");
        solution.put("culprit", truth.culpritName());
        solution.put("motive", truth.motive());
        solution.put("method", truth.method());
        card.put("finalTruth", truth.finalTruth());
        card.put("finalChoice", truth.finalChoice());
        return card;
    }

    /** 规划侧读取已冻结合同；返回 null 表示尚未有可用真相。 */
    public ObjectNode frozenContractNode(RunDO run) {
        StoryContract contract = load(run);
        if (contract == null) {
            return null;
        }
        StoryContract.Truth truth = contract.truth();
        if (!"FROZEN".equals(contract.status()) || truth.finalTruth().isBlank()) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("finalTruth", truth.finalTruth());
        String finalChoice = truth.finalChoice();
        node.put("finalChoice", finalChoice.isBlank()
                ? "按唯一真相合同完成最终选择：" + truth.finalTruth() : finalChoice);
        node.set("forbidden", MAPPER.valueToTree(contract.forbidden()));
        ArrayNode evidence = node.putArray("evidenceThreads");
        contract.threads().forEach(thread -> evidence.add(thread.threadKey()));
        return node;
    }

    /** 合同级校验（P6 前的合同自检）；返回空列表表示可用。 */
    public List<String> validate(RunDO run, NovelDO novel) {
        StoryContract contract = load(run);
        if (contract == null) {
            return List.of("CONTRACT_MISSING：尚未冻结唯一故事合同");
        }
        List<String> errors = StoryContractValidator.validate(contract, contract.scope());
        if (!errors.isEmpty()) {
            return errors;
        }
        if (novel != null) {
            JsonNode cast = readTreeSafe(novel.getStoryAssets()).path("cast");
            if (cast.isArray() && cast.size() > StoryContract.MAX_NAMED_CHARACTERS) {
                return List.of("CONTRACT_CHARACTER_LIMIT：角色数 " + cast.size()
                        + " 超过合同上限 " + StoryContract.MAX_NAMED_CHARACTERS);
            }
        }
        return List.of();
    }

    /**
     * 业务模型（能力包/制度/组织）并入合同快照。
     *
     * <p>只更新 constraint_kernel，不新建版本：合并能力包不是改写真相，合同 version 必须保持稳定
     * （验收口径：一次 P6 回炉期间 derive_story_contract 版本数不增长）。
     * 只有“尚未冻结的推断性真相”才允许被纠正成 NONE；FROZEN 真相永不被清空。
     */
    public void mergeBusinessModel(long runId, String capabilitySnapshot) {
        DeriveStoryContractDO current = latest(runId);
        if (current == null) {
            return;
        }
        ObjectNode truth = parseObject(current.getTruthKernel());
        ObjectNode constraints = parseObject(current.getConstraintKernel());
        JsonNode capabilityNode;
        try {
            capabilityNode = MAPPER.readTree(capabilitySnapshot == null || capabilitySnapshot.isBlank()
                    ? "{}" : capabilitySnapshot);
        } catch (Exception e) {
            capabilityNode = MAPPER.createObjectNode();
        }
        constraints.set("genreCapabilities", capabilityNode.path("genreCapabilities").isArray()
                ? capabilityNode.path("genreCapabilities") : MAPPER.createArrayNode());
        constraints.set("fusionDecisions", capabilityNode.path("fusionDecisions").isArray()
                ? capabilityNode.path("fusionDecisions") : MAPPER.createArrayNode());
        boolean truthChanged = false;
        if (!hasMystery(constraints.toString())
                && "PROVISIONAL".equals(truth.path("status").asText(""))) {
            truth.put("status", "NONE");
            truth.put("finalTruth", "");
            truth.put("finalChoice", "");
            truthChanged = true;
        }
        if (truthChanged) {
            persist(runId, current.getVersion() + 1, current.getStatus(), truth,
                    parseObject(current.getCharacterSlots()), parseObject(current.getEventKernel()),
                    parseObject(current.getClueRegistry()), parseObject(current.getKnowledgeLedger()),
                    constraints);
            return;
        }
        contractMapper.updateKernels(current.getId(), constraints.toString(),
                sha256(truth.toString() + constraints.toString()));
    }

    private DeriveStoryContractDO latest(long runId) {
        return contractMapper.selectOne(new LambdaQueryWrapper<DeriveStoryContractDO>()
                .eq(DeriveStoryContractDO::getRunId, runId)
                .orderByDesc(DeriveStoryContractDO::getVersion)
                .last("LIMIT 1"));
    }

    private DeriveStoryContractDO persist(long runId, int version, String status,
                                           ObjectNode truth, ObjectNode slots, ObjectNode events,
                                           ObjectNode clues, ObjectNode knowledge,
                                           ObjectNode constraints) {
        DeriveStoryContractDO row = new DeriveStoryContractDO();
        row.setRunId(runId);
        row.setVersion(version);
        row.setStatus(status);
        row.setTruthKernel(truth.toString());
        row.setCharacterSlots(slots.toString());
        row.setEventKernel(events.toString());
        row.setClueRegistry(clues.toString());
        row.setKnowledgeLedger(knowledge.toString());
        row.setConstraintKernel(constraints.toString());
        row.setSourceHash(sha256(truth.toString() + slots.toString() + events.toString()
                + clues.toString() + knowledge.toString() + constraints.toString()));
        contractMapper.insertFull(row);
        return row;
    }

    private static boolean hasMystery(String constraintJson) {
        JsonNode node = parseObject(constraintJson);
        JsonNode arr = node.path("genreCapabilities");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                if ("MYSTERY".equals(item.asText(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            if (node != null && node.isTextual() && !node.asText("").isBlank()) {
                return List.of(node.asText("").trim());
            }
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

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private static ObjectNode parseObject(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.isObject() ? (ObjectNode) node : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static JsonNode parseArray(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.isArray() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode readTreeSafe(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "";
        }
    }
}
