package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.DomainCellMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.dto.derive.RunCreateDTO;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.AssetWorldDO;
import com.zzdzz.novelgen.model.entity.derive.CalibrationDO;
import com.zzdzz.novelgen.model.entity.derive.DomainCellDO;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO;
import com.zzdzz.novelgen.model.vo.derive.RunVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * ⑤约束采样（v3.2 §5）：5.1 规模预测 + 5.2 六步过滤抽样（seed 内确定性）+ 5.3 焊接（K≥2 LLM）。
 *
 * <p>确定性口径：全部随机决策走**同一个 {@code new Random(seed)}**，调用顺序固定
 * （form/outcome 默认抽取 → 弧桶 → 补差拍 → 世界观 → 域格），候选列表先按 id 升序——
 * 同 seed 同结果（复现三要素：seed + param + 池内容）。
 *
 * <p>解释留痕（v3.2 压缩处的实现口径）：
 * <ul>
 *   <li>类别降级链按字面执行"丢最弱次标签→θ降档"全部档位后取**并集**（每档命中数进 trace）；</li>
 *   <li>节奏滑动为嵌套集合：逐级放宽直到够数取**当级集合**，最后一级=全部；</li>
 *   <li>拍型过滤不足时按节奏级结果兜底（spec 原文）；t=0.50 不做拍型过滤；</li>
 *   <li>弧桶 K&gt;1 按 开头→中段→转折→收尾 轮转各抽 1（桶空全局补位），余额全池 seed 取；
 *       K=1 全池优胜半区 seed 取 1；</li>
 *   <li>同构弧去重 = md5(funcTag|cat签名) 查 work_fingerprint（已登记即跳过；⑤不登记，登记在⑧）；</li>
 *   <li>域格"系列同 grp 加抽邻格"属系列书逻辑，单卷 run 不启用。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveSampleService {

    public static final String NODE_WELD = "derive_weld";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    /** B0 锚点换算表（v3.2 §5.1 初值；calibration 每 10 轮滚动回归后更新）。 */
    static final Map<BigDecimal, Integer> B0_TABLE = Map.of(
            new BigDecimal("0.00"), 2500,
            new BigDecimal("0.25"), 2500,
            new BigDecimal("0.50"), 1500,
            new BigDecimal("0.75"), 1100,
            new BigDecimal("1.00"), 1100);

    static final List<String> SLOTS = List.of("开头", "中段", "转折", "收尾");

    /** 5.3 焊接提示词（v3.2 原文 + 对象根输出契约）。 */
    static final String WELD_PROMPT = """
            把以下标签层故事线段焊成一条主线：段间因果贯通、主角一致、无断裂；
            保持 [功能标签] + 关系式 ⇒ 状态变化 格式；禁止引入具体域词；
            结局形状按给定收束方式收束。输出编号 O-F01 起。
            只输出 JSON 对象：{"welded":{"code":"O-F01","text":"[标签] 关系式 ⇒ 状态变化"}}
            """;

    private final AssetOutlineMapper outlineMapper;
    private final AssetBeatMapper beatMapper;
    private final AssetWorldMapper worldMapper;
    private final LabelIndexMapper labelIndexMapper;
    private final DomainCellMapper domainCellMapper;
    private final RunMapper runMapper;
    private final WorkFingerprintMapper fingerprintMapper;
    private final com.zzdzz.novelgen.derive.mapper.CalibrationMapper calibrationMapper;
    private final LlmJson llmJson;
    private final ObjectMapper om;

    // ================= 主流程 =================

    /** 建 run：5.1 → 5.2 → 插档（SAMPLING）→ 5.3 焊接 → 回填（PLANNING）。无事务（LLM 不进事务）。 */
    public RunVO create(RunCreateDTO dto) {
        validate(dto);
        long seed = dto.seed() != null ? dto.seed() : ThreadLocalRandom.current().nextLong();
        Random rnd = new Random(seed);

        // ---- 读池（全池跨书；只取已打标且有标签层的条目）----
        List<Row> outlineRows = loadRows("OUTLINE", true);
        List<Row> beatRows = loadRows("BEAT", true);
        List<Row> worldRows = loadRows("WORLD", false);
        if (outlineRows.isEmpty() || beatRows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "素材池为空或未打标，先走完 ②③④");
        }

        // ---- 5.1 规模预测 ----
        BigDecimal pace = new BigDecimal(dto.pace());
        int b0 = B0_TABLE.entrySet().stream()
                .filter(e -> e.getKey().compareTo(pace) == 0)
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, "pace 必须是五锚点"));
        double pMaxD = (double) dto.wordsMax() / b0;
        int pMin = (int) Math.ceil((double) dto.wordsMin() / b0);
        int pMax = (int) Math.ceil(pMaxD);
        int kTarget = Math.max(1, Math.min(4, (int) Math.ceil(pMaxD / 7)));
        int mTarget = Math.max(0, pMax - kTarget * 6);
        int wordsPred = Math.max(dto.wordsMin(), Math.min(dto.wordsMax(), b0 * (int) Math.ceil(pMaxD)));

        // ---- form/outcome 缺省 seed 抽（固定最先，调用序确定）----
        List<String> forms = List.of("LONG", "SHORT_STANDALONE", "LIGHT_NOVEL", "LONG_INNER_SHORT", "LN_INNER_SHORT");
        List<String> outcomes = List.of("循环", "开放", "闭合");
        String form = dto.form() != null && !dto.form().isBlank() ? dto.form()
                : forms.get(rnd.nextInt(forms.size()));
        String outcome = dto.outcome() != null && !dto.outcome().isBlank() ? dto.outcome()
                : outcomes.get(rnd.nextInt(outcomes.size()));

        ObjectNode trace = om.createObjectNode();

        // ---- 5.2.1 类别过滤（丢最弱次标签→θ降档，全档位取并集，每档命中进 trace）----
        List<String> tags = dto.tags();
        Set<Long> catOutIds = new LinkedHashSet<>();
        Set<Long> catBeatIds = new LinkedHashSet<>();
        ArrayNode catArr = trace.putArray("cat");
        List<String> secondaries = new ArrayList<>(tags.subList(1, tags.size()));
        secondaries.sort(Comparator.comparingDouble(t -> avgCat(t, outlineRows, beatRows))); // 最弱在前=先丢
        List<List<String>> tagStages = new ArrayList<>();
        for (int drop = 0; drop <= secondaries.size(); drop++) {
            List<String> used = new ArrayList<>();
            used.add(tags.get(0));
            used.addAll(secondaries.subList(drop, secondaries.size()));
            tagStages.add(used);
        }
        for (int s = 0; s < tagStages.size(); s++) {
            addCatStage(catArr, "drop" + s + "@0.55", tagStages.get(s), 0.55,
                    outlineRows, beatRows, catOutIds, catBeatIds);
        }
        for (double theta : List.of(0.55, 0.40, 0.30)) {
            addCatStage(catArr, "full@" + theta, tags, theta, outlineRows, beatRows, catOutIds, catBeatIds);
        }
        List<Row> catUnionOutlines = outlineRows.stream().filter(r -> catOutIds.contains(r.id())).toList();
        List<Row> catUnionBeats = beatRows.stream().filter(r -> catBeatIds.contains(r.id())).toList();

        // ---- 5.2.2 节奏过滤（锚点滑动，嵌套逐级放宽取当级集合）----
        ArrayNode paceArr = trace.putArray("pace");
        Set<Long> paceOutIds = Set.of();
        Set<Long> paceBeatIds = Set.of();
        int chosen = -1;
        List<Predicate<Row>> levels = paceLevels(pace);
        for (int i = 0; i < levels.size(); i++) {
            Predicate<Row> pred = levels.get(i);
            Set<Long> o = filterIds(catUnionOutlines, pred);
            Set<Long> b = filterIds(catUnionBeats, pred);
            ObjectNode n = paceArr.addObject();
            n.put("level", i);
            n.put("outline", o.size());
            n.put("beat", b.size());
            paceOutIds = o;
            paceBeatIds = b;
            chosen = i;
            if (o.size() >= kTarget && b.size() >= mTarget) {
                break;
            }
        }
        trace.put("paceLevelChosen", chosen);
        // 循环后再取 final 引用（paceOutIds 在循环内被重赋，非 effectively-final，lambda 不能直接捕获）
        final Set<Long> chosenPaceOut = paceOutIds;
        final Set<Long> chosenPaceBeat = paceBeatIds;
        List<Row> paceOutlines = catUnionOutlines.stream().filter(r -> chosenPaceOut.contains(r.id())).toList();
        List<Row> paceBeats = catUnionBeats.stream().filter(r -> chosenPaceBeat.contains(r.id())).toList();

        // ---- 5.2.3 拍型过滤（不足按节奏级兜底；t=0.50 不过滤）----
        Set<String> want = pace.compareTo(new BigDecimal("0.50")) > 0 ? Set.of("事件", "反转")
                : pace.compareTo(new BigDecimal("0.50")) < 0 ? Set.of("铺垫", "情绪", "抉择") : null;
        List<Row> finalOutlines = paceOutlines;
        List<Row> finalBeats = paceBeats;
        boolean btFallback = false;
        if (want != null) {
            List<Row> btOut = paceOutlines.stream()
                    .filter(r -> want.contains(r.beatType())).toList();
            List<Row> btBeat = paceBeats.stream()
                    .filter(r -> want.contains(r.beatType())).toList();
            if (btOut.size() >= kTarget && btBeat.size() >= mTarget) {
                finalOutlines = btOut;
                finalBeats = btBeat;
            } else {
                btFallback = true;
            }
        }
        ObjectNode btNode = trace.putObject("beat_type");
        btNode.put("applied", want != null);
        btNode.put("fallback", btFallback);
        btNode.put("outline", finalOutlines.size());
        btNode.put("beat", finalBeats.size());

        // ---- 5.2.4+5 弧桶分配（同构弧登记跳过、run 内唯一）+ 补差拍 ----
        // 防复刻开关：默认关=不按历史指纹阻断（一致性优先），开=九轮原口径
        boolean antiRepro = Boolean.TRUE.equals(dto.antiRepro());
        Set<String> registered = antiRepro
                ? fingerprintMapper.selectList(new LambdaQueryWrapper<>())
                        .stream().map(WorkFingerprintDO::getFingerprint).collect(Collectors.toSet())
                : Set.of();
        List<Row> pickedOutlines = pickOutlines(finalOutlines, kTarget, tags, registered, rnd);
        List<Row> beatShuffle = new ArrayList<>(finalBeats);
        Collections.shuffle(beatShuffle, rnd);
        List<Row> pickedBeats = beatShuffle.stream().limit(mTarget).toList();

        // ---- 5.2.6 世界观 + 域格（主类别存在即候选，cats[主] 加权 seed 抽 1）----
        String main = tags.get(0);
        Row world = weightedPick(worldRows.stream()
                .filter(r -> r.cat().containsKey(main)).toList(), main, rnd);
        List<DomainCellDO> cellEligible = domainCellMapper.selectList(new LambdaQueryWrapper<DomainCellDO>()
                        .eq(DomainCellDO::getEnabled, true)
                        .orderByAsc(DomainCellDO::getId)).stream()
                .filter(c -> parseCatJson(c.getCats()).containsKey(main))
                .toList();
        DomainCellDO cell = weightedPickCell(cellEligible, main, rnd);
        if (cell == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "域格池中没有承载主标签「" + main + "」的启用域格——先走 ④c 域格归纳");
        }

        // ---- 插档（SAMPLING；jsonb 七列走 insertFull）----
        RunDO run = new RunDO();
        run.setSeed(seed);
        run.setParamWordsMin(dto.wordsMin());
        run.setParamWordsMax(dto.wordsMax());
        run.setParamTags(tagsJson(tags));
        run.setParamPace(pace);
        run.setParamForm(form);
        run.setParamOutcome(outcome);
        run.setDomainId(cell.getId());
        run.setDomainSnapshot(cellSnapshot(cell));
        run.setB0(b0);
        run.setPMin(pMin);
        run.setPMax(pMax);
        run.setKOutline(pickedOutlines.size());
        run.setMBeat(pickedBeats.size());
        run.setToleranceTrace(trace.toString());
        run.setPickedOutlineIds(idArrayJson(pickedOutlines));
        run.setPickedBeatIds(idArrayJson(pickedBeats));
        run.setPickedWorldId(world == null ? null : world.id());
        run.setStatus("SAMPLING");
        run.setQcRound(0);
        run.setAntiRepro(antiRepro);
        run.setStepStatus("{\"v\":1,\"steps\":{\"SAMPLE\":{\"status\":\"RUNNING\"}}}");
        long runId = runMapper.insertFull(run);

        // ---- 5.3 焊接（K≥2 时 LLM；K<2 直接进 PLANNING）----
        String weldedCode = null;
        if (pickedOutlines.size() >= 2) {
            String segments = pickedOutlines.stream()
                    .map(r -> r.code() + " " + r.labelText())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            JsonNode welded = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_WELD, null, null,
                            List.of(LlmPort.Message.system(WELD_PROMPT),
                                    LlmPort.Message.user("收束方式：" + outcome + "\n段列表：\n" + segments)),
                            0.3),
                    DeriveSampleService::parseWelded, 3);
            weldedCode = welded.path("code").asText();
            ObjectNode w = om.createObjectNode();
            w.put("code", weldedCode);
            w.put("text", welded.path("text").asText());
            runMapper.updateWeld(runId, w.toString());
        } else {
            runMapper.updateWeld(runId, null);
        }

        // ---- 标定行（⑤预测；⑧回填实测——v1.2 §3.14：words_pred 落这里，不落 run）----
        CalibrationDO cal = new CalibrationDO();
        cal.setRunId(runId);
        cal.setPace(pace);
        cal.setB0(b0);
        cal.setPPred(pMax);
        cal.setWordsPred(wordsPred);
        calibrationMapper.insert(cal);

        log.info("⑤采样：runId={} seed={} B0={} P=[{},{}] K={} M={} wordsPred={} 域格={} 焊接={} 回退拍型={}",
                runId, seed, b0, pMin, pMax, pickedOutlines.size(), pickedBeats.size(),
                wordsPred, cell.getName(), weldedCode == null ? "无" : weldedCode, btFallback);
        return new RunVO(runId, seed, b0, pMin, pMax, wordsPred,
                pickedOutlines.size(), pickedBeats.size(), form, outcome,
                cell.getName(), weldedCode, "PLANNING");
    }

    // ================= 池加载 =================

    /** 按 entry_type 装载素材+标签（asset 有 label_text 才算③完成；world 不要求 funcTag）。 */
    List<Row> loadRows(String entryType, boolean requireLabelText) {
        Map<Long, LabelIndexDO> labels = new LinkedHashMap<>();
        for (LabelIndexDO l : labelIndexMapper.selectList(new LambdaQueryWrapper<LabelIndexDO>()
                .eq(LabelIndexDO::getEntryType, entryType)
                .orderByAsc(LabelIndexDO::getId))) {
            labels.put(l.getEntryTypeId(), l);
        }
        List<Row> out = new ArrayList<>();
        if ("OUTLINE".equals(entryType)) {
            for (AssetOutlineDO a : outlineMapper.selectList(new LambdaQueryWrapper<AssetOutlineDO>()
                    .orderByAsc(AssetOutlineDO::getId))) {
                LabelIndexDO l = labels.get(a.getId());
                if (l != null && (!requireLabelText || a.getLabelText() != null)) {
                    out.add(Row.of(a.getId(), a.getCode(), a.getLabelText(), a.getFuncTag(), l));
                }
            }
        } else if ("BEAT".equals(entryType)) {
            for (AssetBeatDO b : beatMapper.selectList(new LambdaQueryWrapper<AssetBeatDO>()
                    .orderByAsc(AssetBeatDO::getId))) {
                LabelIndexDO l = labels.get(b.getId());
                if (l != null && (!requireLabelText || b.getLabelText() != null)) {
                    out.add(Row.of(b.getId(), b.getCode(), b.getLabelText(), b.getFuncTag(), l));
                }
            }
        } else {
            for (AssetWorldDO w : worldMapper.selectList(new LambdaQueryWrapper<AssetWorldDO>()
                    .orderByAsc(AssetWorldDO::getId))) {
                LabelIndexDO l = labels.get(w.getId());
                if (l != null) {
                    out.add(Row.of(w.getId(), w.getCode(), w.getLabelText(), null, l));
                }
            }
        }
        return out;
    }

    // ================= 5.2.4 弧桶分配 =================

    List<Row> pickOutlines(List<Row> pool, int k, List<String> tags, Set<String> registeredFp, Random rnd) {
        if (pool.isEmpty() || k <= 0) {
            return List.of();
        }
        Map<String, List<Row>> buckets = new LinkedHashMap<>();
        for (String s : SLOTS) {
            buckets.put(s, new ArrayList<>());
        }
        for (Row r : pool) {
            buckets.get(slotOf(r)).add(r);
        }
        for (List<Row> b : buckets.values()) {
            b.sort(Comparator.comparingDouble((Row r) -> -arcFitOf(r, slotOf(r)))
                    .thenComparingDouble(r -> -score(tags, r))
                    .thenComparingLong(Row::id));
            if (b.size() > 1) {
                // 优胜半区（留前 ceil(n/2)）
                List<Row> top = new ArrayList<>(b.subList(0, (b.size() + 1) / 2));
                b.clear();
                b.addAll(top);
            }
        }
        List<Row> picked = new ArrayList<>();
        Set<Long> used = new LinkedHashSet<>();
        Predicate<Row> eligible = r -> !used.contains(r.id()) && !isomorphicBlocked(r, registeredFp);

        if (k == 1) {
            List<Row> union = buckets.values().stream().flatMap(List::stream).filter(eligible).toList();
            if (!union.isEmpty()) {
                Row r = union.get(rnd.nextInt(union.size()));
                picked.add(r);
                used.add(r.id());
            }
        } else {
            for (String slot : SLOTS) {
                if (picked.size() >= k) {
                    break;
                }
                List<Row> cand = buckets.get(slot).stream().filter(eligible).toList();
                if (cand.isEmpty()) {
                    cand = pool.stream().filter(eligible).toList(); // 桶空全局补位
                    if (cand.isEmpty()) {
                        break;
                    }
                }
                Row r = cand.get(rnd.nextInt(cand.size()));
                picked.add(r);
                used.add(r.id());
            }
            List<Row> rest = pool.stream().filter(eligible).collect(Collectors.toList());
            Collections.shuffle(rest, rnd);
            for (Row r : rest) {
                if (picked.size() >= k) {
                    break;
                }
                picked.add(r);
                used.add(r.id());
            }
        }
        return picked;
    }

    static String slotOf(Row r) {
        String best = SLOTS.get(0);
        double bestV = Double.NEGATIVE_INFINITY;
        for (String s : SLOTS) {
            double v = r.arcFit().getOrDefault(s, 0.0);
            if (v > bestV) {
                bestV = v;
                best = s;
            }
        }
        return best;
    }

    static double arcFitOf(Row r, String slot) {
        return r.arcFit().getOrDefault(slot, 0.0);
    }

    static boolean isomorphicBlocked(Row r, Set<String> registered) {
        return registered.contains(fingerprintOf(r));
    }

    static String fingerprintOf(Row r) {
        StringBuilder sig = new StringBuilder();
        r.cat().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sig.append(e.getKey()).append('=')
                        .append(BigDecimal.valueOf(e.getValue()).setScale(1, RoundingMode.HALF_UP)).append(';'));
        return md5((r.funcTag() == null ? "" : r.funcTag()) + "|" + sig);
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================= 节奏滑动 =================

    static List<Predicate<Row>> paceLevels(BigDecimal t) {
        List<Predicate<Row>> levels = new ArrayList<>();
        if (t.compareTo(new BigDecimal("0.50")) > 0) {
            for (double th = t.doubleValue(); th >= -0.01; th -= 0.25) {
                double f = Math.max(0, th);
                double floor = f;
                levels.add(r -> r.pacing().doubleValue() >= floor - 1e-9);
                if (f <= 0) {
                    break;
                }
            }
        } else if (t.compareTo(new BigDecimal("0.50")) < 0) {
            for (double th = t.doubleValue(); th <= 1.01; th += 0.25) {
                double f = Math.min(1, th);
                double ceil = f;
                levels.add(r -> r.pacing().doubleValue() <= ceil + 1e-9);
                if (f >= 1) {
                    break;
                }
            }
        } else {
            levels.add(r -> Math.abs(r.pacing().doubleValue() - 0.5) <= 0.25 + 1e-9);
            levels.add(r -> Math.abs(r.pacing().doubleValue() - 0.5) <= 0.50 + 1e-9); // 覆盖全部
        }
        return levels;
    }

    static Set<Long> filterIds(List<Row> rows, Predicate<Row> pred) {
        Set<Long> out = new LinkedHashSet<>();
        for (Row r : rows) {
            if (pred.test(r)) {
                out.add(r.id());
            }
        }
        return out;
    }

    // ================= 类别过滤 =================

    void addCatStage(ArrayNode arr, String stageName, List<String> usedTags, double theta,
                     List<Row> outlines, List<Row> beats, Set<Long> outAcc, Set<Long> beatAcc) {
        int o = 0;
        int b = 0;
        for (Row r : outlines) {
            if (score(usedTags, r) >= theta) {
                outAcc.add(r.id());
                o++;
            }
        }
        for (Row r : beats) {
            if (score(usedTags, r) >= theta) {
                beatAcc.add(r.id());
                b++;
            }
        }
        ObjectNode n = arr.addObject();
        n.put("stage", stageName);
        n.put("outline", o);
        n.put("beat", b);
    }

    /** score = 2×cat[主]（主标签恒在 usedTags[0]）+ Σ其余命中标签。 */
    static double score(List<String> usedTags, Row r) {
        if (usedTags.isEmpty()) {
            return 0;
        }
        double s = 0;
        Double m = r.cat().get(usedTags.get(0));
        if (m != null) {
            s += 2 * m;
        }
        for (int i = 1; i < usedTags.size(); i++) {
            Double v = r.cat().get(usedTags.get(i));
            if (v != null) {
                s += v;
            }
        }
        return s;
    }

    static double avgCat(String tag, List<Row> a, List<Row> b) {
        double sum = 0;
        int n = 0;
        for (Row r : a) {
            Double v = r.cat().get(tag);
            if (v != null) {
                sum += v;
                n++;
            }
        }
        for (Row r : b) {
            Double v = r.cat().get(tag);
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    // ================= 加权抽取 =================

    static <T> T weightedPick(List<T> pool, Random rnd, ToDoubleFunction<T> w) {
        if (pool.isEmpty()) {
            return null;
        }
        double total = 0;
        double[] ws = new double[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            ws[i] = Math.max(0.001, w.applyAsDouble(pool.get(i)));
            total += ws[i];
        }
        double x = rnd.nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            x -= ws[i];
            if (x <= 0) {
                return pool.get(i);
            }
        }
        return pool.get(pool.size() - 1);
    }

    Row weightedPick(List<Row> pool, String tag, Random rnd) {
        return weightedPick(pool, rnd, r -> r.cat().getOrDefault(tag, 0.0));
    }

    DomainCellDO weightedPickCell(List<DomainCellDO> pool, String tag, Random rnd) {
        return weightedPick(pool, rnd, c -> parseCatJson(c.getCats()).getOrDefault(tag, 0.0));
    }

    // ================= 构造与解析 =================

    /** 素材+标签的采样视图（jsonb 在装载时解析一次）。 */
    record Row(long id, String code, String labelText, String funcTag,
               Map<String, Double> cat, BigDecimal pacing, String beatType,
               Map<String, Double> arcFit, boolean standalone) {

        static Row of(long id, String code, String text, String funcTag, LabelIndexDO l) {
            return new Row(id, code, text, funcTag,
                    parseCatJson(l.getCat()), l.getPacing(), l.getBeatType(),
                    parseCatJson(l.getArcFit()), Boolean.TRUE.equals(l.getStandalone()));
        }
    }

    static Map<String, Double> parseCatJson(String json) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode n = STATIC_MAPPER.readTree(json);
            n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asDouble()));
        } catch (Exception e) {
            // 坏 jsonb 视为空——打标落库时已校验，正常不达
        }
        return out;
    }

    void validate(RunCreateDTO dto) {
        if (dto.wordsMin() <= 0 || dto.wordsMax() <= dto.wordsMin()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "字数区间非法（wordsMin < wordsMax 且 >0）");
        }
        if (dto.tags() == null || dto.tags().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "tags 至少一个（首位=主标签）");
        }
        if (dto.pace() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "pace 必填（五锚点）");
        }
        try {
            BigDecimal p = new BigDecimal(dto.pace());
            boolean anchor = B0_TABLE.keySet().stream().anyMatch(a -> a.compareTo(p) == 0);
            if (!anchor) {
                throw new BizException(ErrorCode.PARAM_ERROR, "pace 必须是五锚点 0.00/0.25/0.50/0.75/1.00");
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "pace 非法数字");
        }
    }

    static String tagsJson(List<String> tags) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(tags.get(i)).append("\":1");
        }
        return sb.append('}').toString();
    }

    String cellSnapshot(DomainCellDO c) {
        ObjectNode n = STATIC_MAPPER.createObjectNode();
        n.put("name", c.getName());
        n.put("grp", c.getGrp());
        try {
            n.set("cats", STATIC_MAPPER.readTree(c.getCats() == null ? "{}" : c.getCats()));
        } catch (Exception e) {
            n.putObject("cats");
        }
        n.put("beatTypes", c.getBeatTypes());
        n.put("relationNote", c.getRelationNote());
        n.put("counterWords", c.getCounterWords());
        n.put("refIds", c.getRefIds());
        return n.toString();
    }

    String idArrayJson(List<Row> rows) {
        ArrayNode arr = om.createArrayNode();
        rows.forEach(r -> arr.add(r.id()));
        return arr.toString();
    }

    static JsonNode parseWelded(JsonNode root) {
        JsonNode w = root.path("welded");
        if (!w.path("code").asText("").startsWith("O-F")) {
            throw new LlmJson.Bad("welded.code 必须以 O-F 开头");
        }
        if (w.path("text").asText("").isEmpty()) {
            throw new LlmJson.Bad("welded.text 不能为空");
        }
        return w;
    }
}
