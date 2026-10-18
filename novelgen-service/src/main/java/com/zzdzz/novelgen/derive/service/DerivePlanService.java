package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    /** ⑥a 每窗章数（2026-10-18 夜：单次输出规模封顶，章数放大到 60 也不撞模型输出上限）。 */
    static final int PLAN_WINDOW_CHAPTERS = 10;

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
            你在为一部小说判断"篇幅规模"（不是写章纲，也不是写正文）。输入：字数区间、类别、
            结局形状、目标节奏、产品形态、主线、拍清单规模。
            要求：
            1. 自己判断这部书合理的章数与每章字数：快热/短篇偏短章，舒缓/长篇偏长章；
            2. 章数 × 每章字数必须落在输入的字数区间内，并且至少要覆盖区间上限的 80%
               （别只贴着下限交差——要给到目标字数，章数就得多）；
            3. 章数 6~80，每章字数 2000~6000（低于 2000 的薄章不要给）；
            4. 章数不得超过输入的拍数——每章至少要分到 1 拍，拍不够就减章（宁可章长一点）；
            5. 只输出规模判断，不写章题、不分配拍。
            输出 JSON 对象：
            {"chapterCount":12,"chapterAvg":2500,"reason":"一句话说明为何是这个篇幅"}
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
            2. beats 只能从"剩余拍清单"取 seq，同一个 seq 只能用一次，每章至少 1 拍；
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
    private final LlmJson llmJson;

    /** 拍清单条目（临时序号 = 输入给 LLM 的 seq）。 */
    record InvBeat(int tempSeq, String source, Long assetId, Long arcId, Integer arcSegNo,
                   String beatType, BigDecimal pacing, String labelText, String arcSlot) {
    }

    record PlanDraft(int no, String titleHint, int budget, List<Integer> beats) {
    }

    /**
     * ⑥a：幂等推进一个单元（SCALE 定规模 → WINDOW 逐窗分配拍 → DONE 收尾）。
     * 每次只发一次 LLM 请求，客户端循环调用至 allDone。
     */
    @Transactional
    public PlanVO plan(long runId) {
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
            long novelId = run.getNovelId() == null ? createNovel(run, arcIds) : run.getNovelId();
            PlanState st = new PlanState(chapterCount, chapterAvg, new ArrayList<>(), new ArrayList<>());
            runMapper.updateStepStatus(runId, mergePlanState(run.getStepStatus(), st));
            log.info("⑥a-0 定规模：runId={} novelId={} 章={} 章均={} 理由={}",
                    runId, novelId, chapterCount, chapterAvg, out.path("reason").asText(""));
            return new PlanVO(runId, novelId, chapterCount, 0, 0, List.of(),
                    "SCALE", 0, totalWindows(chapterCount), 0, 0, false);
        } finally {
            DeriveActivity.end(runId);
        }
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
        int to = Math.min(state.chapterCount(), from + PLAN_WINDOW_CHAPTERS - 1);
        List<InvBeat> remain = inventory.stream().filter(b -> !usedSeqs.contains(b.tempSeq())).toList();
        int windowNo = (from - 1) / PLAN_WINDOW_CHAPTERS + 1;
        List<PlanDraft> win;
        DeriveActivity.begin(runId, "⑥a 分章 第" + windowNo + "/" + totalW + " 窗（章 " + from + "-" + to + "）");
        try {
            PlanContext planCtx = new PlanContext(state.chapterCount(), run.getParamWordsMin(),
                    run.getParamWordsMax(), run.getParamPace(), run.getParamForm(),
                    run.getParamOutcome(), inventory, run.getParamTags(),
                    Boolean.TRUE.equals(run.getLenCheck()));
            WindowContext ctx = new WindowContext(planCtx, state, from, to, remain);
            String user = buildWindowUser(run, inventory, remain, drafts, from, to, state.chapterAvg());
            win = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_PLAN, novelId, null, runId,
                            List.of(LlmPort.Message.system(PLAN_WINDOW_PROMPT), LlmPort.Message.user(user)),
                            0.3),
                    root -> parseWindow(root, ctx), 3);
            persistWindow(runId, novelId, win, inventory);
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

    /** 窗落库：chapters + beat_instance（每窗独立提交，失败不回滚已完成窗）。 */
    void persistWindow(long runId, long novelId, List<PlanDraft> win, List<InvBeat> inventory) {
        int nextSeq = nextBeatSeq(runId);
        for (PlanDraft d : win) {
            ChapterDO ch = new ChapterDO();
            ch.setNovelId(novelId);
            ch.setChapterNo(d.no());
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
        root.put("拍数", inventory.size());
        return root.toString();
    }

    /** ⑥a-1 输入：本窗章号范围 + 剩余拍 + 前窗章概览（衔接用）。 */
    String buildWindowUser(RunDO run, List<InvBeat> inventory, List<InvBeat> remain,
                           List<PlanDraft> drafts, int from, int to, int chapterAvg) {
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
        if (ch > beatCount) {
            throw new LlmJson.Bad("章数 " + ch + " 超过拍数 " + beatCount
                    + "——每章至少分到 1 拍，章数不得超过拍数（宁可把章写长）");
        }
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
        checkTensionDensity(all, ctx.plan(), all.size() >= ctx.state().chapterCount());
        return out;
    }

    /**
     * 张力密度硬闸（2026-10-18，审稿实锤：中段三连命案 + 终局前掏空）：
     * ①连续 ≥3 章整章只有 {事件,反转} → 拒（无情绪沉淀）；
     * ②推理/悬疑且反转拍 ≥2 → 最后 30% 章内至少还有 1 个反转（防过早泄底掏空终局）。
     */
    static void checkTensionDensity(List<PlanDraft> drafts, PlanContext ctx) {
        checkTensionDensity(drafts, ctx, true);
    }

    /**
     * @param complete 是否已含全部章（逐窗推进中途为 false：此时不判"最后 30%"的反转后移，
     *                 否则拿不完整章表算出的尾段会误报）
     */
    static void checkTensionDensity(List<PlanDraft> drafts, PlanContext ctx, boolean complete) {
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
                String msg = "张力沉淀：第 " + (d.no() - 2) + "–" + d.no()
                        + " 章连续 3 章整章拍型全是 {事件,反转}——重击之间必须插入铺垫/情绪/抉择章";
                if (Boolean.TRUE.equals(DIRECT_PASS.get())) {
                    log.warn("直通免审：{}（降级留痕，不拦）", msg);
                    return;
                }
                throw new LlmJson.Bad(msg);
            }
        }
        String tags = ctx.tags() == null ? "" : ctx.tags();
        boolean reasoning = tags.contains("推理") || tags.contains("悬疑");
        if (!complete || !reasoning || drafts.isEmpty()) {
            return;
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
            return;
        }
        int tailFrom = (int) Math.floor(drafts.size() * 0.7);
        if (tailFrom >= drafts.size()) {
            return;
        }
        boolean tailHasReverse = false;
        for (int i = tailFrom; i < drafts.size(); i++) {
            for (Integer s : drafts.get(i).beats()) {
                if ("反转".equals(ctx.inventory().get(s - 1).beatType())) {
                    tailHasReverse = true;
                }
            }
        }
        if (!tailHasReverse) {
            String msg = "反转后移：推理/悬疑且反转拍=" + reverseTotal
                    + "，最后 30% 章（第 " + (drafts.get(tailFrom).no()) + " 章起）没有任何反转——"
                    + "至少保留 1 个反转给终局段，防止过早掏空悬念";
            if (Boolean.TRUE.equals(DIRECT_PASS.get())) {
                log.warn("直通免审：{}（降级留痕，不拦）", msg);
                return;
            }
            throw new LlmJson.Bad(msg);
        }
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
    record PlanState(int chapterCount, int chapterAvg, List<Integer> usedSeqs, List<PlanDraft> drafts) {
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
                    used, drafts);
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
}
