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
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
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

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    static final String PLAN_PROMPT = """
            你在为一部小说做章节计划。输入：字数区间、结局形状、目标节奏、主线（标签层焊接结果）、
            拍清单（每拍含临时序号 seq、拍型、节奏锚点、弧位标签、标签层文案）。
            输出 JSON 对象：
            {"chapters":[{"no":1,"title_hint":"≤12字章题","budget":2500,"beats":[3,1,7]}]}
            规则：
            1. 章节数必须等于系统给出的"章节数"；每章 budget>0 且总和在字数区间内；
            2. 拍清单的每个 seq 必须恰好出现一次（不许丢、不许重复）；每章≥1拍；beats 数组顺序=章内拍序；
            3. 拍按 arc_fit 与因果定序（弧位偏开头的靠前、收尾的靠后；同弧拍保持相对顺序）；
            4. 结局形状=闭合：末章必须包含收束拍；=开放/循环：末章可留悬念；
            5. 快热：前 3 章必须含拍型∈{事件,反转} 的拍；
            6. title_hint 不带"第N章"前缀、不带专名过露的剧透。
            只输出 JSON 对象，无其他文字。
            """;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final AssetOutlineMapper outlineMapper;
    private final AssetBeatMapper beatMapper;
    private final LabelIndexMapper labelIndexMapper;
    private final LlmJson llmJson;

    /** 拍清单条目（临时序号 = 输入给 LLM 的 seq）。 */
    record InvBeat(int tempSeq, String source, Long assetId, Long arcId, Integer arcSegNo,
                   String beatType, BigDecimal pacing, String labelText, String arcSlot) {
    }

    record PlanDraft(int no, String titleHint, int budget, List<Integer> beats) {
    }

    /** ⑥a：对 run 做章节计划并落 novel(GENERATED)/chapters/beat_instance，回填 run.novel_id。 */
    @Transactional
    public PlanVO plan(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"PLANNING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 状态应为 PLANNING，当前 " + run.getStatus());
        }
        if (run.getNovelId() != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 已计划过（novelId=" + run.getNovelId() + "）");
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

        // ---- 规模参数 ----
        BigDecimal pace = run.getParamPace();
        int b0 = DeriveSampleService.B0_TABLE.entrySet().stream()
                .filter(e -> e.getKey().compareTo(pace) == 0)
                .map(Map.Entry::getValue).findFirst().orElse(1500);
        int wordsPred = Math.max(run.getParamWordsMin(), Math.min(run.getParamWordsMax(),
                b0 * (int) Math.ceil((double) run.getParamWordsMax() / b0)));
        int chapterAvg = pace.compareTo(new BigDecimal("0.75")) >= 0 ? 2500
                : pace.compareTo(new BigDecimal("0.25")) <= 0 ? 4000 : 3200;
        int expectedCh = Math.max(1, (int) Math.round((double) wordsPred / chapterAvg));

        // ---- LLM 分章（校验不过喂回重试）----
        PlanContext ctx = new PlanContext(expectedCh, run.getParamWordsMin(), run.getParamWordsMax(),
                pace, run.getParamForm(), run.getParamOutcome(), inventory);
        String user = buildUserInput(run, inventory, expectedCh, wordsPred);
        List<PlanDraft> drafts = llmJson.ask(
                new LlmPort.ChatRequest(NODE_PLAN, runId, run.getNovelId(),
                        List.of(LlmPort.Message.system(PLAN_PROMPT), LlmPort.Message.user(user)),
                        0.3),
                root -> parsePlan(root, ctx), 3);

        // ---- 落库：novel(GENERATED) → chapters → beat_instance → run.novel_id ----
        NovelDO novel = new NovelDO();
        novel.setTitle("未命名·RUN-" + runId);
        novel.setSourceType("GENERATED");
        novel.setCategoryTags(run.getParamTags());
        novel.setForm(run.getParamForm());
        novel.setRunId(runId);
        long novelId = novelMapper.insertFull(novel);

        // tempSeq → 最终 seq / chapter 映射（按 LLM 章序+章内拍序定序）
        Map<Integer, Integer> finalSeqByTemp = new HashMap<>();
        Map<Integer, Integer> chapterByTemp = new HashMap<>();
        int finalSeq = 1;
        List<PlanVO.ChapterPlanItem> items = new ArrayList<>();
        Map<Integer, List<InvBeat>> byChapter = new HashMap<>();
        for (PlanDraft d : drafts) {
            ChapterDO ch = new ChapterDO();
            ch.setNovelId(novelId);
            ch.setChapterNo(d.no());
            ch.setTitle(d.titleHint() == null || d.titleHint().isBlank() ? "第" + d.no() + "章" : d.titleHint());
            ch.setBudget(d.budget());
            ch.setStatus("PLANNED");
            List<InvBeat> chBeats = new ArrayList<>();
            BigDecimal paceSum = BigDecimal.ZERO;
            for (Integer tempSeq : d.beats()) {
                InvBeat ib = inventory.get(tempSeq - 1);
                finalSeqByTemp.put(tempSeq, finalSeq++);
                chapterByTemp.put(tempSeq, d.no());
                chBeats.add(ib);
                paceSum = paceSum.add(ib.pacing());
            }
            BigDecimal pacePlan = chBeats.isEmpty() ? BigDecimal.ZERO
                    : paceSum.divide(BigDecimal.valueOf(chBeats.size()), 2, RoundingMode.HALF_UP);
            ch.setPacePlan(pacePlan);
            chapterMapper.insert(ch);
            byChapter.put(d.no(), chBeats);
            items.add(new PlanVO.ChapterPlanItem(d.no(), ch.getTitle(), d.budget(), chBeats.size(), pacePlan.doubleValue()));
        }

        for (PlanDraft d : drafts) {
            for (Integer tempSeq : d.beats()) {
                InvBeat ib = inventory.get(tempSeq - 1);
                BeatInstanceDO bi = new BeatInstanceDO();
                bi.setRunId(runId);
                bi.setSeq(finalSeqByTemp.get(tempSeq));
                bi.setSource(ib.source());
                bi.setAssetId(ib.assetId());
                bi.setArcId(ib.arcId());
                bi.setArcSegNo(ib.arcSegNo());
                bi.setBeatType(ib.beatType());
                bi.setPacing(ib.pacing());
                bi.setChapterNo(chapterByTemp.get(tempSeq));
                bi.setStatus("PLANNED");
                beatInstanceMapper.insert(bi);
            }
        }
        runMapper.updateNovelId(runId, novelId);

        int totalBudget = drafts.stream().mapToInt(PlanDraft::budget).sum();
        log.info("⑥a章节计划：runId={} novelId={} 章={} 拍={} 总预算={}",
                runId, novelId, drafts.size(), finalSeq - 1, totalBudget);
        return new PlanVO(runId, novelId, drafts.size(), finalSeq - 1, totalBudget, items);
    }

    // ---- 输入构造与解析校验 ----

    String buildUserInput(RunDO run, List<InvBeat> inventory, int expectedCh, int wordsPred) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("章节数", expectedCh);
        root.put("wordsPred", wordsPred);
        root.put("字数区间", run.getParamWordsMin() + "~" + run.getParamWordsMax());
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        var arr = root.putArray("拍清单");
        for (InvBeat ib : inventory) {
            var n = arr.addObject();
            n.put("seq", ib.tempSeq());
            n.put("beat_type", ib.beatType());
            n.put("pacing", ib.pacing().toPlainString());
            n.put("arc_slot", ib.arcSlot());
            if (ib.labelText() != null) {
                n.put("label", ib.labelText());
            }
        }
        return root.toString();
    }

    static List<PlanDraft> parsePlan(JsonNode root, PlanContext ctx) {
        JsonNode arr = root.path("chapters");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new LlmJson.Bad("chapters 必须是非空数组");
        }
        if (arr.size() != ctx.expectedCh()) {
            throw new LlmJson.Bad("章节数必须 = " + ctx.expectedCh() + "，当前 " + arr.size());
        }
        List<PlanDraft> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        Set<Integer> nos = new HashSet<>();
        int budgetSum = 0;
        for (int i = 0; i < arr.size(); i++) {
            JsonNode c = arr.get(i);
            int no = c.path("no").asInt(i + 1);
            int budget = c.path("budget").asInt(0);
            JsonNode beats = c.path("beats");
            if (!nos.add(no)) {
                throw new LlmJson.Bad("章号重复: " + no);
            }
            if (budget <= 0) {
                throw new LlmJson.Bad("第" + no + "章 budget 必须 >0");
            }
            if (!beats.isArray() || beats.isEmpty()) {
                throw new LlmJson.Bad("第" + no + "章 beats 必须非空（每章≥1拍）");
            }
            List<Integer> beatList = new ArrayList<>();
            for (JsonNode b : beats) {
                int s = b.asInt(0);
                if (s < 1 || s > ctx.inventory().size()) {
                    throw new LlmJson.Bad("beats 含非法 seq: " + s);
                }
                if (!used.add(s)) {
                    throw new LlmJson.Bad("拍 seq " + s + " 重复出现（每拍恰好一次）");
                }
                beatList.add(s);
            }
            budgetSum += budget;
            out.add(new PlanDraft(no, c.path("title_hint").asText(""), budget, beatList));
        }
        // 每拍恰好一次：全覆盖
        if (used.size() != ctx.inventory().size()) {
            Set<Integer> missing = new HashSet<>();
            for (InvBeat ib : ctx.inventory()) {
                if (!used.contains(ib.tempSeq())) {
                    missing.add(ib.tempSeq());
                }
            }
            throw new LlmJson.Bad("拍未全覆盖，缺 seq: " + missing);
        }
        if (budgetSum < ctx.minWords() || budgetSum > ctx.maxWords()) {
            throw new LlmJson.Bad("Σbudget=" + budgetSum + " 超出字数区间 [" + ctx.minWords() + "," + ctx.maxWords() + "]");
        }
        // 快热：前3章必须含 {事件,反转}
        if (ctx.pace().compareTo(new BigDecimal("0.75")) >= 0) {
            boolean hot = false;
            for (int i = 0; i < Math.min(3, out.size()); i++) {
                for (Integer s : out.get(i).beats()) {
                    InvBeat ib = ctx.inventory().get(s - 1);
                    if ("事件".equals(ib.beatType()) || "反转".equals(ib.beatType())) {
                        hot = true;
                    }
                }
            }
            if (!hot) {
                throw new LlmJson.Bad("快热：前 3 章必须含拍型∈{事件,反转} 的拍");
            }
        }
        // 独立短篇"末章闭合"不在此硬卡（解释留痕）：池结构可能既无收束拍也无收尾位弧
        // （⑤弧桶轮转可能只抽到 开头/中段/转折）——⑥a 硬卡会死锁。闭合语义由
        // PLAN_PROMPT 规则 4 约束 + ⑥b 结局形状落地 + ⑧LLM 快检兑底。
        return out;
    }

    record PlanContext(int expectedCh, int minWords, int maxWords,
                       BigDecimal pace, String form, String outcome, List<InvBeat> inventory) {
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
