package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.contract.StoryContractValidator;
import com.zzdzz.novelgen.derive.mapper.DeriveGateReviewMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationEvidenceMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.derive.plan.PlanJsonViews;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionDecision;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionResult;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditIssue;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditReport;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.derive.genre.GenreGateAuditor;
import com.zzdzz.novelgen.derive.genre.GenreProfile;
import com.zzdzz.novelgen.derive.genre.GenreProfileRegistry;
import com.zzdzz.novelgen.derive.genre.MysteryGateAuditor;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.derive.genre.RomanceProfile;
import com.zzdzz.novelgen.derive.genre.GenreContract;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import com.zzdzz.novelgen.derive.story.StoryKernelAssembler;
import com.zzdzz.novelgen.derive.story.StoryObligationEvidence;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.derive.story.StoryThread;
import com.zzdzz.novelgen.derive.story.WordBudgetGuard;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveGateReviewDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationEvidenceDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import com.zzdzz.novelgen.model.vo.derive.GateCriterionVO;
import com.zzdzz.novelgen.model.vo.derive.GateVO;
import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P6 立项闸（⑥b 全窗完成后、⑦ 开写前的 LLM 立项评审，六判据）。
 *
 * <p>模型只输出六项结构化审稿，不再拥有终态决定权。Java 按严重度统一裁决：
 * 只有 ADVISORY 时放行；存在 HARD/FIXABLE 则前两轮回炉，第三轮才熔断。
 * 评审记录落 run.step_status.steps.GATE（L2 层，无 DDL）；⑦ writeNext 要求 GATE=PASS 才开写。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveGateService {

    public static final String NODE_GATE = "derive_gate";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();
    static final List<String> CRITERION_KEYS = List.of(
            "single_rule", "budget", "motive_chain", "closure", "function_lock", "motif_dedup");
    private static final Set<String> SEVERITIES = Set.of("HARD", "FIXABLE", "ADVISORY");
    private static final Pattern PLAN_VOLUME_TARGET =
            Pattern.compile("PLAN_VOLUME:[1-9]\\d*");

    static final String GATE_PROMPT = """
            你是小说大纲立项评审员。输入含：类别/产品形态/字数带/结局形状/主线、人物表（cast 含 persona）、
            真相卡（若有）、brief、全书章纲（每章 no/title/outline）、拍清单（seq/拍型/归属章/状态/filled 情节点）、
            母题黑名单（跨稿复读母题，命中要换写）。
            只依据给出材料评审。TruthContract 是唯一真相合同；真相卡、卷合同、主线或章纲与它冲突时，必须判为合同冲突，不得把两套案情当成同一个故事。你的职责是“诊断”，不是决定放行或熔断；最终动作由系统按结构化结果计算。
            合同与章级日程是只读事实：不得在 directive 中要求移动线索章号、改真凶/动机/手法、增删角色；
            揭示章的 motiveTag 与 culpritName 是合同事实，不得在 directive 中改写；
            directive 只能描述“重写第 X 章的哪些句子”。
            六判据逐项输出 pass、severity、note、issues、repairTarget：
            1. 规则单轨：真相卡存在时，诡计/手法层只允许一套可理性化规则——超自然只能做氛围，
               不得进入解释通道；两套规则并存判 fail；
            2. 体量预算：主线 ≤1 条；支线（独立人物线/赌局/复仇/私奔类）≤1（中篇）或 ≤2（长篇）；
               正文死亡/命案 ≤4 起；机关+死亡+揭穿类高密度事件中篇 ≤3、长篇 ≤5；
               拍总量与字数带折算明显超载（事件拍占绝对多数且支线塞不进章容量）判 fail；
            3. 动机链：主要人物每次立场/阵营变化在章纲里能找到 ≥1 个铺垫章；
               连续翻转无中间台阶、反派动机一句带过判 fail；
            4. 闭合：真相卡存在时，结局章（reveal_chapter 及后 1~2 章）章纲必须写明
               culprit/motive/method 三要素的兑现动作；先写死案情、主题余韵殿后合法，
               案情悬空/替身未决/真凶未点名判 fail；
            5. 职能锁定：主视角/侦探的行为与 persona 方法论一致（如"只记不断"者不得
               亲自潜入/肉搏/夺人质）；侦探与被卷入者职能重叠到读者会认错主视角判 fail；
            6. 母题查重：章纲与母题黑名单逐条比对，命中 ≥3 处（或核心转折母题命中 ≥2 处）
               则 fail——要求 directive 给出替换方向（换动机载体/换收尾动作/换嫁祸手段）。

            severity 口径：
            - pass=true 时 severity 固定 ADVISORY、repairTarget 固定 NONE；note 写通过依据；
            - fail 且是局部可改问题时 severity=FIXABLE；
            - fail 且存在根本矛盾、规则无解、人物状态无法闭合时 severity=HARD；
            - 只有真正会让当前计划写出硬伤时才判 fail；仅仅“还能更好、可以更精彩”要 pass，
              在 note 里提醒，不要把审美偏好升级成阻断项。

            repairTarget 只许四类：
            - SCALE：卷数/章数/字数规模本身不成立；
            - SPINE：主谜、最终真相、真相阶梯、角色功能弧光或全局线程合同有根本错误；
            - PLAN_VOLUME:n：第 n 卷局部问题、章功能、拍路由、角色高光或卷闭合有错；
            - DRAFT：仅章纲文字与 filled 情节点的局部表达需要重写。

            issues 必须引用可核对事实（章号、角色名、拍号或规则），禁止“逻辑不够好”这类空话。
            输出 JSON 对象：
            {"criteria":{
              "single_rule":{"pass":true,"severity":"ADVISORY","note":"只采用一套可解释规则",
                "issues":[],"repairTarget":"NONE"},
              "budget":{"pass":false,"severity":"FIXABLE","note":"第2卷事件过密",
                "issues":["第18-21章连续4章命案，超出中篇预算"],"repairTarget":"PLAN_VOLUME:2"},
              "motive_chain":{"pass":true,"severity":"ADVISORY","note":"阵营变化有铺垫",
                "issues":[],"repairTarget":"NONE"},
              "closure":{"pass":true,"severity":"ADVISORY","note":"真相三要素有兑现",
                "issues":[],"repairTarget":"NONE"},
              "function_lock":{"pass":true,"severity":"ADVISORY","note":"职能未串线",
                "issues":[],"repairTarget":"NONE"},
              "motif_dedup":{"pass":true,"severity":"ADVISORY","note":"未命中黑名单",
                "issues":[],"repairTarget":"NONE"}
             },
             "issues":["汇总需要处理的硬问题，无则空数组"],
             "directive":"给出可执行减法指令（砍什么/留什么/改什么），无则空串"}
            只输出 JSON 对象，无其他文字。
            """;

    record GateReview(
            List<GateCriterionVO> criteria,
            List<String> issues,
            String directive) {
    }

    record GateDecision(
            String verdict,
            String action,
            String repairTarget,
            List<String> issues,
            List<GateCriterionVO> criteria) {
    }

    record GateTruth(
            DeriveTruthContractService.TruthResolution resolution,
            JsonNode volumes,
            StoryContract contract,
            List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule) {
        GateTruth(DeriveTruthContractService.TruthResolution resolution, JsonNode volumes,
                  StoryContract contract) {
            this(resolution, volumes, contract, List.of());
        }
    }

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final WordlistMapper wordlistMapper;
    private final DeriveTruthContractService truthContractService;
    private final LlmJson llmJson;
    @Autowired(required = false)
    private DerivePlanValidationService planValidationService;
    @Autowired(required = false)
    private DeriveContractService contractService;
    @Autowired(required = false)
    private DeriveCharacterBusinessModelService businessModelService;
    @Autowired(required = false)
    private VolumePlanMapper volumePlanMapper;
    @Autowired(required = false)
    private CharacterPlanMapper characterPlanMapper;
    @Autowired(required = false)
    private StoryThreadMapper storyThreadMapper;
    @Autowired(required = false)
    private DeriveGateReviewMapper gateReviewMapper;
    @Autowired(required = false)
    private DeriveStoryObligationMapper obligationMapper;
    @Autowired(required = false)
    private DeriveStoryObligationEvidenceMapper evidenceMapper;
    @Autowired(required = false)
    private GenreProfileRegistry genreProfileRegistry;
    @Autowired(required = false)
    private GenreGateAuditor genreGateAuditor;
    @Autowired(required = false)
    private MysteryGateAuditor mysteryGateAuditor;
    /** 章级日程（合同投影）：P6 只读；缺失/版本不一致即 CONTRACT_INVALID 终态。 */
    @Autowired(required = false)
    private DeriveChapterScheduleService scheduleService;
    /** compiler-v1 快照存在时，/gate 只读兼容准入报告，不再调用自由模型改写结构。 */
    @Autowired(required = false)
    private PlanSnapshotService planSnapshotService;
    /** 旧 PLAN_V2 已完成但尚无 active snapshot 时，桥接成 compiler-v1 计划。 */
    @Autowired(required = false)
    private DerivePlanSnapshotBridge planSnapshotBridge;

    /** ⑥b→⑦ 之间跑一次立项评审。 */
    public GateVO gate(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"DRAFTING".equals(run.getStatus()) || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "run 应处于 DRAFTING（⑥b 已完成），当前 " + run.getStatus());
        }
        if (run.getActivePlanRevision() != null && planSnapshotService != null) {
            return gateFromAdmission(run, gateRound(run.getStepStatus()) + 1);
        }
        long novelId = run.getNovelId();
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        long noOutline = chapters.stream()
                .filter(c -> c.getDraftOutline() == null || c.getDraftOutline().isBlank()).count();
        if (chapters.isEmpty() || noOutline > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥b 未完成：无纲章 " + noOutline + "/" + chapters.size() + "，先循环 POST /draft 至 allDone");
        }
        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByAsc(BeatInstanceDO::getSeq));
        NovelDO novel = novelMapper.selectById(novelId);

        int prevRound = gateRound(run.getStepStatus());
        int round = prevRound + 1;

        List<String> motifs = wordlistMapper.selectList(new LambdaQueryWrapper<WordlistDO>()
                        .eq(WordlistDO::getKind, "BLACKLIST")
                        .eq(WordlistDO::getGrp, "MOTIF"))
                .stream().map(WordlistDO::getWord).filter(w -> w != null && !w.isBlank()).toList();
        GateTruth gateTruth = resolveGateTruth(run, novel);
        // 合同级漂移是上游节点违反冻结合同，属终态失败：不进 LLM 评审，也不给回炉放大器。
        List<String> contractIssues = contractIssues(run, gateTruth, chapters, beats);
        if (!contractIssues.isEmpty()) {
            String message = "CONTRACT_INVALID：" + String.join("；", contractIssues);
            run.setStepStatus(withGate(run.getStepStatus(), "FAILED", round, contractIssues,
                    message, "CONTRACT_INVALID"));
            runMapper.updateStepStatus(runId, run.getStepStatus());
            persistGateReview(runId, round,
                    new GateDecision("REJECT", "FAILED", "CONTRACT_INVALID",
                            contractIssues, List.of()), message);
            runMapper.updateStatus(runId, "FAILED", null);
            log.warn("P6 合同预检拦截（终态 FAILED）：runId={} issues={}", runId, contractIssues);
            return new GateVO(runId, novelId, "REJECT", "FAILED", round, contractIssues,
                    message, "CONTRACT_INVALID", List.of());
        }
        String user = buildUser(run, novel, chapters, beats, motifs, gateTruth);

        List<String> preflight = new java.util.ArrayList<>(preflightIssues(chapters, beats));
        preflight.addAll(revealNameIssues(chapters, gateTruth.schedule(), gateTruth.contract()));
        if (businessModelService != null) {
            preflight.addAll(businessModelService.validateStatic(runId));
            if (!preflight.isEmpty()) {
                log.warn("P6-A 状态一致性检查拦截：runId={} issues={}", runId, preflight);
            }
        }
        GateReview review;
        if (!preflight.isEmpty()) {
            review = new GateReview(List.of(), preflight, "");
            log.warn("P6 结构预检拦截：runId={} issues={}", runId, preflight);
        } else {
        DeriveActivity.begin(runId, "P6 立项闸评审（六判据）");
        try {
            review = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_GATE, novelId, null, runId,
                            List.of(LlmPort.Message.system(GATE_PROMPT), LlmPort.Message.user(user)), 0.2),
                    DeriveGateService::parseGate, 3, (think, piece) -> DeriveActivity.touch(runId, piece));
        } finally {
            DeriveActivity.end(runId);
        }
        }

        PlanValidationVO planCheck = planValidationService == null
                ? null : planValidationService.validateBook(runId);
        GateDecision decision = decideGate(review, planCheck, prevRound);

        String directive = review.directive() == null ? "" : review.directive().trim();
        if (directive.isEmpty() && "REWORK".equals(decision.action())) {
            directive = buildDirective(decision.issues(), decision.repairTarget());
        }
        run.setStepStatus(withGate(
                run.getStepStatus(), decision.action(), round, decision.issues(),
                directive, decision.repairTarget(), decision.criteria()));
        runMapper.updateStepStatus(runId, run.getStepStatus());
        persistGateReview(runId, round, decision, directive);
        persistTruthContract(runId, gateTruth.resolution().contract());
        if ("REWORK".equals(decision.action())) {
            runMapper.updateStatus(runId, "REWORK", null);
        } else if ("FAILED".equals(decision.action())) {
            runMapper.updateStatus(runId, "FAILED", null);
        }
        log.info("P6立项闸：runId={} round={} verdict={} action={} issues={} directive={}",
                runId, round, decision.verdict(), decision.action(), decision.issues().size(),
                directive.length() > 80 ? directive.substring(0, 80) + "…" : directive);
        return new GateVO(runId, novelId, decision.verdict(), decision.action(), round,
                decision.issues(), directive, decision.repairTarget(),
                decision.criteria());
    }

    /**
     * direct 模式的确定性审计：不调用 LLM 立项模型，但合同、义务、闭合与预算问题仍必须拦截。
     */
    public List<String> deterministicAudit(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (obligationMapper == null || storyThreadMapper == null) {
            return List.of();
        }
        return genreIssues(run);
    }


    /** /gate 兼容适配：只读 ADMITTED 快照，按 admission 决策映射旧 GO/REWORK/REJECT。 */
    private GateVO gateFromAdmission(RunDO run, int round) {
        DerivePlanSnapshotDO snapshot = planSnapshotService.requireActive(run.getId());
        PlanAuditReport report;
        try {
            report = STATIC_MAPPER.readValue(
                    snapshot.getAdmissionReport() == null ? "{}" : snapshot.getAdmissionReport(),
                    PlanAuditReport.class);
        } catch (Exception e) {
            report = new PlanAuditReport(List.of());
        }
        PlanAdmissionDecision decision = report.admitted()
                ? PlanAdmissionDecision.ADMIT
                : PlanAdmissionDecision.REJECT;
        GateDecision gateDecision = gateDecisionFor(decision, report.issues(),
                gateRound(run.getStepStatus()));
        List<String> issues = report.issues().stream()
                .map(issue -> issue.code() + "：" + issue.message())
                .toList();
        String directive = "按结构化计划审计报告修复；不得改写合同、日程或计划 revision";
        run.setStepStatus(withGate(run.getStepStatus(), gateDecision.action(), round,
                issues, directive, gateDecision.repairTarget()));
        runMapper.updateStepStatus(run.getId(), run.getStepStatus());
        persistGateReview(run.getId(), round, gateDecision, directive);
        if ("REWORK".equals(gateDecision.action())) {
            runMapper.updateStatus(run.getId(), "REWORK", null);
        } else if ("FAILED".equals(gateDecision.action())) {
            runMapper.updateStatus(run.getId(), "FAILED", null);
        }
        return new GateVO(run.getId(), run.getNovelId(), gateDecision.verdict(),
                gateDecision.action(), round, issues, directive,
                gateDecision.repairTarget(), List.of());
    }

    /** admission 决策到旧 P6 动作的唯一映射；没有自由模型 directive 通道。 */
    static GateDecision gateDecisionFor(PlanAdmissionDecision decision,
                                        List<PlanAuditIssue> issues,
                                        int prevRound) {
        List<String> values = issues == null ? List.of() : issues.stream()
                .map(issue -> issue.code() + "：" + issue.message())
                .toList();
        return switch (decision) {
            case ADMIT -> new GateDecision("GO", "PASS", "NONE",
                    new ArrayList<>(values), List.of());
            case REPAIR -> prevRound >= 5
                    ? new GateDecision("REJECT", "FAILED", "PLAN_VOLUME",
                    new ArrayList<>(values), List.of())
                    : new GateDecision("REWORK", "REWORK", "PLAN_VOLUME",
                    new ArrayList<>(values), List.of());
            case REJECT -> new GateDecision("REJECT", "FAILED", "CONTRACT",
                    new ArrayList<>(values), List.of());
        };
    }
    // ---- 输入构造 ----

    /**
     * P6 的真相输入只有一份：冻结合同（唯一事实源）。合同存在时不再合并 STORY_SPINE、
     * 旧卷合同或焊接到的主线——那些正是“两套案情”的来源。
     */
    GateTruth resolveGateTruth(RunDO run, NovelDO novel) {
        JsonNode volumes = volumePlanMapper == null
                ? STATIC_MAPPER.createArrayNode()
                : PlanJsonViews.volumes(volumePlanMapper.selectList(
                        new LambdaQueryWrapper<VolumePlanDO>()
                                .eq(VolumePlanDO::getRunId, run.getId())
                                .orderByAsc(VolumePlanDO::getVolumeNo)));
        // 日程只读加载（不 require）：缺失/过期由 contractIssues 判 CONTRACT_INVALID 终态，
        // 避免抛裸异常让 auto-flow 只记 lastError 就去停下。
        List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule =
                scheduleService == null ? List.of() : scheduleService.load(run);
        if (!schedule.isEmpty() && scheduleService != null) {
            try {
                scheduleService.assertCurrent(run);
            } catch (BizException stale) {
                log.warn("P6 读取到期日程（将在合同预检判终态）：runId={} 原因={}",
                        run.getId(), stale.getMessage());
            }
        }
        if (contractService != null) {
            StoryContract contract = contractService.load(run);
            if (contract != null && !contract.truth().finalTruth().isBlank()) {
                String finalChoice = contract.truth().finalChoice();
                if (finalChoice == null || finalChoice.isBlank()) {
                    finalChoice = "按唯一真相合同完成最终选择：" + contract.truth().finalTruth();
                }
                List<String> threads = new ArrayList<>();
                contract.threads().forEach(thread -> threads.add(thread.threadKey()));
                String truthId = "T-" + Integer.toHexString((run.getId() + ":"
                        + contract.truth().finalTruth() + ":" + finalChoice).hashCode());
                com.zzdzz.novelgen.derive.contract.TruthContract canonical =
                        new com.zzdzz.novelgen.derive.contract.TruthContract(truthId,
                                contract.truth().finalTruth(), finalChoice,
                                contract.forbidden(), threads);
                return new GateTruth(new DeriveTruthContractService.TruthResolution(
                        canonical, List.of()), volumes, contract, schedule);
            }
        }
        JsonNode assets = DeriveDraftService.readTreeSafe(novel.getStoryAssets());
        JsonNode spine = DeriveDraftService.readTreeSafe(run.getStepStatus())
                .path("steps").path("STORY_SPINE");
        return new GateTruth(truthContractService.resolve(
                run.getId(), spine, assets.path("truth"), volumes), volumes, null, schedule);
    }
    private void persistTruthContract(long runId,
                                      com.zzdzz.novelgen.derive.contract.TruthContract contract) {
        RunDO current = runMapper.selectById(runId);
        if (current == null) {
            return;
        }
        ObjectNode root = stepsRoot(current.getStepStatus());
        root.with("steps").set("TRUTH_CONTRACT", STATIC_MAPPER.valueToTree(contract));
        runMapper.updateStepStatus(runId, root.toString());
    }

    /**
     * 合同级预检：角色计划/卷合同/章纲与冻结合同的结构比对（cast、线程 key、揭示章泄底）。
     * 只判结构漂移，不判文笔；命中即 CONTRACT_INVALID（终态）。
     */
    List<String> contractIssues(RunDO run, GateTruth gateTruth, List<ChapterDO> chapters) {
        return contractIssues(run, gateTruth, chapters, List.of());
    }

    /**
     * 合同级预检：角色计划/卷合同/章纲/拍/日程与冻结合同的结构比对。
     * 只判结构漂移，不判文笔；命中即 CONTRACT_INVALID（终态）。
     */
    List<String> contractIssues(RunDO run, GateTruth gateTruth, List<ChapterDO> chapters,
                                List<BeatInstanceDO> beats) {
        StoryContract contract = gateTruth.contract();
        if (contract == null) {
            return List.of();
        }
        List<PlanContracts.VolumeContract> volumes = new ArrayList<>();
        if (volumePlanMapper != null) {
            for (VolumePlanDO row : volumePlanMapper.selectList(
                    new LambdaQueryWrapper<VolumePlanDO>().eq(VolumePlanDO::getRunId, run.getId()))) {
                try {
                    volumes.add(STATIC_MAPPER.readValue(row.getContract(),
                            PlanContracts.VolumeContract.class));
                } catch (Exception e) {
                    log.warn("卷合同 JSON 损坏，跳过合同预检：runId={} rowId={}", run.getId(), row.getId());
                }
            }
        }
        List<PlanContracts.CharacterContract> characters = new ArrayList<>();
        if (characterPlanMapper != null) {
            for (CharacterPlanDO row : characterPlanMapper.selectList(
                    new LambdaQueryWrapper<CharacterPlanDO>().eq(CharacterPlanDO::getRunId, run.getId()))) {
                try {
                    characters.add(STATIC_MAPPER.readValue(row.getContract(),
                            PlanContracts.CharacterContract.class));
                } catch (Exception e) {
                    log.warn("角色合同 JSON 损坏，跳过合同预检：runId={} rowId={}", run.getId(), row.getId());
                }
            }
        }
        List<String> issues = new ArrayList<>(
                StoryContractValidator.validateAgainstPlan(contract, volumes, characters, chapters));
        issues.addAll(scheduleIssues(run, gateTruth.schedule(), beats));
        issues.addAll(genreIssues(run));
        return issues;
    }

    List<String> genreIssues(RunDO run) {
        List<String> collected = new ArrayList<>(budgetIssues(run));
        if (obligationMapper == null) {
            return collected;
        }
        List<StoryObligation> obligations = new ArrayList<>();
        for (DeriveStoryObligationDO row : obligationMapper.selectByRun(run.getId())) {
            obligations.add(new StoryObligation(row.getObligationKey(),
                    row.getChapterNo() == null ? 0 : row.getChapterNo(), row.getKind(),
                    row.getTargetKey(), Boolean.TRUE.equals(row.getRequired()),
                    Boolean.TRUE.equals(row.getForbidden()), row.getSeverity(),
                    row.getSourceContract(), readJsonObject(row.getPayload())));
        }
        if (obligations.isEmpty()) {
            return collected;
        }
        List<StoryThread> threads = new ArrayList<>();
        if (storyThreadMapper != null) {
            for (var row : storyThreadMapper.selectList(new LambdaQueryWrapper<StoryThreadDO>()
                    .eq(StoryThreadDO::getRunId, run.getId()))) {
                threads.add(new StoryThread(row.getThreadKey(), row.getKind(), row.getTitle(),
                        row.getPlantChapter(), row.getStatus(), row.getOwnerCharacterKey(),
                        row.getPayoffChapter(), Map.of()));
            }
        }
        StoryEvidenceIndex evidenceIndex = evidenceIndex(run.getId());
        StoryContract contract = null;
        if (contractService != null) {
            try {
                contract = contractService.require(run);
            } catch (RuntimeException ignored) {
                contract = null;
            }
        }
        StoryKernel kernel = new StoryKernelAssembler().assemble(run, contract, List.of(),
                List.of(), List.of(), threads, obligations, evidenceIndex);
        GenreProfile profile = profile(run);
        List<com.zzdzz.novelgen.derive.plan.PlanContracts.PlanIssue> issues =
                "MYSTERY".equals(profile.genre()) && mysteryGateAuditor != null
                        ? mysteryGateAuditor.audit(kernel, profile)
                        : auditGenreGate(run, profile, kernel, evidenceIndex);
        collected.addAll(issues.stream()
                .map(issue -> issue.code() + "：" + issue.message())
                .toList());
        return collected;
    }

    /** 确定性字数预算审计：direct 与完整 P6 共用，超限不得靠 LLM 审稿兜底。 */
    private List<String> budgetIssues(RunDO run) {
        Integer wordsMin = run.getParamWordsMin();
        Integer wordsMax = run.getParamWordsMax();
        if (chapterMapper == null || wordsMin == null || wordsMax == null
                || wordsMin <= 0 || wordsMax < wordsMin) {
            return List.of();
        }
        List<ChapterDO> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<ChapterDO>()
                        .eq(ChapterDO::getNovelId, run.getNovelId())
                        .orderByAsc(ChapterDO::getChapterNo));
        if (chapters.isEmpty()) {
            return List.of();
        }
        int total = 0;
        int remaining = 0;
        int remainingBudget = 0;
        for (ChapterDO chapter : chapters) {
            String content = chapter.getContent();
            if (content != null && !content.isBlank()) {
                total += chapter.getCharCount() == null ? content.length()
                        : chapter.getCharCount();
            } else {
                remaining++;
                remainingBudget += chapter.getBudget() == null ? 0 : chapter.getBudget();
            }
        }
        int chapterAvg = remaining == 0 ? 0 : remainingBudget / remaining;
        WordBudgetGuard.BudgetDecision decision = WordBudgetGuard.check(
                total, 0, remaining, chapterAvg, wordsMin, wordsMax);
        if (decision.ok()) {
            return List.of();
        }
        return List.of(decision.code() + "：totalCharCount=" + total
                + " remainingChapters=" + remaining
                + " projectedMax=" + decision.projectedMax()
                + " wordsMax=" + wordsMax
                + " repairTarget=" + decision.repairTarget());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<com.zzdzz.novelgen.derive.plan.PlanContracts.PlanIssue> auditGenreGate(
            RunDO run, GenreProfile profile, StoryKernel kernel, StoryEvidenceIndex evidenceIndex) {
        GenreContract contract = contractService == null ? null : contractService.loadGenreContract(run);
        if (contract != null) {
            return profile.auditGate(contract, kernel, evidenceIndex);
        }
        return genreGateAuditor == null ? List.of() : genreGateAuditor.audit(kernel, profile);
    }

    private StoryEvidenceIndex evidenceIndex(long runId) {
        StoryEvidenceIndex.Builder builder = StoryEvidenceIndex.builder();
        if (evidenceMapper == null) {
            return builder.build();
        }
        for (DeriveStoryObligationEvidenceDO row : evidenceMapper.selectByRun(runId)) {
            builder.addObligationEvidence(new StoryObligationEvidence(
                    row.getObligationKey(),
                    row.getChapterNo() == null ? 0 : row.getChapterNo(),
                    row.getEvidenceKind(),
                    row.getEventKey() == null ? "DB:" + row.getId() : row.getEventKey(),
                    row.getBeatSeq(),
                    readJsonStringList(row.getEntityKeys()),
                    readJsonStringList(row.getThreadKeys()),
                    row.getFact(),
                    row.getSource(),
                    row.getConfidence() == null ? 1.0 : row.getConfidence()));
        }
        return builder.build();
    }

    private Map<String, Object> readJsonObject(String json) {
        try {
            JsonNode node = STATIC_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!node.isObject()) {
                return Map.of();
            }
            return STATIC_MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> readJsonStringList(String json) {
        try {
            JsonNode node = STATIC_MAPPER.readTree(json == null || json.isBlank() ? "[]" : json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && !item.asText("").isBlank()) {
                    out.add(item.asText(""));
                }
            });
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private GenreProfile profile(RunDO run) {
        GenreProfileRegistry registry = genreProfileRegistry;
        if (registry == null) {
            registry = new GenreProfileRegistry(List.of(new MysteryProfile(), new RomanceProfile()));
        }
        return registry.require(run.getGenre() == null || run.getGenre().isBlank()
                ? "MYSTERY" : run.getGenre());
    }

    /** 日程级预检：缺失/过期、拍引用日程外线索、缺埋设/兑现/揭示拍——都是上游违约（终态）。 */
    private List<String> scheduleIssues(RunDO run,
                                        List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule,
                                        List<BeatInstanceDO> beats) {
        List<String> issues = new ArrayList<>();
        if (schedule == null || schedule.isEmpty()) {
            issues.add("CONTRACT_SCHEDULE_MISSING：章级日程缺失（SCHEDULE 单元未完成）");
            return issues;
        }
        if (scheduleService != null) {
            try {
                scheduleService.assertCurrent(run);
            } catch (BizException stale) {
                issues.add("CONTRACT_SCHEDULE_STALE：" + stale.getMessage());
            }
        }
        Map<String, int[]> windows = DeriveBeatRouteService.clueChapters(schedule);
        Map<Integer, List<BeatInstanceDO>> byChapter = new HashMap<>();
        for (BeatInstanceDO beat : beats) {
            if ("SKIPPED".equals(beat.getStatus()) || beat.getChapterNo() == null) {
                continue;
            }
            byChapter.computeIfAbsent(beat.getChapterNo(), key -> new ArrayList<>()).add(beat);
        }
        for (com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry entry : schedule) {
            List<BeatInstanceDO> chapterBeats =
                    byChapter.getOrDefault(entry.chapterNo(), List.of());
            for (String key : entry.mustPlant()) {
                if (countBeat(chapterBeats, "CLUE_PLANT", key) != 1) {
                    issues.add("CONTRACT_CLUE_PLANT_MISSING：第" + entry.chapterNo()
                            + "章缺 1 个 CLUE_PLANT 拍引用 " + key);
                }
            }
            for (String key : entry.mustPayoff()) {
                if (countBeat(chapterBeats, "CLUE_PAYOFF", key) != 1) {
                    issues.add("CONTRACT_CLUE_PAYOFF_MISSING：第" + entry.chapterNo()
                            + "章缺 1 个 CLUE_PAYOFF 拍引用 " + key);
                }
            }
            if (entry.reveal() && entry.mustPayoff().isEmpty()) {
                issues.add("CONTRACT_REVEAL_NO_PAYOFF：日程揭示章第" + entry.chapterNo()
                        + "章没有任何 clue payoff");
            }
            if (entry.reveal() && countBeat(chapterBeats, "FINAL_REVEAL", null) < 1) {
                issues.add("CONTRACT_REVEAL_MISSING：揭示章第" + entry.chapterNo()
                        + "章缺 FINAL_REVEAL 拍");
            }
        }
        for (BeatInstanceDO beat : beats) {
            if ("SKIPPED".equals(beat.getStatus()) || beat.getChapterNo() == null) {
                continue;
            }
            for (String ref : threadRefs(beat.getRouteContract())) {
                int[] window = windows.get(ref);
                if (window == null) {
                    continue;
                }
                if (!DeriveBeatRouteService.allowedClueRef(window, beat.getChapterNo(),
                        roleOf(beat.getBeatRole()))) {
                    issues.add("CONTRACT_CLUE_STRAY：拍 seq=" + beat.getSeq() + " 在第"
                            + beat.getChapterNo() + "章（" + beat.getBeatRole()
                            + "）引用了日程外的线索 " + ref);
                }
            }
        }
        return issues;
    }

    private static long countBeat(List<BeatInstanceDO> beats, String role, String clueKey) {
        return beats.stream()
                .filter(beat -> role.equals(beat.getBeatRole()))
                .filter(beat -> clueKey == null
                        || threadRefs(beat.getRouteContract()).contains(clueKey))
                .count();
    }

    /** route_contract.threadRefs（解析失败按空处理）。 */
    static List<String> threadRefs(String routeContract) {
        List<String> out = new ArrayList<>();
        JsonNode node = DeriveDraftService.readTreeSafe(routeContract);
        node.path("threadRefs").forEach(item -> {
            String ref = item.asText("").trim();
            if (!ref.isEmpty()) {
                out.add(ref);
            }
        });
        return out;
    }

    private static PlanContracts.BeatRole roleOf(String role) {
        try {
            return role == null ? null : PlanContracts.BeatRole.valueOf(role.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 揭示章及其后必须逐字写出真凶姓名（章纲文字问题，DRAFT 目标，允许重写修复）。 */
    static List<String> revealNameIssues(List<ChapterDO> chapters,
                                         List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule,
                                         StoryContract contract) {
        List<String> issues = new ArrayList<>();
        if (contract == null || contract.truth() == null || schedule == null || schedule.isEmpty()) {
            return issues;
        }
        String culprit = contract.truth().culpritName();
        String motiveTag = contract.truth().motiveTag() == null
                ? "" : contract.truth().motiveTag().trim();
        int reveal = contract.truth().revealChapter();
        if ((culprit == null || culprit.isBlank()) && motiveTag.isBlank()) {
            return issues;
        }
        if (reveal < 1) {
            return issues;
        }
        for (ChapterDO chapter : chapters) {
            if (chapter.getChapterNo() < reveal) {
                continue;
            }
            String outline = chapter.getDraftOutline() == null ? "" : chapter.getDraftOutline();
            if (!culprit.isBlank() && !outline.contains(culprit)) {
                issues.add("REVEAL_NAME_MISSING：第" + chapter.getChapterNo()
                        + "章（揭示章 " + reveal + " 及之后）章纲未写出真凶「" + culprit + "」");
            }
            if (!motiveTag.isBlank() && !outline.contains(motiveTag)) {
                issues.add("REVEAL_MOTIVE_TAG_MISSING：第" + chapter.getChapterNo()
                        + "章（揭示章 " + reveal + " 及之后）章纲未逐字写出 motiveTag「"
                        + motiveTag + "」");
            }
        }
        return issues;
    }
    String buildUser(RunDO run, NovelDO novel, List<ChapterDO> chapters,
                     List<BeatInstanceDO> beats, List<String> motifs) {
        return buildUser(run, novel, chapters, beats, motifs, resolveGateTruth(run, novel));
    }

    String buildUser(RunDO run, NovelDO novel, List<ChapterDO> chapters,
                     List<BeatInstanceDO> beats, List<String> motifs,
                     GateTruth gateTruth) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.set("类别", DeriveDraftService.readTreeSafe(run.getParamTags()));
        root.put("产品形态", run.getParamForm());
        root.put("字数带", run.getParamWordsMin() + "~" + run.getParamWordsMax());
        root.put("结局形状", run.getParamOutcome());
        String canonicalMainline = gateTruth.resolution().contract().finalTruth();
        if ((canonicalMainline == null || canonicalMainline.isBlank()) && gateTruth.contract() == null) {
            canonicalMainline = run.getWelded() == null ? "" : run.getWelded();
        }
        root.put("主线", canonicalMainline == null ? "" : canonicalMainline);
        if (gateTruth.contract() != null) {
            root.set("FROZEN_CONTRACT（唯一合同：cast/真相/线索/线程/禁令，禁止改写）",
                    DeriveDraftService.readTreeSafe(contractService.promptContext(gateTruth.contract())));
        }
        JsonNode assets = DeriveDraftService.readTreeSafe(novel.getStoryAssets());
        root.set("人物表", assets.path("cast"));
        root.put("protagonist", assets.path("protagonist").asText(""));
        if (assets.path("truth").isObject()) {
            root.set("真相卡", assets.path("truth"));
        }
        root.set("brief", assets.path("brief"));
        JsonNode spine = DeriveDraftService.readTreeSafe(run.getStepStatus())
                .path("steps").path("STORY_SPINE");
        JsonNode volumes = gateTruth.volumes();
        var truthResolution = gateTruth.resolution();
        root.set("TruthContract", STATIC_MAPPER.valueToTree(truthResolution.contract()));
        if (!truthResolution.conflicts().isEmpty()) {
            root.set("truthConflicts", STATIC_MAPPER.valueToTree(truthResolution.conflicts()));
        }
        if (volumePlanMapper != null) {
            root.set("卷合同", PlanJsonViews.volumes(volumePlanMapper.selectList(
                    new LambdaQueryWrapper<VolumePlanDO>()
                            .eq(VolumePlanDO::getRunId, run.getId())
                            .orderByAsc(VolumePlanDO::getVolumeNo))));
        }
        if (characterPlanMapper != null) {
            root.set("角色叙事计划", PlanJsonViews.characters(characterPlanMapper.selectList(
                    new LambdaQueryWrapper<CharacterPlanDO>()
                            .eq(CharacterPlanDO::getRunId, run.getId())
                            .orderByAsc(CharacterPlanDO::getCharacterKey))));
        }
        if (storyThreadMapper != null) {
            root.set("线程计划", PlanJsonViews.threads(storyThreadMapper.selectList(
                    new LambdaQueryWrapper<StoryThreadDO>()
                            .eq(StoryThreadDO::getRunId, run.getId())
                            .orderByAsc(StoryThreadDO::getThreadKey))));
        }
        var chArr = root.putArray("章纲");
        for (ChapterDO c : chapters) {
            var n = chArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("outline", c.getDraftOutline() == null ? "" : c.getDraftOutline());
        }
        var bArr = root.putArray("拍清单");
        for (BeatInstanceDO b : beats) {
            var n = bArr.addObject();
            n.put("seq", b.getSeq());
            n.put("beat_type", b.getBeatType());
            n.put("chapter", b.getChapterNo());
            n.put("status", b.getStatus());
            n.put("filled", b.getFilledText() == null ? "" : b.getFilledText());
        }
        ArrayNode mArr = root.putArray("母题黑名单");
        motifs.forEach(mArr::add);
        ArrayNode scheduleArr = root.putArray("chapterSchedule");
        for (com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry entry : gateTruth.schedule()) {
            ObjectNode item = scheduleArr.addObject();
            item.put("chapterNo", entry.chapterNo());
            item.set("mustPlant", STATIC_MAPPER.valueToTree(entry.mustPlant()));
            item.set("mustPayoff", STATIC_MAPPER.valueToTree(entry.mustPayoff()));
            item.put("reveal", entry.reveal());
        }
        ArrayNode rules = root.putArray("scheduleRules");
        rules.add("日程与合同是硬约束：你的 directive 只能描述章纲文字重写，"
                + "不得要求移动线索章号、改真凶/动机/手法、增删角色");
        rules.add("directive 提到的章号必须是本书已有章；不得出现「把线索改到第 X 章」这类改合同的指令");
        return root.toString();
    }

    // ---- 解析与判定（纯函数，单测覆盖） ----

    static GateReview parseGate(JsonNode root) {
        JsonNode issuesNode = root.path("issues");
        if (issuesNode.isTextual() && !issuesNode.asText("").isBlank()) {
            ArrayNode normalized = STATIC_MAPPER.createArrayNode();
            normalized.add(issuesNode.asText(""));
            ((ObjectNode) root).set("issues", normalized);
        } else if (issuesNode.isObject()) {
            ArrayNode normalized = STATIC_MAPPER.createArrayNode();
            issuesNode.forEach(normalized::add);
            ((ObjectNode) root).set("issues", normalized);
        } else if (!issuesNode.isArray()) {
            ((ObjectNode) root).putArray("issues");
        }
        if (!root.has("directive")) {
            ((ObjectNode) root).put("directive", "");
        }
        JsonNode criteriaNode = root.path("criteria");
        if (!criteriaNode.isObject()) {
            throw new LlmJson.Bad("criteria 必须是对象");
        }
        List<GateCriterionVO> criteria = new ArrayList<>();
        for (String key : CRITERION_KEYS) {
            JsonNode node = criteriaNode.path(key);
            if (!node.isObject()) {
                throw new LlmJson.Bad("criteria." + key + " 必须是对象");
            }
            if (!node.path("pass").isBoolean()) {
                throw new LlmJson.Bad("criteria." + key + ".pass 必须是布尔值");
            }
            boolean pass = node.path("pass").asBoolean();
            String severity = node.path("severity").asText("").trim();
            if (!SEVERITIES.contains(severity)) {
                throw new LlmJson.Bad("criteria." + key
                        + ".severity 必须是 HARD|FIXABLE|ADVISORY");
            }
            String note = node.path("note").asText("").trim();
            if (note.isEmpty()) {
                throw new LlmJson.Bad("criteria." + key + ".note 不能为空");
            }
            JsonNode criterionIssues = normalizeIssuesNode(node.path("issues"));
            ((ObjectNode) node).set("issues", criterionIssues);
            List<String> issues = new ArrayList<>();
            node.path("issues").forEach(n -> {
                String issue = n.asText("").trim();
                if (!issue.isEmpty()) {
                    issues.add(issue);
                }
            });
            String target = node.path("repairTarget").asText("").trim();
            if (!isValidTarget(target)) {
                throw new LlmJson.Bad("criteria." + key
                        + ".repairTarget 必须是 NONE|DRAFT|SPINE|SCALE|PLAN_VOLUME:n");
            }
            if (!pass && !"ADVISORY".equals(severity) && "NONE".equals(target)) {
                throw new LlmJson.Bad("criteria." + key
                        + " 未通过时必须给非 NONE 回炉目标");
            }
            if (pass && !"ADVISORY".equals(severity)) {
                throw new LlmJson.Bad("criteria." + key
                        + " 通过时 severity 必须为 ADVISORY");
            }
            criteria.add(new GateCriterionVO(key, pass, severity, note, issues, target));
        }
        List<String> issues = new ArrayList<>();
        root.path("issues").forEach(n -> {
            String issue = n.asText("").trim();
            if (!issue.isEmpty()) {
                issues.add(issue);
            }
        });
        return new GateReview(criteria, issues, root.path("directive").asText(""));
    }

    /**
     * P6 directive 兜底（Task5）：模型漏写 directive 时，按裁决 issues 自动生成
     * 可执行减法指令，保证回炉轮次始终带反馈，不再空转。
     */
    static String buildDirective(List<String> issues, String repairTarget) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        String target = repairTarget == null || repairTarget.isBlank() ? "NONE" : repairTarget;
        return "按 TruthContract 修复以下问题：" + String.join("；", issues)
                + "；repairTarget=" + target;
    }

    /** P6 前结构预检：机械问题不消耗 LLM 评审轮次。 */
    static List<String> preflightIssues(List<ChapterDO> chapters, List<BeatInstanceDO> beats) {
        List<String> issues = new ArrayList<>();
        Set<Integer> seqs = new HashSet<>();
        Map<Integer, Integer> effective = new HashMap<>();
        int finalReveal = 0;
        for (BeatInstanceDO beat : beats) {
            if ("SKIPPED".equals(beat.getStatus())) {
                continue;
            }
            if (!seqs.add(beat.getSeq())) {
                issues.add("PACK_DUPLICATE_GLOBAL_SEQ：seq=" + beat.getSeq() + " 重复");
            }
            if (beat.getChapterNo() == null) {
                issues.add("PACK_BEAT_UNASSIGNED：seq=" + beat.getSeq() + " 没有章节归属");
            } else {
                effective.merge(beat.getChapterNo(), 1, Integer::sum);
            }
            if ("FINAL_REVEAL".equals(beat.getBeatRole())) {
                finalReveal++;
            }
        }
        for (ChapterDO chapter : chapters) {
            if (effective.getOrDefault(chapter.getChapterNo(), 0) == 0) {
                issues.add("PACK_EMPTY_CHAPTER：第" + chapter.getChapterNo() + "章没有有效拍");
            }
        }
        if (finalReveal > 1) {
            issues.add("REVEAL_NOT_UNIQUE：FINAL_REVEAL 出现 " + finalReveal + " 次");
        }
        return issues;
    }

    private void persistGateReview(long runId, int round, GateDecision decision, String directive) {
        if (gateReviewMapper == null) {
            return;
        }
        try {
            DeriveGateReviewDO row = new DeriveGateReviewDO();
            row.setRunId(runId);
            row.setRound(round);
            row.setVerdict(decision.verdict());
            row.setAction(decision.action());
            row.setIssues(STATIC_MAPPER.writeValueAsString(decision.issues()));
            row.setCriteria(STATIC_MAPPER.writeValueAsString(decision.criteria()));
            row.setDirective(directive);
            row.setRepairTarget(decision.repairTarget());
            gateReviewMapper.insertFull(row);
        } catch (Exception e) {
            log.warn("P6 历史评审落库失败（不影响裁决）：runId={} round={}", runId, round, e);
        }
    }
    /** Java 统一裁决：模型只报问题，放行/回炉/熔断与目标层级都在这里决定。 */
    static GateDecision decideGate(GateReview review, PlanValidationVO planCheck, int prevRound) {
        LinkedHashSet<String> issues = new LinkedHashSet<>(review.issues());
        List<String> targets = new ArrayList<>();
        for (GateCriterionVO criterion : review.criteria()) {
            criterion.issues().forEach(issue -> issues.add(
                    criterionLabel(criterion.key()) + "：" + issue));
            if (!criterion.pass() && !"ADVISORY".equals(criterion.severity())) {
                targets.add(criterion.repairTarget());
            }
        }
        if (planCheck != null && !planCheck.passed()) {
            for (PlanValidationVO.Issue issue : planCheck.hardErrors()) {
                issues.add(issue.code() + "：" + issue.message());
                targets.add(issue.repairTarget());
            }
        }
        // 结构预检拦截（无 criteria 但有 issues）必须阻塞在 REWORK，不得因“没有判据项”判 GO。
        if (review.criteria().isEmpty() && !issues.isEmpty() && targets.isEmpty()) {
            String target = planCheck == null || planCheck.passed() || planCheck.hardErrors().isEmpty()
                    ? "DRAFT"
                    : mostUpstreamTarget(planCheck.hardErrors().stream()
                    .map(PlanValidationVO.Issue::repairTarget).toList());
            boolean exhausted = prevRound >= 5;
            return new GateDecision(exhausted ? "REJECT" : "REWORK",
                    exhausted ? "FAILED" : "REWORK", target,
                    new ArrayList<>(issues), review.criteria());
        }
        if (targets.isEmpty()) {
            return new GateDecision("GO", "PASS", "NONE",
                    new ArrayList<>(issues), review.criteria());
        }
        String repairTarget = mostUpstreamTarget(targets);
        boolean exhausted = prevRound >= 5;
        return new GateDecision(exhausted ? "REJECT" : "REWORK",
                exhausted ? "FAILED" : "REWORK", repairTarget,
                new ArrayList<>(issues), review.criteria());
    }

    private static boolean isUpstreamRepair(String repairTarget) {
        return "SPINE".equals(repairTarget)
                || "SCALE".equals(repairTarget)
                || (repairTarget != null && repairTarget.startsWith("PLAN_VOLUME:"));
    }

    /** 根因重建后保留闸轮次，避免同一个上游矛盾被无限重置回第 1 轮。 */
    static String gateResetForRootRebuild(String stepStatus) {
        ObjectNode root = stepsRoot(stepStatus);
        ObjectNode gate = root.with("steps").withObject("GATE");
        String directive = gate.path("directive").asText("");
        String repairTarget = gate.path("repairTarget").asText("NONE");
        java.util.List<String> issues = new java.util.ArrayList<>();
        gate.path("issues").forEach(n -> issues.add(n.asText("")));
        int round = gate.path("round").asInt(0);
        return withGateNode(root.toString(), "PENDING", round, issues, directive,
                repairTarget, gate.path("criteria"));
    }
    // ---- step_status.steps.GATE 读写（合并既有键，L2 层） ----

    static int gateRound(String stepStatus) {
        return readGate(stepStatus).path("round").asInt(0);
    }

    static boolean gatePassed(String stepStatus) {
        return "PASS".equals(readGate(stepStatus).path("status").asText(""));
    }

    static String withGate(String stepStatus, String status, int round,
                           List<String> issues, String directive) {
        return withGate(stepStatus, status, round, issues, directive, "NONE");
    }

    static String withGate(String stepStatus, String status, int round,
                           List<String> issues, String directive, String repairTarget) {
        return withGate(stepStatus, status, round, issues, directive, repairTarget, List.of());
    }

    static String withGate(String stepStatus, String status, int round,
                           List<String> issues, String directive, String repairTarget,
                           List<GateCriterionVO> criteria) {
        ObjectNode criteriaNode = STATIC_MAPPER.createObjectNode();
        for (GateCriterionVO criterion : criteria == null ? List.<GateCriterionVO>of() : criteria) {
            ObjectNode node = criteriaNode.putObject(criterion.key());
            node.put("pass", criterion.pass());
            node.put("severity", criterion.severity());
            node.put("note", criterion.note());
            ArrayNode issueArr = node.putArray("issues");
            criterion.issues().forEach(issueArr::add);
            node.put("repairTarget", criterion.repairTarget());
        }
        return withGateNode(stepStatus, status, round, issues, directive,
                repairTarget, criteriaNode);
    }

    private static String withGateNode(String stepStatus, String status, int round,
                                       List<String> issues, String directive,
                                       String repairTarget, JsonNode criteria) {
        ObjectNode root = stepsRoot(stepStatus);
        ObjectNode gate = root.with("steps").putObject("GATE");
        gate.put("status", status);
        gate.put("round", round);
        gate.put("directive", directive == null ? "" : directive);
        gate.put("repairTarget", repairTarget == null ? "NONE" : repairTarget);
        ArrayNode arr = gate.putArray("issues");
        issues.forEach(arr::add);
        if (criteria != null && criteria.isObject()) {
            gate.set("criteria", criteria.deepCopy());
        } else {
            gate.putObject("criteria");
        }
        return root.toString();
    }

    /** ⑥b 回炉清稿时重置 GATE 状态——**保留 directive/issues**（重做必须按减法指令执行，2026-10-18 修）。 */
    static String gateReset(String stepStatus) {
        ObjectNode root = stepsRoot(stepStatus);
        ObjectNode gate = root.with("steps").withObject("GATE");
        int round = gate.path("round").asInt(0);
        if ("PASS".equals(gate.path("status").asText(""))) {
            // run45 实弹：P6 已 PASS 后被 ⑧ 质检打回清稿，下一轮 P6 是全新评审周期，
            // 轮次必须归零——否则 round=5 的 PASS 会让复评第 6 轮直接熔断 FAILED。
            // 回炉总次数仍由 ⑧ 自己的 qc_round（≤2 次 REWORK）兜底，不会失控。
            round = 0;
        }
        String directive = gate.path("directive").asText("");
        String repairTarget = gate.path("repairTarget").asText("NONE");
        List<String> issues = new ArrayList<>();
        gate.path("issues").forEach(n -> issues.add(n.asText("")));
        return withGateNode(root.toString(), "PENDING", round, issues, directive,
                repairTarget, gate.path("criteria"));
    }

    /** 回炉重做时要喂回模型的减法指令（无则空串）。 */
    static String gateDirective(String stepStatus) {
        return readGate(stepStatus).path("directive").asText("");
    }

    static String repairTarget(String stepStatus) {
        return readGate(stepStatus).path("repairTarget").asText("NONE");
    }

    static String repairTarget(PlanValidationVO validation) {
        if (validation == null || validation.passed()
                || validation.hardErrors().isEmpty()) {
            return "DRAFT";
        }
        return validation.hardErrors().get(0).repairTarget();
    }

    private static JsonNode readGate(String stepStatus) {
        return stepsRoot(stepStatus).path("steps").path("GATE");
    }

    private static JsonNode normalizeIssuesNode(JsonNode node) {
        if (node.isTextual() && !node.asText("").isBlank()) {
            ArrayNode array = STATIC_MAPPER.createArrayNode();
            array.add(node.asText(""));
            return array;
        }
        if (node.isObject()) {
            ArrayNode array = STATIC_MAPPER.createArrayNode();
            node.forEach(array::add);
            return array;
        }
        return node.isArray() ? node : STATIC_MAPPER.createArrayNode();
    }

    private static boolean isValidTarget(String target) {
        return "NONE".equals(target) || "DRAFT".equals(target) || "SPINE".equals(target)
                || "SCALE".equals(target) || PLAN_VOLUME_TARGET.matcher(target).matches();
    }

    private static String mostUpstreamTarget(List<String> targets) {
        String best = "DRAFT";
        int bestRank = -1;
        for (String target : targets) {
            String normalized = isValidTarget(target) ? target : "DRAFT";
            int rank = switch (normalized) {
                case "SCALE" -> 4;
                case "SPINE" -> 3;
                case "DRAFT" -> 1;
                case "NONE" -> 0;
                default -> 2;
            };
            if (rank > bestRank) {
                best = normalized;
                bestRank = rank;
            }
        }
        return best;
    }

    private static String criterionLabel(String key) {
        return switch (key) {
            case "single_rule" -> "规则单轨";
            case "budget" -> "体量预算";
            case "motive_chain" -> "动机链";
            case "closure" -> "闭合";
            case "function_lock" -> "职能锁定";
            case "motif_dedup" -> "母题查重";
            default -> key;
        };
    }

    private static ObjectNode stepsRoot(String stepStatus) {
        JsonNode root;
        try {
            root = STATIC_MAPPER.readTree(stepStatus == null || stepStatus.isBlank() ? "{}" : stepStatus);
        } catch (Exception e) {
            root = STATIC_MAPPER.createObjectNode();
        }
        if (!root.isObject()) {
            root = STATIC_MAPPER.createObjectNode();
        }
        ObjectNode obj = (ObjectNode) root;
        if (!obj.path("steps").isObject()) {
            obj.putObject("steps");
        }
        if (!obj.has("v")) {
            obj.put("v", 1);
        }
        return obj;
    }
}
