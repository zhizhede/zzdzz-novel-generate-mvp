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
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanV2StateCodec;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ⑥a 章节计划（v3.2 §⑥a：LLM 分章 + 脚本校验 ≤2 轮）。
 *
 * <p>拍清单 = 选中弧的**原生拍链**（asset_beat.parent_arc_id，source=ARC_SEG）
 * + ⑤直采补差拍（picked_beat_ids，source=ASSET）——对应 5.1 的 K×6+M 规模假设。
 * LLM 定序（拍按 arc_fit 与因果），脚本校验：章节数=wordsPred÷章均（快热2500/标准3200/慢热4000）、
 * 每拍恰好一次、Σbudget∈[Nmin,Nmax]、每章≥1拍、快热前3章含{事件,反转}、独立短篇末章含收束拍。
 * 校验不过把原因喂回 LlmJson 重试（≤2 语义由 tries=3 覆盖：首跑+2 回炉）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DerivePlanService {

    public static final String NODE_PLAN = "derive_plan";

    /**
     * 直通免审上下文（2026-10-18 用户定调：全程直通=评审类规则全部留痕不拦，只有落不了库的
     * 机械完整性硬保）。由 DeriveAutoFlowService 在 direct 模式下于本线程设置；普通调用恒 false。
     * 受控降级的三条：快热前3章、张力沉淀①、反转留尾②——都是品味/节奏判断，非机械必需。
     */
    static final ThreadLocal<Boolean> DIRECT_PASS = ThreadLocal.withInitial(() -> false);
    /** 本窗机械张力提示：交给 AI 复核/修复，不再由脚本直接判死。 */
    private static final ThreadLocal<String> TENSION_ISSUE = new ThreadLocal<>();

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    /** ⑥a 每窗章数（2026-10-18 夜：单次输出规模封顶，章数放大到 60 也不撞模型输出上限）。 */
    static final int PLAN_WINDOW_CHAPTERS = 10;
    /** 单窗拍数软上限：超过就按剩余拍/剩余章动态拆窗，避免最后一窗被压上几十拍。 */
    static final int MAX_PLAN_WINDOW_BEATS = 40;
    /** 规模设计口径：每章建议 2~3 拍，具体拍数由分章配额决定；章数先由拍数反推。 */
    static final int MIN_BEATS_PER_CHAPTER = 1;

    /** 每章字数与章数的合理带（AI 自定，系统只夹区间——旧的"字数÷硬常量算章数"已废）。 */
    static final int CHAPTER_AVG_MIN = 2000;
    static final int CHAPTER_AVG_MAX = 6000;
    static final int CHAPTER_COUNT_MIN = 6;
    static final int CHAPTER_COUNT_MAX = 80;

    /**
     * ⑥a-0 定规模：章数由 AI 判（用户定调「不设字数的初衷就是让 AI 自己判篇幅」），
     * 系统只验「章数 × 章均落在字数区间 + 各自在合理带内」。输出极小。
     */
    static final String PLAN_SCALE_PROMPT = """
            你在为一部小说判断"篇幅规模"（不是写章纲，也不是写正文）。输入：创作框架、字数区间、类别、
            结局形状、目标节奏、产品形态、主线、拍清单规模。
            要求：
            1. 先读「创作框架」判断故事规模：短篇、单卷中篇、多卷长篇。框架是短篇就按短篇，
               框架是单卷就按单卷；「可以拆分为多卷」只是可选能力，不是必须扩成多卷。
            2. 框架优先于默认字数带：字数区间只是边界，不是目标；不要为了凑到上限自动扩章、
               扩卷或增加案件。若框架本身是短篇，允许明显低于默认字数目标。
            3. 拍数是素材规模，不等于必须写成的章节规模；短框架可以压缩拍组、合并事件，
               由你判断需要多少章承载，不要机械按每章固定拍数。
            4. 在框架允许的规模内自选 chapterCount 与 chapterAvg；章数 1~80，每章字数
               2000~6000；章数是规模建议值，可高于原始拍数，结构缺口由后续 syntheticSlots 补齐。
            5. 同时给出卷级骨架：每卷必须有本卷独立事件、本卷局部闭合、下一卷钩子；
               普通卷 global_role=LOCAL，最后一卷 global_role=FINAL 且 next_hook 为空。
               卷章号范围必须连续覆盖 1..chapterCount，不得重叠。
            6. 只输出规模和卷骨架，不写章题、不分配拍。
            输出 JSON 对象：
            {"storyScale":"SHORT|MEDIUM|LONG","volumeCount":2,"chapterCount":24,"chapterAvg":2500,
             "reason":"一句话说明为何是这个篇幅",
             "volumes":[{"volume_no":1,"from_chapter":1,"to_chapter":12,
                         "arc":"第一卷事件名","local_event":"本卷独立事件",
                         "local_closure":"本卷局部真相与人物落点",
                         "next_hook":"下一卷钩子","global_role":"LOCAL"}]}
            只输出 JSON 对象，无其他文字。
            """;

    /**
     * ⑥a-1..n 逐窗分章：每次只做这一段连续的章（章数固定、只能从"剩余拍清单"取拍），
     * 单次输出与思考量都封顶；跨窗一致性靠「前窗章概览 + 剩余拍清单」衔接。
     */
    static final String PLAN_WINDOW_PROMPT = """
            你在为一部小说做"章节计划·单窗分配"（不是写正文，也不是写详细章纲，只是把拍分到章）。
            输入：字数区间、类别、结局形状、节奏、产品形态、主线、本窗章号范围、每章目标字数、
            剩余拍清单（临时序号 seq/拍型/节奏/弧位/文案）、前面各窗已定章概览。
            输出 JSON 对象：
            {"chapters":[{"no":11,"title_hint":"≤12字章题","budget":2500,"beats":[23,24]}]}
            规则：
            1. 只能输出本窗章号范围内的章，章号连续、每章都要出现，章数必须等于范围内章数；
            2. beats 只能从"剩余拍清单"取 seq，同一个 seq 只能用一次，每章至少 1 拍；具体拍数按剧情需要安排，不限制单章上限；
               「本窗建议拍数」只是节奏参考，不是硬配额；可在不导致后续章节塞不下、
               也不影响结局闭合的前提下，根据场景和情绪自由前后微调；
            2b. 本窗不要贪多把拍用光：后面的章还等着分拍，本窗用完后剩余拍数必须 ≥ 剩余章数；
            3. 每章 budget 要贴着输入的「每章目标字数」给（上下浮动不超过 30%），本窗合计不要超过
               字数区间上限；全书各章预算合计必须达到字数下限，别把书写短了；
            4. 拍按 arc_fit 与因果定序：弧位偏开头的靠前、收尾的靠后，同弧拍保持相对顺序；
            5. 结局形状=闭合：本窗若含末章，末章必须含收束拍；类别含「推理」或「悬疑」且闭合时，
               末章的收束拍要承载「真相了结/罪责兑现」这类可查验的动作（真凶收押、还原陈述、
               判决落地），禁止用纯氛围收束拍充数，氛围余味只能出现在了结动作之后；
            6. 快热（节奏≥0.75）：开头三章必须含拍型∈{事件,反转} 的拍；
            7. 张力沉淀：不要连续 ≥3 章整章拍型全是 {事件,反转}——重击之间插入铺垫/情绪/抉择章；
               跨窗也算：若输入「前窗末两章拍型」已经是连续两章 {事件,反转}，本窗第一章必须改用
               非 {事件,反转} 的拍，否则就凑成三连；
            8. 类别含「推理」或「悬疑」且反转拍多时，本窗别把反转全用掉，给后面的窗留反转；
            9. title_hint 不带"第N章"前缀，不写专名过露的剧透；
            10. 与前窗衔接：不推翻前窗已定章里的人物关系与已发生事件。
            只输出 JSON 对象，无其他文字。
            """;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final AssetOutlineMapper outlineMapper;
    private final AssetBeatMapper beatMapper;
    private final LabelIndexMapper labelIndexMapper;
    private final WorkFingerprintMapper fingerprintMapper;
    private final VolumePlanMapper volumePlanMapper;
    private final LlmJson llmJson;
    private final DeriveStoryPlanService storyPlanService;
    private final DeriveBeatRouteService beatRouteService;
    private final DerivePlanValidationService planValidationService;
    private final DeriveFactService factService;
    private final DeriveThreadService threadService;

    /** 拍清单条目（临时序号 = 输入给 LLM 的 seq）。 */
    record InvBeat(int tempSeq, String source, Long assetId, Long arcId, Integer arcSegNo,
                   String beatType, BigDecimal pacing, String labelText, String arcSlot) {
    }

    /** AI 张力复核：机械规则只负责发现，是否真是节奏事故由模型结合题材判断。 */
    static final String TENSION_REVIEW_PROMPT = """
            你是小说结构编辑。系统发现章节计划可能连续高张力，请判断这是“有意的连续升级/追逐/对决”
            还是“缺少呼吸感的机械堆叠”。输入含类别、结局形状、问题章节的拍型与情节点。
            判定标准：
            1. 推理/悬疑/惊悚允许连续高压章，但必须有因果推进、信息增量或追逐升级，不能只是重复事件+反转；
            2. 若连续章只是同质化命案/反转、没有铺垫、情绪或抉择承接，判 REVISE；
            3. 若高张力是高潮前的必要加速，且每章都改变局面，判 PASS；
            4. 只输出 JSON：{"verdict":"PASS"或"REVISE","reason":"≤60字判断依据"}。
            只输出 JSON 对象，无其他文字。
            """;

    record PlanDraft(int no, String titleHint, int budget, List<Integer> beats) {
    }

    /**
     * ⑥a：幂等推进一个单元（SCALE 定规模 → WINDOW 逐窗分配拍 → DONE 收尾）。
     * 每次只发一次 LLM 请求，客户端循环调用至 allDone。
     */
    public PlanVO plan(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"PLANNING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥a 需要 run 处于 PLANNING（当前 " + run.getStatus() + "）");
        }
        if (shouldUseLegacy(run)) {
            return planLegacy(runId);
        }
        return planV2(run);
    }

    private PlanVO planLegacy(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"PLANNING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "⑥a 需要 run 处于 PLANNING（当前 " + run.getStatus() + "）");
        }

        // ---- 拍清单（原生拍链 + 直采补差）----
        List<Long> arcIds = jsonLongArray(run.getPickedOutlineIds());
        Map<Long, LabelIndexDO> outlineLabels = labelMap("OUTLINE");
        Map<Long, LabelIndexDO> beatLabels = labelMap("BEAT");
        List<InvBeat> inventory = new ArrayList<>();
        int temp = 1;
        Map<Long, String> arcSlot = new HashMap<>();
        for (Long arcId : arcIds) {
            LabelIndexDO al = outlineLabels.get(arcId);
            arcSlot.put(arcId, slotOf(al));
        }
        for (Long arcId : arcIds) {
            int seg = 0;
            List<AssetBeatDO> natives = beatMapper.selectList(new LambdaQueryWrapper<AssetBeatDO>()
                    .eq(AssetBeatDO::getParentArcId, arcId)
                    .orderByAsc(AssetBeatDO::getId));
            for (AssetBeatDO b : natives) {
                LabelIndexDO l = beatLabels.get(b.getId());
                if (l == null) {
                    continue;
                }
                inventory.add(new InvBeat(temp++, "ARC_SEG", null, arcId, seg++,
                        l.getBeatType(), l.getPacing(), b.getLabelText(), arcSlot.get(arcId)));
            }
        }
        for (Long beatId : jsonLongArray(run.getPickedBeatIds())) {
            AssetBeatDO row = beatMapper.selectById(beatId);
            // 去重：直采拍的父弧若也在选中弧里，其原生链已作为 ARC_SEG 入清单（run 内 asset 唯一）
            if (row == null || row.getParentArcId() == null || arcIds.contains(row.getParentArcId())) {
                continue;
            }
            LabelIndexDO l = beatLabels.get(beatId);
            if (l == null) {
                continue;
            }
            inventory.add(new InvBeat(temp++, "ASSET", beatId, null, null,
                    l.getBeatType(), l.getPacing(), null, slotOf(l)));
        }
        if (inventory.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "拍清单为空，run 抽样异常");
        }
        ensureFinalClosureBeat(inventory, run.getParamOutcome());

        // ---- 幂等推进：先定规模（AI 判章数），再逐窗分章 ----
        PlanState state = readPlanState(run.getStepStatus());
        if (state == null) {
            if (run.getNovelId() != null) {
                // 老的一次性计划留下的书：没有 PLAN 段，重跑会重复建章，明确拒绝
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "run 已计划过（novelId=" + run.getNovelId() + "），⑥a 不能重复规划");
            }
            return planScale(run, inventory, arcIds);
        }
        return planNextWindow(run, inventory, state);
    }

    /** V2 单元推进；测试可直接喂已加载 run。 */
    PlanVO planV2ForTest(RunDO run) {
        return planV2(run);
    }

    private PlanVO planV2(RunDO run) {
        PlanContracts.PlanV2State state = readPlanV2(run.getStepStatus());
        return switch (state.currentUnit()) {
            case SCALE, CONTRACT, SCHEDULE, SPINE, VOLUMES, CHARACTERS ->
                    storyPlanService.advance(run.getId());
            case NEED_SCOPE -> new PlanVO(run.getId(), run.getNovelId() == null ? 0 : run.getNovelId(),
                    state.scope() == null ? 0 : state.scope().chapterCount(),
                    0, 0, List.of(), "NEED_SCOPE", 0, 0, 0, 0, false);
            case SLOT_ROUTE -> routeNextUnfinishedVolume(run, state);
            case VALIDATE -> finishBookValidation(run);
            case DONE -> new PlanVO(run.getId(), run.getNovelId() == null ? 0 : run.getNovelId(),
                    chaptersPlanned(run.getId()), beatsPlanned(run.getId()), totalBudget(run.getId()),
                    List.of(), "DONE", 1, 1, chaptersPlanned(run.getId()),
                    beatsPlanned(run.getId()), true);
            case LEGACY -> planLegacy(run.getId());
        };
    }

    private PlanVO routeNextUnfinishedVolume(RunDO run, PlanContracts.PlanV2State state) {
        int volumeNo = state.volumeUnits().entrySet().stream()
                .filter(e -> e.getValue() != PlanContracts.PlanUnit.VALIDATE)
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse(0);
        if (volumeNo == 0) {
            return finishBookValidation(run);
        }
        var route = beatRouteService.routeVolume(run.getId(), volumeNo);
        return new PlanVO(run.getId(), run.getNovelId(), route.chapterCount(),
                route.routedBeats(), 0, List.of(), "SLOT_ROUTE",
                volumeNo, state.scope() == null ? 0 : state.scope().volumeCount(),
                route.chapterCount(), route.routedBeats(), false,
                volumeNo, route.routedBeats(), route.validationWarnings());
    }

    private PlanVO finishBookValidation(RunDO run) {
        repairPersistedKnowledge(run.getId());
        if (run.getNovelId() != null) {
            factService.rebuild(run.getId(), run.getNovelId());
        }
        threadService.syncFromBeats(run.getId());
        var validation = planValidationService.validateBook(run.getId());
        for (int repairRound = 0; !validation.passed() && repairRound < 2; repairRound++) {
            List<Integer> volumes = repairVolumes(validation);
            if (volumes.isEmpty()) {
                break;
            }
            String feedback = validationFeedback(validation);
            for (Integer volumeNo : volumes) {
                beatRouteService.replanVolume(run.getId(), volumeNo, feedback);
            }
            repairPersistedKnowledge(run.getId());
            validation = planValidationService.validateBook(run.getId());
        }
        if (!validation.passed()) {
            List<PlanValidationVO.Issue> upstream = validation.hardErrors().stream()
                    .filter(issue -> "SPINE".equals(issue.repairTarget())
                            || "SCALE".equals(issue.repairTarget()))
                    .toList();
            if (!upstream.isEmpty()) {
                List<String> issues = upstream.stream()
                        .map(issue -> issue.code() + "：" + issue.message())
                        .collect(java.util.stream.Collectors.toList());
                String repairTarget = upstream.get(0).repairTarget();
                int gateRound = DeriveGateService.gateRound(run.getStepStatus());
                String directive = "规划硬校验发现上游合同缺口；按下列问题重建唯一真相合同："
                        + String.join("；", issues);
                runMapper.updateStepStatus(run.getId(), DeriveGateService.withGate(
                        run.getStepStatus(), "REWORK", gateRound, issues, directive, repairTarget));
                runMapper.updateStatus(run.getId(), "REWORK", null);
                log.warn("规划校验转根因回炉：runId={} target={} issues={}",
                        run.getId(), repairTarget, issues);
                return new PlanVO(run.getId(), run.getNovelId(), chaptersPlanned(run.getId()),
                        beatsPlanned(run.getId()), totalBudget(run.getId()), List.of(), "REWORK",
                        0, 0, 0, 0, false);
            }
            // P6 Task4：DRAFT 级规划硬错误（persona/角色合同对齐）不再直接抛异常停死 auto-flow，
            // 转成 GATE REWORK/DRAFT + 把 PLAN_V2 退回 CHARACTERS 局部重做角色合同，
            // 轮次与 P6 共用同一熔断计数（>5 轮转 FAILED），禁止无限回炉。
            List<String> issues = validation.hardErrors().stream()
                    .map(issue -> issue.code() + "：" + issue.message())
                    .collect(java.util.stream.Collectors.toList());
            String repairTarget = validation.hardErrors().stream()
                    .map(PlanValidationVO.Issue::repairTarget)
                    .filter(target -> target != null && !target.isBlank()
                            && !"NONE".equals(target))
                    .findFirst().orElse("DRAFT");
            int prevRound = DeriveGateService.gateRound(run.getStepStatus());
            String directive = DeriveGateService.buildDirective(issues, repairTarget);
            if (prevRound >= 5) {
                runMapper.updateStepStatus(run.getId(), DeriveGateService.withGate(
                        run.getStepStatus(), "FAILED", prevRound + 1, issues, directive,
                        repairTarget));
                runMapper.updateStatus(run.getId(), "FAILED", null);
                log.error("规划硬校验熔断：runId={} target={} issues={}",
                        run.getId(), repairTarget, issues);
                return new PlanVO(run.getId(), run.getNovelId(), chaptersPlanned(run.getId()),
                        beatsPlanned(run.getId()), totalBudget(run.getId()), List.of(), "FAILED",
                        0, 0, 0, 0, false);
            }
            runMapper.updateStepStatus(run.getId(), DeriveGateService.withGate(
                    run.getStepStatus(), "REWORK", prevRound + 1, issues, directive,
                    repairTarget));
            resetPlanToCharacters(run);
            log.warn("规划硬校验转角色合同回炉：runId={} round={} target={} issues={}",
                    run.getId(), prevRound + 1, repairTarget, issues);
            return new PlanVO(run.getId(), run.getNovelId(), chaptersPlanned(run.getId()),
                    beatsPlanned(run.getId()), totalBudget(run.getId()), List.of(), "REWORK",
                    0, 0, 0, 0, false);
        }
        RunDO current = runMapper.selectById(run.getId());
        ObjectNode root = (ObjectNode) readTreeSafe(current.getStepStatus()).deepCopy();
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        PlanContracts.PlanV2State state = PlanV2StateCodec.read(
                steps.path("PLAN_V2").toString());
        PlanContracts.PlanV2State done = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.DONE,
                appendUnit(state.completedUnits(), PlanContracts.PlanUnit.VALIDATE),
                state.scope(), state.volumeUnits());
        steps.set("PLAN_V2", STATIC_MAPPER.valueToTree(done));
        runMapper.updateStepStatus(run.getId(), root.toString());
        List<String> warnings = validation.warnings().stream()
                .map(w -> w.code() + "：" + w.message()).toList();
        int chapters = chaptersPlanned(run.getId());
        int beats = beatsPlanned(run.getId());
        return new PlanVO(run.getId(), run.getNovelId(), chapters, beats, totalBudget(run.getId()),
                List.of(), "DONE", 1, 1, chapters, beats, true,
                null, beats, warnings);
    }

    private static final Pattern FACT_CHARACTER = Pattern.compile("^C\\d+");

    /** 知识事实的粗粒度归一，仅用于寻找此前已经写入的相近事实。 */
    static String factCore(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = FACT_CHARACTER.matcher(text.trim()).replaceAll("");
        value = value.replaceAll("(内容|详情|证据|事实|结果|情况|成型|完成)$", "");
        value = value.replaceAll("的", "");
        return value.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static boolean factMatches(String writerFact, String requiredFact) {
        String required = factCore(requiredFact);
        String writer = factCore(writerFact);
        return required.length() >= 4 && writer.contains(required);
    }

    private static BeatInstanceDO findEarlierFactWriter(
            List<BeatInstanceDO> rows,
            BeatInstanceDO target,
            String fact,
            Map<String, ObjectNode> routeByBeatId) {
        if (target.getChapterNo() == null) {
            return null;
        }
        for (BeatInstanceDO prior : rows) {
            if (prior.getId() == target.getId()
                    || prior.getStatus() == null || "SKIPPED".equals(prior.getStatus())
                    || prior.getChapterNo() == null
                    || prior.getSeq() == null
                    || prior.getChapterNo() > target.getChapterNo()
                    || (prior.getChapterNo().equals(target.getChapterNo())
                            && prior.getSeq() >= target.getSeq())) {
                continue;
            }
            ObjectNode priorRoute = routeByBeatId.get(String.valueOf(prior.getId()));
            if (priorRoute == null) {
                continue;
            }
            for (JsonNode write : priorRoute.path("writesKnowledge")) {
                if (factMatches(write.asText(""), fact)) {
                    return prior;
                }
            }
        }
        return null;
    }

    /** 对已落库路由做知识传播修复：只补到已有获知拍，找不到来源时不伪造。 */
    private void repairPersistedKnowledge(long runId) {
        List<BeatInstanceDO> rows = new ArrayList<>(beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>()
                        .eq(BeatInstanceDO::getRunId, runId)
                        .eq(BeatInstanceDO::getStatus, "PLANNED")));
        rows.sort(java.util.Comparator
                .comparingInt((BeatInstanceDO beat) ->
                        beat.getChapterNo() == null ? Integer.MAX_VALUE : beat.getChapterNo())
                .thenComparingInt(BeatInstanceDO::getSeq));
        Map<String, Set<String>> knownBy = new HashMap<>();
        Map<String, BeatInstanceDO> firstWriter = new HashMap<>();
        Map<String, ObjectNode> routeByBeatId = new HashMap<>();
        for (BeatInstanceDO beat : rows) {
            JsonNode parsed = readTreeSafe(beat.getRouteContract());
            ObjectNode route = parsed.isObject()
                    ? (ObjectNode) parsed.deepCopy() : STATIC_MAPPER.createObjectNode();
            routeByBeatId.put(String.valueOf(beat.getId()), route);
            List<String> focus = new ArrayList<>();
            route.path("focusCharacters").forEach(node -> focus.add(node.asText("")));
            List<String> requires = new ArrayList<>();
            route.path("requiresKnowledge").forEach(node -> requires.add(node.asText("")));
            for (String fact : requires) {
                Set<String> known = knownBy.getOrDefault(fact, Set.of());
                List<String> missing = focus.stream()
                        .filter(character -> !known.contains(character))
                        .toList();
                BeatInstanceDO writer = firstWriter.get(fact);
                if (writer == null) {
                    writer = findEarlierFactWriter(rows, beat, fact, routeByBeatId);
                }
                if (writer == null) {
                    writer = rows.stream()
                            .filter(row -> row.getChapterNo() != null
                                    && (beat.getChapterNo() == null
                                    || row.getChapterNo() < beat.getChapterNo()))
                            .findFirst().orElse(null);
                    if (writer != null) {
                        log.warn("落库知识缺口改用前置占位拍：fact={} targetSeq={} writerSeq={}",
                                fact, beat.getSeq(), writer.getSeq());
                    }
                }
                if (writer == null || missing.isEmpty()) {
                    if (!missing.isEmpty()) {
                        log.warn("落库知识缺口：fact={} 在早于 seq={} 的拍中无获知来源，缺口={}",
                                fact, beat.getSeq(), missing);
                    }
                    continue;
                }
                ObjectNode writerRoute = routeByBeatId.get(String.valueOf(writer.getId()));
                if (writerRoute == null) {
                    continue;
                }
                ArrayNode writerWrites = writerRoute.withArray("writesKnowledge");
                boolean hasExactFact = false;
                for (JsonNode write : writerWrites) {
                    if (fact.equals(write.asText(""))) {
                        hasExactFact = true;
                        break;
                    }
                }
                if (!hasExactFact) {
                    writerWrites.add(fact);
                    factService.recordRepair(runId, "PLAN_VALIDATE",
                            "KNOWLEDGE_WRITER_MATCH",
                            Map.of("fact", fact),
                            Map.of("writerSeq", writer.getSeq(),
                                    "writerRoute", writerRoute.toString()),
                            "相近事实匹配到已有 writer 并回填精确知识",
                            "PLAN_VOLUME");
                }
                var writerFocus = writerRoute.withArray("focusCharacters");
                Set<String> existing = new LinkedHashSet<>();
                writerFocus.forEach(node -> existing.add(node.asText("")));
                for (String character : missing) {
                    if (existing.add(character)) {
                        writerFocus.add(character);
                    }
                }
                writer.setRouteContract(writerRoute.toString());
                beatInstanceMapper.updateRoute(writer);
                knownBy.computeIfAbsent(fact, key -> new LinkedHashSet<>()).addAll(missing);
                log.info("落库知识因果修复：fact={} 补角色={} 到 seq={}",
                        fact, missing, writer.getSeq());
            }
            List<String> writes = new ArrayList<>();
            route.path("writesKnowledge").forEach(node -> writes.add(node.asText("")));
            for (String fact : writes) {
                knownBy.computeIfAbsent(fact, key -> new LinkedHashSet<>()).addAll(focus);
                firstWriter.putIfAbsent(fact, beat);
            }
        }
    }

    /** 从规划硬校验问题提取需要重排的卷号。 */
    static List<Integer> repairVolumes(com.zzdzz.novelgen.model.vo.derive.PlanValidationVO validation) {
        if (validation == null || validation.passed()) {
            return List.of();
        }
        LinkedHashSet<Integer> volumes = new LinkedHashSet<>();
        for (var issue : validation.hardErrors()) {
            String target = issue.repairTarget();
            if (target == null || !target.startsWith("PLAN_VOLUME:")) {
                continue;
            }
            String raw = target.substring("PLAN_VOLUME:".length());
            try {
                int volumeNo = Integer.parseInt(raw);
                if (volumeNo > 0) {
                    volumes.add(volumeNo);
                }
            } catch (NumberFormatException ignored) {
                // 无卷号的旧问题不能安全自动重排，留给上层显式处理。
            }
        }
        return new ArrayList<>(volumes);
    }

    /** 把规划硬校验问题压成路由模型可执行的返工说明。 */
    static String validationFeedback(
            com.zzdzz.novelgen.model.vo.derive.PlanValidationVO validation) {
        if (validation == null || validation.passed()) {
            return "";
        }
        return validation.hardErrors().stream()
                .map(issue -> issue.code() + "：" + issue.message())
                .collect(java.util.stream.Collectors.joining("；"));
    }

    static boolean shouldUseLegacy(RunDO run) {
        JsonNode steps = readTreeSafe(run.getStepStatus()).path("steps");
        return steps.path("PLAN").isObject() && !steps.path("PLAN_V2").isObject();
    }

    private static PlanContracts.PlanV2State readPlanV2(String stepStatus) {
        JsonNode node = readTreeSafe(stepStatus).path("steps").path("PLAN_V2");
        return node.isObject() ? PlanV2StateCodec.read(node.toString()) : PlanV2StateCodec.initial();
    }

    private int chaptersPlanned(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null || run.getNovelId() == null) {
            return 0;
        }
        return Math.toIntExact(chapterMapper.selectCount(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, run.getNovelId())));
    }

    private int beatsPlanned(long runId) {
        return Math.toIntExact(beatInstanceMapper.selectCount(
                new LambdaQueryWrapper<BeatInstanceDO>().eq(BeatInstanceDO::getRunId, runId)));
    }

    private int totalBudget(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null || run.getNovelId() == null) {
            return 0;
        }
        return chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                        .eq(ChapterDO::getNovelId, run.getNovelId()))
                .stream().mapToInt(c -> c.getBudget() == null ? 0 : c.getBudget()).sum();
    }

    /**
     * P6 Task4：把 PLAN_V2 退回 CHARACTERS 单元，让角色合同用已有 cast/ persona
     * 局部重做（保留 scope 与已 VALIDATE 的卷路由，禁止清书重规划）。
     */
    private void resetPlanToCharacters(RunDO run) {
        RunDO current = runMapper.selectById(run.getId());
        ObjectNode root = (ObjectNode) readTreeSafe(current.getStepStatus()).deepCopy();
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        PlanContracts.PlanV2State state = PlanV2StateCodec.read(
                steps.path("PLAN_V2").toString());
        List<PlanContracts.PlanUnit> completed = new ArrayList<>(state.completedUnits());
        completed.remove(PlanContracts.PlanUnit.CHARACTERS);
        completed.remove(PlanContracts.PlanUnit.SLOT_ROUTE);
        completed.remove(PlanContracts.PlanUnit.VALIDATE);
        Map<Integer, PlanContracts.PlanUnit> volumeUnits = new LinkedHashMap<>(state.volumeUnits());
        PlanContracts.PlanV2State reset = new PlanContracts.PlanV2State(
                PlanContracts.PlanUnit.CHARACTERS, completed, state.scope(), volumeUnits);
        steps.set("PLAN_V2", STATIC_MAPPER.valueToTree(reset));
        runMapper.updateStepStatus(run.getId(), root.toString());
    }

    private static <T> List<T> appendUnit(List<T> values, T value) {
        List<T> out = new ArrayList<>(values == null ? List.of() : values);
        if (!out.contains(value)) {
            out.add(value);
        }
        return out;
    }

    // ---- 单元推进 ----

    /** ⑥a-0：定规模（章数由 AI 判），落成品书骨架与 PLAN 状态。 */
    private PlanVO planScale(RunDO run, List<InvBeat> inventory, List<Long> arcIds) {
        long runId = run.getId();
        DeriveActivity.begin(runId, "⑥a-0 定篇幅规模");
        try {
            String user = buildScaleUser(run, inventory);
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_PLAN, run.getNovelId(), null, runId,
                            List.of(LlmPort.Message.system(PLAN_SCALE_PROMPT), LlmPort.Message.user(user)),
                            0.3),
                    root -> parseScale(root, run, inventory.size()), 3);
            int chapterCount = out.path("chapterCount").asInt();
            int chapterAvg = out.path("chapterAvg").asInt();
            int volumeCount = out.path("volumeCount").asInt(0);
            if (volumeCount < 1) {
                volumeCount = defaultVolumeCount(chapterCount);
            }
            long novelId = run.getNovelId() == null ? createNovel(run, arcIds) : run.getNovelId();
            List<VolumeDraft> volumeDrafts = parseVolumes(out, chapterCount, volumeCount);
            volumeCount = volumeDrafts.size();
            createVolumePlans(runId, novelId, chapterAvg, volumeDrafts);
            PlanState st = new PlanState(chapterCount, chapterAvg, new ArrayList<>(), new ArrayList<>(),
                    volumeCount);
            runMapper.updateStepStatus(runId, mergePlanState(run.getStepStatus(), st));
            log.info("⑥a-0 定规模：runId={} novelId={} 体系={} 卷={} 章={} 章均={} 理由={}",
                    runId, novelId, out.path("storyScale").asText(""),
                    volumeCount, chapterCount, chapterAvg, out.path("reason").asText(""));
            return new PlanVO(runId, novelId, chapterCount, 0, 0, List.of(),
                    "SCALE", 0, totalWindows(chapterCount), 0, 0, false);
        } finally {
            DeriveActivity.end(runId);
        }
    }

    /** 未由 AI 给卷数时的保守估算：约 12 章一卷。 */
    static int defaultVolumeCount(int chapterCount) {
        return Math.max(1, (chapterCount + 11) / 12);
    }

    /** 章号 → 卷号（连续均分；45章4卷约为12/11/11/11）。 */
    static int volumeNoForChapter(int chapterNo, int chapterCount, int volumeCount) {
        if (chapterCount <= 0 || volumeCount <= 0) {
            return 1;
        }
        return Math.min(volumeCount, (chapterNo - 1) * volumeCount / chapterCount + 1);
    }

    /** 规模确定后落卷级骨架；⑥b/页面可据此展示每卷事件与闭合。 */
    void createVolumePlans(long runId, long novelId, int chapterAvg, List<VolumeDraft> drafts) {
        for (VolumeDraft v : drafts) {
            VolumePlanDO row = new VolumePlanDO();
            row.setRunId(runId);
            row.setNovelId(novelId);
            row.setVolumeNo(v.volumeNo());
            row.setFromChapter(v.fromChapter());
            row.setToChapter(v.toChapter());
            row.setArc(v.arc());
            row.setLocalEvent(v.localEvent());
            row.setLocalClosure(v.localClosure());
            row.setNextHook(v.nextHook());
            row.setGlobalRole(v.globalRole());
            row.setStatus("PLANNED");
            volumePlanMapper.insert(row);
        }
    }

    /** 解析 AI 卷骨架；缺失/坏结构时退回按章均分的单卷骨架，避免旧模型输出直接炸整轮。 */
    static List<VolumeDraft> parseVolumes(JsonNode root, int chapterCount, int volumeCount) {
        List<VolumeDraft> out = new ArrayList<>();
        JsonNode arr = root.path("volumes");
        if (arr.isArray() && !arr.isEmpty()) {
            int expectFrom = 1;
            for (JsonNode n : arr) {
                int no = n.path("volume_no").asInt(0);
                int from = n.path("from_chapter").asInt(0);
                int to = n.path("to_chapter").asInt(0);
                String arc = n.path("arc").asText("");
                String event = n.path("local_event").asText("");
                String closure = n.path("local_closure").asText("");
                if (no != out.size() + 1 || from != expectFrom || to < from || to > chapterCount
                        || arc.isBlank() || event.isBlank() || closure.isBlank()) {
                    break;
                }
                boolean last = out.size() + 1 == arr.size();
                String role = last ? "FINAL" : "LOCAL";
                String next = last ? "" : n.path("next_hook").asText("");
                if (!last && next.isBlank()) {
                    break;
                }
                out.add(new VolumeDraft(no, from, to, arc, event, closure, next, role));
                expectFrom = to + 1;
            }
            if (out.size() == arr.size() && expectFrom == chapterCount + 1) {
                return out;
            }
        }
        // 兼容旧输出：按默认卷数均分，内容留给后续卷规划重排。
        int count = Math.max(1, volumeCount);
        for (int vol = 1; vol <= count; vol++) {
            int from = Math.min(chapterCount, (vol - 1) * chapterCount / count + 1);
            int to = vol == count ? chapterCount : vol * chapterCount / count;
            out.add(new VolumeDraft(vol, from, to, "第" + vol + "卷",
                    "承接创作框架，完成本卷独立事件与调查推进",
                    "本卷阶段闭合：揭晓本卷事件结果",
                    vol == count ? "" : "下一卷承接本卷未解线索",
                    vol == count ? "FINAL" : "LOCAL"));
        }
        return out;
    }

    record VolumeDraft(int volumeNo, int fromChapter, int toChapter, String arc,
                       String localEvent, String localClosure, String nextHook, String globalRole) {
    }

    /** ⑥a-1..n：分配下一窗的拍并落库；全部完成后返回 DONE（客户端循环调用）。 */
    private PlanVO planNextWindow(RunDO run, List<InvBeat> inventory, PlanState state) {
        long runId = run.getId();
        Long novelId = run.getNovelId();
        if (novelId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥a 状态与成品书不一致（novel_id 为空）——请重新建 run");
        }
        int totalW = totalWindows(state.chapterCount());
        List<Integer> usedSeqs = new ArrayList<>(state.usedSeqs());
        List<PlanDraft> drafts = new ArrayList<>(state.drafts());
        if (drafts.size() >= state.chapterCount()) {
            int totalBudget = drafts.stream().mapToInt(PlanDraft::budget).sum();
            if (usedSeqs.size() < inventory.size()) {
                log.warn("⑥a 收尾：章已排满但还有 {} 个拍未分配（runId={} 已用 {}/{}）",
                        inventory.size() - usedSeqs.size(), runId, usedSeqs.size(), inventory.size());
            }
            log.info("⑥a 全部窗完成：runId={} novelId={} 章={} 拍={} 总预算={}",
                    runId, novelId, drafts.size(), usedSeqs.size(), totalBudget);
            return new PlanVO(runId, novelId, drafts.size(), usedSeqs.size(), totalBudget,
                    List.of(), "DONE", totalW, totalW, drafts.size(), usedSeqs.size(), true);
        }
        int from = drafts.size() + 1;
        List<InvBeat> remain = inventory.stream().filter(b -> !usedSeqs.contains(b.tempSeq())).toList();
        int windowChapters = planWindowChapterCount(remain.size(), state.chapterCount() - drafts.size());
        int to = Math.min(state.chapterCount(), from + windowChapters - 1);
        int windowNo = (from - 1) / PLAN_WINDOW_CHAPTERS + 1;
        List<PlanDraft> win = allocateWindow(from, to, remain, state.chapterCount(),
                state.chapterAvg(), inventory.size());
        DeriveActivity.begin(runId, "⑥a 分章 第" + windowNo + "/" + totalW + " 窗（章 " + from + "-" + to + "）");
        try {
            persistWindow(runId, novelId, win, inventory, state.chapterCount(), state.volumeCount());
        } finally {
            DeriveActivity.end(runId);
        }
        drafts.addAll(win);
        for (PlanDraft d : win) {
            usedSeqs.addAll(d.beats());
        }
        PlanState next = new PlanState(state.chapterCount(), state.chapterAvg(), usedSeqs, drafts);
        runMapper.updateStepStatus(runId, mergePlanState(run.getStepStatus(), next));
        int assigned = win.stream().mapToInt(d -> d.beats().size()).sum();
        int totalBudget = drafts.stream().mapToInt(PlanDraft::budget).sum();
        log.info("⑥a 分章窗：runId={} novelId={} 窗{}/{} 章{}-{} 分配拍{}（拍累计 {}/{}，章累计 {}/{}）",
                runId, novelId, windowNo, totalW, from, to, assigned,
                usedSeqs.size(), inventory.size(), drafts.size(), state.chapterCount());
        return new PlanVO(runId, novelId, state.chapterCount(), usedSeqs.size(), totalBudget,
                List.of(), "WINDOW", windowNo, totalW, drafts.size(), usedSeqs.size(), false);
    }

    /** 设计阶段确定性分章：按每章配额从剩余拍清单按顺序切组，模型不再参与拍数分配。 */
    static List<PlanDraft> allocateWindow(int from, int to, List<InvBeat> remain,
                                          int chapterCount, int chapterAvg, int totalBeats) {
        int cursor = 0;
        List<PlanDraft> out = new ArrayList<>();
        for (int no = from; no <= to; no++) {
            int quota = beatQuotaForChapter(totalBeats, chapterCount, no);
            int end = Math.min(remain.size(), cursor + quota);
            if (end - cursor != quota) {
                throw new IllegalStateException("拍配额越界：第" + no + "章需要 " + quota
                        + " 拍，剩余只有 " + (remain.size() - cursor));
            }
            List<Integer> beats = new ArrayList<>();
            for (int i = cursor; i < end; i++) {
                beats.add(remain.get(i).tempSeq());
            }
            cursor = end;
            out.add(new PlanDraft(no, "第" + no + "章", chapterAvg, beats));
        }
        return out;
    }

    /** 全书拍数按章节前余后整地摊分：104拍/52章为2拍每章。 */
    static int beatQuotaForChapter(int totalBeats, int chapterCount, int chapterNo) {
        int base = totalBeats / chapterCount;
        int remainder = totalBeats % chapterCount;
        return base + (chapterNo <= remainder ? 1 : 0);
    }

    /**
     * 闭合结局的拍组兜底：末拍必须是「收束」，否则把源清单最后一拍提升为终局收束拍。
     * 保留原拍号/来源/节奏，只改变拍型与语义标签，既不改总拍数，也不让末章停在悬念事件上。
     */
    static void ensureFinalClosureBeat(List<InvBeat> inventory, String outcome) {
        if (!"闭合".equals(outcome) || inventory.isEmpty()) {
            return;
        }
        int last = inventory.size() - 1;
        InvBeat tail = inventory.get(last);
        if ("收束".equals(tail.beatType())) {
            return;
        }
        String label = tail.labelText() == null || tail.labelText().isBlank()
                ? "主线真相收束，人物与事件落到最终状态"
                : "终局收束：" + tail.labelText();
        inventory.set(last, new InvBeat(tail.tempSeq(), tail.source(), tail.assetId(), tail.arcId(),
                tail.arcSegNo(), "收束", tail.pacing(), label, tail.arcSlot()));
    }
    /** 窗落库：chapters + beat_instance（每窗独立提交，失败不回滚已完成窗）。 */
    void persistWindow(long runId, long novelId, List<PlanDraft> win, List<InvBeat> inventory,
                       int stateChapterCount, int volumeCount) {
        int nextSeq = nextBeatSeq(runId);
        for (PlanDraft d : win) {
            ChapterDO ch = new ChapterDO();
            ch.setNovelId(novelId);
            ch.setChapterNo(d.no());
            ch.setVolumeNo(volumeNoForChapter(d.no(), stateChapterCount, volumeCount));
            ch.setTitle(d.titleHint() == null || d.titleHint().isBlank() ? "第" + d.no() + "章" : d.titleHint());
            ch.setBudget(d.budget());
            ch.setStatus("PLANNED");
            BigDecimal paceSum = BigDecimal.ZERO;
            for (Integer tempSeq : d.beats()) {
                paceSum = paceSum.add(inventory.get(tempSeq - 1).pacing());
            }
            ch.setPacePlan(d.beats().isEmpty() ? BigDecimal.ZERO
                    : paceSum.divide(BigDecimal.valueOf(d.beats().size()), 2, RoundingMode.HALF_UP));
            chapterMapper.insert(ch);
            for (Integer tempSeq : d.beats()) {
                InvBeat ib = inventory.get(tempSeq - 1);
                BeatInstanceDO bi = new BeatInstanceDO();
                bi.setRunId(runId);
                bi.setSeq(nextSeq++);
                bi.setSource(ib.source());
                bi.setAssetId(ib.assetId());
                bi.setArcId(ib.arcId());
                bi.setArcSegNo(ib.arcSegNo());
                bi.setBeatType(ib.beatType());
                bi.setPacing(ib.pacing());
                bi.setChapterNo(d.no());
                bi.setVolumeNo(volumeNoForChapter(d.no(), stateChapterCount, volumeCount));
                bi.setFilledText(ib.labelText());
                bi.setStatus("PLANNED");
                beatInstanceMapper.insert(bi);
            }
        }
    }

    /** 成品书骨架（⑥a-0 建一次；文风卡从抽中弧的源书复制，v3.6 方案B）。 */
    private long createNovel(RunDO run, List<Long> arcIds) {
        NovelDO novel = new NovelDO();
        novel.setTitle("未命名·RUN-" + run.getId());
        novel.setSourceType("GENERATED");
        novel.setCategoryTags(run.getParamTags());
        novel.setForm(run.getParamForm());
        novel.setRunId(run.getId());
        String styleCard = pickedOutlineStyleCard(arcIds);
        if (styleCard != null) {
            novel.setStorySettings(styleCard);
        }
        long novelId = novelMapper.insertFull(novel);
        runMapper.updateNovelId(run.getId(), novelId);
        registerBridgeFingerprints(run, novelId);
        return novelId;
    }

    /** 拍实例下一个可用 seq（跨窗递增，避免撞号）。 */
    int nextBeatSeq(long runId) {
        List<BeatInstanceDO> tail = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByDesc(BeatInstanceDO::getSeq)
                .last("LIMIT 1"));
        return tail.isEmpty() || tail.get(0).getSeq() == null ? 1 : tail.get(0).getSeq() + 1;
    }

    static int totalWindows(int chapterCount) {
        return (chapterCount + PLAN_WINDOW_CHAPTERS - 1) / PLAN_WINDOW_CHAPTERS;
    }

    /**
     * 设计阶段动态拆窗：按“剩余拍数 ÷ 剩余章数”估算本窗章数，并用单窗拍数上限约束。
     * run30 实弹：前3窗只用64/144拍，最后10章被压上约80拍，模型无法一次完整分配；
     * 现在会把这种情况拆成两个5章窗，而不是把过载问题留给拦截重跑。
     */
    static int planWindowChapterCount(int remainingBeats, int remainingChapters) {
        if (remainingChapters <= 0) {
            return 1;
        }
        int avgBeatsPerChapter = Math.max(1,
                (remainingBeats + remainingChapters - 1) / remainingChapters);
        int beatLimited = (MAX_PLAN_WINDOW_BEATS + avgBeatsPerChapter - 1) / avgBeatsPerChapter;
        return Math.max(1, Math.min(PLAN_WINDOW_CHAPTERS, beatLimited));
    }

    // ---- 输入构造与解析校验 ----

    /** 抽中弧 BRIDGE 指纹（与⑧ registerFingerprints 同口径；唯一键幂等，撞了忽略）。 */
    void registerBridgeFingerprints(RunDO run, long novelId) {
        for (long outlineId : DeriveQcService.parseIdArray(run.getPickedOutlineIds())) {
            AssetOutlineDO arc = outlineMapper.selectById(outlineId);
            if (arc == null) {
                continue;
            }
            LabelIndexDO label = labelIndexMapper.selectOne(new LambdaQueryWrapper<LabelIndexDO>()
                    .eq(LabelIndexDO::getEntryType, "OUTLINE")
                    .eq(LabelIndexDO::getEntryTypeId, outlineId)
                    .last("LIMIT 1"));
            String fp = label == null ? null : DeriveQcService.fingerprintOf(arc.getFuncTag(), label.getCat());
            String value = arc.getCode() != null ? arc.getCode()
                    : (arc.getLabelText() == null ? "" : arc.getLabelText().substring(0, Math.min(32, arc.getLabelText().length())));
            try {
                WorkFingerprintDO row = new WorkFingerprintDO();
                row.setNovelId(novelId);
                row.setKind("BRIDGE");
                row.setValue(value);
                row.setFingerprint(fp);
                fingerprintMapper.insert(row);
            } catch (Exception e) {
                log.debug("work_fingerprint 已存在（幂等）：{}", value);
            }
        }
    }

    /** ⑥a-0 输入：只给判断篇幅所需的信息（不给章数，章数由 AI 判）。 */
    String buildScaleUser(RunDO run, List<InvBeat> inventory) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("字数区间", run.getParamWordsMin() + "~" + run.getParamWordsMax());
        root.set("类别", DeriveWriteService.readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        root.put("创作框架", run.getParamIntent() == null ? "" : run.getParamIntent());
        root.put("规模判断口径", "先按创作框架判断短/中/长篇；可多卷只是可选，不是扩长指令");
        root.put("拍数", inventory.size());
        root.put("目标字数区间（仅边界）", run.getParamWordsMin() + "~" + run.getParamWordsMax());
        return root.toString();
    }

    /** ⑥a-1 输入：本窗章号范围 + 剩余拍 + 前窗章概览（衔接用）。 */
    String buildWindowUser(RunDO run, List<InvBeat> inventory, List<InvBeat> remain,
                           List<PlanDraft> drafts, int from, int to, int chapterAvg, int chapterCount) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("字数区间", run.getParamWordsMin() + "~" + run.getParamWordsMax());
        root.set("类别", DeriveWriteService.readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        root.put("本窗章号范围", from + "~" + to);
        root.put("本窗章数", to - from + 1);
        root.put("每章目标字数", chapterAvg);
        root.put("剩余拍数", remain.size());
        int remainingChapters = Math.max(1, chapterCount - drafts.size());
        int targetBeats = (int) Math.ceil(remain.size() * (to - from + 1) / (double) remainingChapters);
        root.put("本窗建议拍数", targetBeats);
        root.put("单章最少拍数", MIN_BEATS_PER_CHAPTER);
        root.put("全章平均拍数", chapterCount == 0 ? 0 : inventory.size() / (double) chapterCount);
        var prev = root.putArray("前窗章概览");
        for (PlanDraft d : drafts) {
            var n = prev.addObject();
            n.put("no", d.no());
            n.put("title_hint", d.titleHint());
            n.put("budget", d.budget());
            var types = n.putArray("beat_types");
            for (Integer s : d.beats()) {
                types.add(inventory.get(s - 1).beatType());
            }
        }
        var arr = root.putArray("剩余拍清单");
        for (InvBeat ib : remain) {
            var n = arr.addObject();
            n.put("seq", ib.tempSeq());
            n.put("beat_type", ib.beatType());
            n.put("pacing", ib.pacing().toPlainString());
            n.put("arc_slot", ib.arcSlot());
            if (ib.labelText() != null) {
                n.put("label", ib.labelText());
            }
        }
        // 跨窗张力衔接：把上一窗最后两章的拍型摊开，避免本窗开头又凑成三连高张力
        var tail = root.putArray("前窗末两章拍型");
        for (int i = Math.max(0, drafts.size() - 2); i < drafts.size(); i++) {
            PlanDraft d = drafts.get(i);
            var n = tail.addObject();
            n.put("no", d.no());
            var types = n.putArray("beat_types");
            for (Integer s : d.beats()) {
                types.add(inventory.get(s - 1).beatType());
            }
        }
        return root.toString();
    }

    /**
     * ⑥a-0 校验：章数与章均由 AI 定。字数带（区间 / 80% 下限 / 章均带）只在开了字数审计
     * （run.len_check）时卡；不开审计直接放行，只保留机械必备项（章数≥1、章数≤拍数）。
     */
    static JsonNode parseScale(JsonNode root, RunDO run, int beatCount) {
        int ch = root.path("chapterCount").asInt(0);
        int avg = root.path("chapterAvg").asInt(0);
        if (ch < 1) {
            throw new LlmJson.Bad("章数必须 ≥1（当前 " + ch + "）");
        }
        if (avg < 1) {
            throw new LlmJson.Bad("每章字数必须 >0（当前 " + avg + "）");
        }
        // 章数由创作框架决定；系统只做规模带校验，拍数缺口交给后续结构补拍。
        // 不再用固定每章拍数反推章数，否则会把短框架强制扩成长篇。
        if (!Boolean.TRUE.equals(run.getLenCheck())) {
            return root;
        }
        if (ch > CHAPTER_COUNT_MAX) {
            throw new LlmJson.Bad("章数必须在 " + CHAPTER_COUNT_MIN + "~" + CHAPTER_COUNT_MAX
                    + "（当前 " + ch + "）");
        }
        if (avg < CHAPTER_AVG_MIN || avg > CHAPTER_AVG_MAX) {
            throw new LlmJson.Bad("每章字数必须在 " + CHAPTER_AVG_MIN + "~" + CHAPTER_AVG_MAX
                    + "（当前 " + avg + "）");
        }
        long total = (long) ch * avg;
        if (total < run.getParamWordsMin() || total > run.getParamWordsMax()) {
            throw new LlmJson.Bad("章数×每章字数=" + total + " 超出字数区间 ["
                    + run.getParamWordsMin() + "," + run.getParamWordsMax() + "]——调整章数或每章字数");
        }
        // 不能只贴着区间下限交差：总量至少要覆盖上限的 80%，否则章数配不上字数
        long floor = (long) Math.ceil(run.getParamWordsMax() * 0.8);
        if (total < floor) {
            throw new LlmJson.Bad("章数×每章字数=" + total + " 只够上限的 "
                    + Math.round(total * 100.0 / run.getParamWordsMax()) + "%，至少要到 " + floor
                    + "——这部书要写到 " + run.getParamWordsMax() + " 字，请增加章数或提高每章字数");
        }
        return root;
    }

    /** ⑥a-1 校验：本窗章号连续齐全、拍只取自剩余清单且不跨窗重复、预算与累计预算在区间内。 */
    static List<PlanDraft> parseWindow(JsonNode root, WindowContext ctx) {
        JsonNode arr = root.path("chapters");
        int expect = ctx.to() - ctx.from() + 1;
        if (!arr.isArray() || arr.size() != expect) {
            throw new LlmJson.Bad("本窗必须输出 " + expect + " 章（第 " + ctx.from() + "-" + ctx.to()
                    + " 章），当前 " + (arr.isArray() ? arr.size() : 0));
        }
        Set<Integer> remainSeqs = new HashSet<>();
        for (InvBeat b : ctx.remain()) {
            remainSeqs.add(b.tempSeq());
        }
        Set<Integer> usedBefore = new HashSet<>(ctx.state().usedSeqs());
        List<PlanDraft> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        Set<Integer> nos = new HashSet<>();
        int winBudget = 0;
        for (JsonNode c : arr) {
            int no = c.path("no").asInt(0);
            int budget = c.path("budget").asInt(0);
            if (no < ctx.from() || no > ctx.to()) {
                throw new LlmJson.Bad("章号越界: " + no + "（本窗只做 " + ctx.from() + "-" + ctx.to() + "）");
            }
            if (!nos.add(no)) {
                throw new LlmJson.Bad("章号重复: " + no);
            }
            if (ctx.plan().lenCheck()
                    && Math.abs(budget - ctx.state().chapterAvg()) > ctx.state().chapterAvg() * 0.3) {
                throw new LlmJson.Bad("第" + no + "章 budget=" + budget + " 与目标章均 "
                        + ctx.state().chapterAvg() + " 偏差超过 30%——按章均给量，总量才对得上字数区间");
            }
            JsonNode beats = c.path("beats");
            if (!beats.isArray() || beats.isEmpty()) {
                throw new LlmJson.Bad("第" + no + "章 beats 必须非空（每章≥1拍）");
            }
            List<Integer> beatList = new ArrayList<>();
            for (JsonNode b : beats) {
                int s = b.asInt(0);
                if (usedBefore.contains(s)) {
                    throw new LlmJson.Bad("拍 seq " + s + " 已在前面的窗用过（每拍全书恰好一次）");
                }
                if (!remainSeqs.contains(s)) {
                    throw new LlmJson.Bad("拍 seq " + s + " 不在剩余拍清单里");
                }
                if (!used.add(s)) {
                    throw new LlmJson.Bad("拍 seq " + s + " 在本窗重复出现");
                }
                beatList.add(s);
            }
            winBudget += budget;
            out.add(new PlanDraft(no, c.path("title_hint").asText(""), budget, beatList));
        }
        Set<Integer> missingNos = new HashSet<>();
        for (int n = ctx.from(); n <= ctx.to(); n++) {
            if (!nos.contains(n)) {
                missingNos.add(n);
            }
        }
        if (!missingNos.isEmpty()) {
            throw new LlmJson.Bad("本窗章号不连续，缺: " + missingNos);
        }
        int cumBudget = ctx.state().drafts().stream().mapToInt(PlanDraft::budget).sum() + winBudget;
        if (ctx.plan().lenCheck() && cumBudget > ctx.plan().maxWords()) {
            throw new LlmJson.Bad("预算累计 " + cumBudget + " 已超出字数上限 "
                    + ctx.plan().maxWords() + "——本章窗把 budget 往小调");
        }
        int restChapters = ctx.state().chapterCount() - ctx.to();
        int restBeats = ctx.remain().size() - used.size();
        int mustUse = Math.max(0, ctx.remain().size() - restChapters);
        if (used.size() < mustUse) {
            throw new LlmJson.Bad("本窗只分配 " + used.size() + " 拍，至少需要分配 " + mustUse
                    + " 拍——否则剩下 " + restBeats + " 拍无法在后续 " + restChapters
                    + " 章内保证每章至少 1 拍");
        }
        if (restBeats < restChapters) {
            throw new LlmJson.Bad("本窗用掉 " + used.size() + " 拍后只剩 " + restBeats
                    + " 拍，而后面还有 " + restChapters + " 章——每章至少 1 拍，本窗请少用拍");
        }
        if (restChapters == 0 && restBeats != 0) {
            List<Integer> left = ctx.remain().stream().map(InvBeat::tempSeq)
                    .filter(s -> !used.contains(s)).toList();
            throw new LlmJson.Bad("这是最后一窗，还剩拍未分配: " + left
                    + "——拍清单每个 seq 必须恰好用一次，请把它们分进本窗各章");
        }
        if (ctx.plan().lenCheck() && restChapters == 0 && cumBudget < ctx.plan().minWords()) {
            throw new LlmJson.Bad("全部章预算合计 " + cumBudget + " 低于字数下限 "
                    + ctx.plan().minWords() + "——各章 budget 往上调，别把书写短了");
        }
        List<PlanDraft> all = new ArrayList<>(ctx.state().drafts());
        all.addAll(out);
        if (ctx.from() == 1 && ctx.plan().pace().compareTo(new BigDecimal("0.75")) >= 0) {
            boolean hot = false;
            for (PlanDraft d : out) {
                if (d.no() > 3) {
                    break;
                }
                for (Integer s : d.beats()) {
                    String t = ctx.plan().inventory().get(s - 1).beatType();
                    if ("事件".equals(t) || "反转".equals(t)) {
                        hot = true;
                    }
                }
            }
            if (!hot) {
                if (Boolean.TRUE.equals(DIRECT_PASS.get())) {
                    log.warn("直通免审：快热前3章规则降级留痕（不拦）——pace={} 前3章无{事件,反转}拍",
                            ctx.plan().pace());
                } else {
                    throw new LlmJson.Bad("快热：前 3 章必须含拍型∈{事件,反转} 的拍");
                }
            }
        }
        TENSION_ISSUE.remove();
        String tensionIssue = detectTensionIssue(all, ctx.plan(), all.size() >= ctx.state().chapterCount());
        if (tensionIssue != null) {
            TENSION_ISSUE.set(tensionIssue);
        }
        return out;
    }

    /** 张力密度检测：机械规则只发现风险，交给 AI 复核决定是否真是结构事故。 */
    static String detectTensionIssue(List<PlanDraft> drafts, PlanContext ctx, boolean complete) {
        int consecutive = 0;
        for (PlanDraft d : drafts) {
            boolean allHigh = !d.beats().isEmpty();
            for (Integer s : d.beats()) {
                InvBeat ib = ctx.inventory().get(s - 1);
                if (!"事件".equals(ib.beatType()) && !"反转".equals(ib.beatType())) {
                    allHigh = false;
                    break;
                }
            }
            consecutive = allHigh ? consecutive + 1 : 0;
            if (consecutive >= 3) {
                return "张力沉淀：第 " + (d.no() - 2) + "–" + d.no()
                        + " 章连续 3 章整章拍型全是 {事件,反转}——重击之间需要有呼吸感";
            }
        }
        String tags = ctx.tags() == null ? "" : ctx.tags();
        boolean reasoning = tags.contains("推理") || tags.contains("悬疑");
        if (!complete || !reasoning || drafts.isEmpty()) {
            return null;
        }
        int reverseTotal = 0;
        for (PlanDraft d : drafts) {
            for (Integer s : d.beats()) {
                if ("反转".equals(ctx.inventory().get(s - 1).beatType())) {
                    reverseTotal++;
                }
            }
        }
        if (reverseTotal < 2) {
            return null;
        }
        int tailFrom = (int) Math.floor(drafts.size() * 0.7);
        if (tailFrom >= drafts.size()) {
            return null;
        }
        boolean tailHasReverse = false;
        for (int i = tailFrom; i < drafts.size(); i++) {
            for (Integer s : drafts.get(i).beats()) {
                if ("反转".equals(ctx.inventory().get(s - 1).beatType())) {
                    tailHasReverse = true;
                }
            }
        }
        if (tailHasReverse) {
            return null;
        }
        return "反转后移：推理/悬疑且反转拍=" + reverseTotal
                + "，最后 30% 章（第 " + drafts.get(tailFrom).no() + " 章起）没有任何反转——终局段需要保留悬念";
    }

    static void checkTensionDensity(List<PlanDraft> drafts, PlanContext ctx) {
        checkTensionDensity(drafts, ctx, true);
    }

    static void checkTensionDensity(List<PlanDraft> drafts, PlanContext ctx, boolean complete) {
        String issue = detectTensionIssue(drafts, ctx, complete);
        if (issue == null) {
            return;
        }
        if (Boolean.TRUE.equals(DIRECT_PASS.get())) {
            log.warn("直通免审：{}（降级留痕，不拦）", issue);
            return;
        }
        throw new LlmJson.Bad(issue);
    }

    /** 取走并清空本窗机械张力提示。 */
    static String takeTensionIssue() {
        String issue = TENSION_ISSUE.get();
        TENSION_ISSUE.remove();
        return issue;
    }

    /** AI 张力复核：允许推理/悬疑的连续高压，但拒绝同质化堆叠。 */
    TensionReview aiTensionReview(RunDO run, List<InvBeat> inventory,
                                  List<PlanDraft> previous, List<PlanDraft> current,
                                  String issue) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("issue", issue);
        root.put("tags", run.getParamTags() == null ? "" : run.getParamTags());
        root.put("outcome", run.getParamOutcome());
        var arr = root.putArray("chapters");
        for (PlanDraft d : concat(previous, current)) {
            var n = arr.addObject();
            n.put("no", d.no());
            var types = n.putArray("beat_types");
            var labels = n.putArray("beats");
            for (Integer s : d.beats()) {
                InvBeat ib = inventory.get(s - 1);
                types.add(ib.beatType());
                var b = labels.addObject();
                b.put("seq", s);
                b.put("label", ib.labelText() == null ? "" : ib.labelText());
            }
        }
        return llmJson.ask(
                new LlmPort.ChatRequest(NODE_PLAN, run.getNovelId(), null, run.getId(),
                        List.of(LlmPort.Message.system(TENSION_REVIEW_PROMPT),
                                LlmPort.Message.user(root.toString())), 0.2),
                node -> {
                    String verdict = node.path("verdict").asText("");
                    if (!"PASS".equals(verdict) && !"REVISE".equals(verdict)) {
                        throw new LlmJson.Bad("verdict 必须是 PASS 或 REVISE，当前 " + verdict);
                    }
                    return new TensionReview("PASS".equals(verdict),
                            node.path("reason").asText(""));
                }, 2);
    }

    private static List<PlanDraft> concat(List<PlanDraft> a, List<PlanDraft> b) {
        List<PlanDraft> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    record TensionReview(boolean pass, String reason) {
    }
    record PlanContext(int expectedCh, int minWords, int maxWords,
                       BigDecimal pace, String form, String outcome, List<InvBeat> inventory,
                       String tags, boolean lenCheck) {
        PlanContext(int expectedCh, int minWords, int maxWords,
                    BigDecimal pace, String form, String outcome, List<InvBeat> inventory) {
            this(expectedCh, minWords, maxWords, pace, form, outcome, inventory, null, false);
        }

        PlanContext(int expectedCh, int minWords, int maxWords,
                    BigDecimal pace, String form, String outcome, List<InvBeat> inventory,
                    String tags) {
            this(expectedCh, minWords, maxWords, pace, form, outcome, inventory, tags, false);
        }
    }

    /** ⑥a 幂等推进状态（落 run.step_status.steps.PLAN）：AI 定的章数/章均 + 已完成窗的拍与章快照。 */
    record PlanState(int chapterCount, int chapterAvg, List<Integer> usedSeqs,
                     List<PlanDraft> drafts, int volumeCount) {
        /** 兼容旧调用：未显式给卷数时，按约 12 章一卷估算。 */
        PlanState(int chapterCount, int chapterAvg, List<Integer> usedSeqs, List<PlanDraft> drafts) {
            this(chapterCount, chapterAvg, usedSeqs, drafts, defaultVolumeCount(chapterCount));
        }
    }

    record WindowContext(PlanContext plan, PlanState state, int from, int to, List<InvBeat> remain) {
    }

    /** 读 ⑥a 状态；未开始定规模返回 null。 */
    static PlanState readPlanState(String stepStatus) {
        try {
            JsonNode plan = DeriveWriteService.readTreeSafe(stepStatus).path("steps").path("PLAN");
            if (!plan.path("chapterCount").isNumber()) {
                return null;
            }
            List<Integer> used = new ArrayList<>();
            for (JsonNode s : plan.path("usedSeqs")) {
                used.add(s.asInt());
            }
            List<PlanDraft> drafts = new ArrayList<>();
            for (JsonNode d : plan.path("drafts")) {
                List<Integer> beats = new ArrayList<>();
                for (JsonNode b : d.path("beats")) {
                    beats.add(b.asInt());
                }
                drafts.add(new PlanDraft(d.path("no").asInt(), d.path("titleHint").asText(""),
                        d.path("budget").asInt(), beats));
            }
            return new PlanState(plan.path("chapterCount").asInt(), plan.path("chapterAvg").asInt(),
                    used, drafts, plan.path("volumeCount").asInt(defaultVolumeCount(
                            plan.path("chapterCount").asInt())));
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 PLAN 段写回 step_status（保留 GATE/WELD/SAMPLE 等其它段）。 */
    static String mergePlanState(String stepStatus, PlanState st) {
        ObjectNode root;
        try {
            JsonNode parsed = DeriveWriteService.readTreeSafe(stepStatus);
            root = parsed.isObject() ? (ObjectNode) parsed.deepCopy() : STATIC_MAPPER.createObjectNode();
        } catch (Exception e) {
            root = STATIC_MAPPER.createObjectNode();
        }
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        ObjectNode plan = steps.putObject("PLAN");
        plan.put("chapterCount", st.chapterCount());
        plan.put("chapterAvg", st.chapterAvg());
        plan.put("volumeCount", st.volumeCount());
        var used = plan.putArray("usedSeqs");
        for (Integer s : st.usedSeqs()) {
            used.add(s);
        }
        var drafts = plan.putArray("drafts");
        for (PlanDraft d : st.drafts()) {
            var n = drafts.addObject();
            n.put("no", d.no());
            n.put("titleHint", d.titleHint());
            n.put("budget", d.budget());
            var beats = n.putArray("beats");
            for (Integer s : d.beats()) {
                beats.add(s);
            }
        }
        return root.toString();
    }

    // ---- 辅助 ----

    Map<Long, LabelIndexDO> labelMap(String entryType) {
        Map<Long, LabelIndexDO> out = new LinkedHashMap<>();
        for (LabelIndexDO l : labelIndexMapper.selectList(new LambdaQueryWrapper<LabelIndexDO>()
                .eq(LabelIndexDO::getEntryType, entryType)
                .orderByAsc(LabelIndexDO::getId))) {
            out.put(l.getEntryTypeId(), l);
        }
        return out;
    }

    static String slotOf(LabelIndexDO l) {
        if (l == null || l.getArcFit() == null) {
            return "中段";
        }
        try {
            JsonNode n = STATIC_MAPPER.readTree(l.getArcFit());
            String best = "中段";
            double bestV = Double.NEGATIVE_INFINITY;
            for (String s : List.of("开头", "中段", "转折", "收尾")) {
                double v = n.path(s).asDouble(0);
                if (v > bestV) {
                    bestV = v;
                    best = s;
                }
            }
            return best;
        } catch (Exception e) {
            return "中段";
        }
    }

    /** 文风卡传播（v3.6 方案B）：第一本抽中弧所在源书的 style_profile → 成品书 story_settings；无卡跳过。 */
    String pickedOutlineStyleCard(List<Long> arcIds) {
        if (arcIds.isEmpty()) {
            return null;
        }
        AssetOutlineDO first = outlineMapper.selectById(arcIds.get(0));
        if (first == null || first.getSrcNovelId() == null) {
            return null;
        }
        NovelDO src = novelMapper.selectById(first.getSrcNovelId());
        JsonNode sp = DeriveWriteService
                .readTreeSafe(src == null ? null : src.getStorySettings())
                .path("style_profile");
        if (!sp.isObject()) {
            return null;
        }
        ObjectNode out = STATIC_MAPPER.createObjectNode();
        out.put("v", 1);
        out.set("style_profile", sp);
        return out.toString();
    }

    static List<Long> jsonLongArray(String json) {
        List<Long> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode n = STATIC_MAPPER.readTree(json);
            n.forEach(x -> out.add(x.asLong()));
        } catch (Exception ignore) {
            // 坏 jsonb 视为空
        }
        return out;
    }

    private static JsonNode readTreeSafe(String json) {
        try {
            return STATIC_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return STATIC_MAPPER.createObjectNode();
        }
    }
}
