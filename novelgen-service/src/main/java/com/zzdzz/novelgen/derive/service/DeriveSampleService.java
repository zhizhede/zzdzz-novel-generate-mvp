package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.genre.GenreProfileRegistry;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.derive.genre.RomanceProfile;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired(required = false)
    private DeriveContractService contractService;
    private DeriveCharacterBusinessModelService businessModelService;
    private final WorkFingerprintMapper fingerprintMapper;
    private final com.zzdzz.novelgen.derive.mapper.CalibrationMapper calibrationMapper;
    private final LlmJson llmJson;
    private final ObjectMapper om;
    @Autowired(required = false)
    private GenreProfileRegistry genreProfileRegistry;

    public static final String NODE_TAG_NORM = "derive_tag_norm";

    /** 输入思路 AI 优化（intakeOptimize 开关，V114；默认关）。 */
    public static final String NODE_INTAKE = "derive_intake_review";

    /** AI 估档节点（2026-10-17 用户定调：字数带留空=AI 判篇幅与带宽，不是按形态套默认档）。 */
    public static final String NODE_WORD_BAND = "derive_word_band";

    static final String WORD_BAND_PROMPT = """
            你在做「篇幅估档」：根据题材标签与创作思路，判定成品小说的产品形态与合理总字数带。
            可选形态（只能选一个）：
            LONG=长篇；SHORT_STANDALONE=独立短篇；LIGHT_NOVEL=轻小说；
            LONG_INNER_SHORT=长中带短；LN_INNER_SHORT=轻中带短。
            输入：类别标签、节奏、创作思路、文风、（若给出）用户指定形态。
            规则：
            - 按题材自然体量与思路复杂度判断：长线阴谋/群像升级流/多卷本给大体量；
              单一事件闭环/单元短故事给小体量；轻小说语态或二次元设定倾向 LIGHT_NOVEL；
            - AI 天然贪大，宁小勿大：能用短篇讲清的不要给长篇；
            - wordsMin/wordsMax = 该形态下合理总字数（正整数，max>min），只估带宽不写章节结构；
            - 用户已指定形态时，form 必须原样返回该形态，只估字数。
            只输出 JSON 对象：
            {"form":"LONG","wordsMin":100000,"wordsMax":200000,"reason":"≤30字判断依据"}
            """;

    /** v1 思路预审提示词：审思路本身（非成稿），保留骨架只修硬伤，输出可直接使用的优化稿。 */
    static final String INTAKE_PROMPT = """
            你在做「创作思路预审」：开写之前鉴定用户的思路是否符合目标成品小说需求，
            指出硬伤并给出更优的完整思路。
            输入：目标参数（字数区间/类别/节奏/形态/文风）+ 用户创作思路全文。
            审「思路本身」（不是审成稿），维度：
            1. 逻辑可行性：设定/行为/环境的硬漏洞（例：伪装物在日常场景必然被识破、设定过度兜底）；
            2. 伏笔与回收：埋下的线是否都有回收方案（例：开篇命案悬置不收）；
            3. 人物动机：关键人物行为是否有可成立的动机；
            4. 目标匹配：思路体量是否匹配字数区间与节奏（慢热/快热）；红线合规（不写令人不适的描写）。
            规则：
            - 保留用户原意、骨架、体裁与结局形状，只修硬伤，禁止换题材重编；
            - optimized_intent = 优化后的完整创作思路，自包含、可直接作为后续生成输入；
              修改写成对思路本身的设定级表述（如「主角极少出门，遛狗改为在楼道窗台」），
              禁止「删掉第X章」这类针对未生成文本的指令；
            - 思路本身无硬伤时，optimized_intent 原样返回用户思路。
            只输出 JSON 对象：
            {"score":0.0,
             "holes":["按严重度排序的硬伤，每条≤60字"],
             "changes":["对应修改，每条≤60字"],
             "optimized_intent":"优化后的完整创作思路"}
            """;

    /** v3.5 类别归一化提示词（只映射池内键；%s=池键清单；全部本就是池键时恒等直通不调用）。 */
    static final String TAG_NORM_PROMPT = """
            你在做类别标签归一化：把用户的题材词映射到素材池既有类别键。
            池内可用键（只能用这些）：%s
            规则：
            - 每个输入词映射到池内语义最近的键，禁止发明池外键；
            - confidence 0~1：直接对应 ≥0.9，明显相关 0.6~0.85，勉强 <0.6；
            - mapping 条数=输入条数，input 与输入顺序一一对应；
            - 如实给 confidence，不要为过关虚报高值。
            只输出 JSON 对象：{"mapping":[{"input":"词","key":"池键","confidence":0.9,"reason":"≤15字"}]}
            """;

    // ================= 主流程 =================

    /** 建 run：5.1 → 5.2 → 插档（SAMPLING）→ 5.3 焊接 → 回填（PLANNING）。无事务（LLM 不进事务）。 */
    public RunVO create(RunCreateDTO dto) {
        validate(dto);
        String genre = normalizeGenre(dto.genre(), resolveGenreRegistry());
        // ---- 输入思路 AI 优化（v1，开关默认关）：预审通过则以优化稿顶替 intent 进后续链路 ----
        IntakeResult intake = Boolean.TRUE.equals(dto.intakeOptimize())
                ? optimizeIntake(dto)
                : new IntakeResult(dto.intent(), null);
        String effectiveIntent = intake.intent() == null || intake.intent().isBlank()
                ? null : intake.intent().strip();
        long seed = dto.seed() != null ? dto.seed() : ThreadLocalRandom.current().nextLong();
        Random rnd = new Random(seed);

        // ---- 读池（全池跨书；只取已打标且有标签层的条目）----
        List<Row> outlineRows = loadRows("OUTLINE", true);
        List<Row> beatRows = loadRows("BEAT", true);
        List<Row> worldRows = loadRows("WORLD", false);
        if (outlineRows.isEmpty() || beatRows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "素材池为空或未打标，先走完 ②③④");
        }

        // ---- form/outcome 缺省：字数带空=AI 估档（连形态一起判，2026-10-17 用户定调）；
        //      字数带给了才走 seed 抽 form。outcome 恒 seed 抽。 ----
        List<String> forms = List.of("LONG", "SHORT_STANDALONE", "LIGHT_NOVEL", "LONG_INNER_SHORT", "LN_INNER_SHORT");
        List<String> outcomes = List.of("循环", "开放", "闭合");
        boolean bandUnspecified = dto.wordsMin() == null && dto.wordsMax() == null;
        String userForm = dto.form() != null && !dto.form().isBlank() ? dto.form() : null;
        String form;
        int[] band;
        if (bandUnspecified) {
            BandEstimate est = aiEstimateBand(dto, effectiveIntent, userForm);
            if (est != null) {
                form = userForm != null ? userForm : est.form();
                band = clampBand(est.wordsMin(), est.wordsMax(), form);
                log.info("⑤AI估档：form={} band={}~{}（钳入档内） reason={}",
                        form, band[0], band[1], est.reason());
            } else {
                // fail-open：估档失败=用户形态或 seed 抽 + 形态默认带（v3.7 短期档口径退守）
                form = userForm != null ? userForm : forms.get(rnd.nextInt(forms.size()));
                band = resolveWordBand(dto, form);
                log.info("⑤AI估档失败回退：form={} band={}~{}", form, band[0], band[1]);
            }
        } else {
            form = userForm != null ? userForm : forms.get(rnd.nextInt(forms.size()));
            band = resolveWordBand(dto, form);
        }
        String outcome = dto.outcome() != null && !dto.outcome().isBlank() ? dto.outcome()
                : outcomes.get(rnd.nextInt(outcomes.size()));
        // P2 裁决：推理/悬疑题材禁开放结局（真相卡闭合义务优先；33/50 号合同打架教训）
        outcome = coerceMysteryOutcome(outcome, dto.tags());
        int wordsMin = band[0];
        int wordsMax = band[1];
        BigDecimal pace = new BigDecimal(dto.pace());
        int b0 = B0_TABLE.entrySet().stream()
                .filter(e -> e.getKey().compareTo(pace) == 0)
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, "pace 必须是五锚点"));
        double pMaxD = (double) wordsMax / b0;
        int pMin = (int) Math.ceil((double) wordsMin / b0);
        int pMax = (int) Math.ceil(pMaxD);
        int kTarget = Math.max(1, Math.min(4, (int) Math.ceil(pMaxD / 7)));
        int mTarget = Math.max(0, pMax - kTarget * 6);
        int wordsPred = Math.max(wordsMin, Math.min(wordsMax, b0 * (int) Math.ceil(pMaxD)));

        ObjectNode trace = om.createObjectNode();

        // ---- 5.2.0 类别归一化（v3.5：原始 tags → 池内键；全命中=恒等免 LLM，否则一次 LLM 映射、≥0.6 才过）----
        List<String> rawTags = dto.tags();
        Set<String> poolKeys = new LinkedHashSet<>();
        for (Row r : outlineRows) {
            poolKeys.addAll(r.cat().keySet());
        }
        for (Row r : beatRows) {
            poolKeys.addAll(r.cat().keySet());
        }
        List<String> tags;
        List<NormStep> normSteps = new ArrayList<>();
        if (rawTags.stream().allMatch(poolKeys::contains)) {
            tags = new ArrayList<>(rawTags);
            for (String t : rawTags) {
                normSteps.add(new NormStep(t, t, 1.0, "池键直用"));
            }
        } else {
            String keys = String.join("、", poolKeys);
            List<NormStep> steps = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_TAG_NORM, null, null,
                            List.of(LlmPort.Message.system(TAG_NORM_PROMPT.formatted(keys)),
                                    LlmPort.Message.user("输入题材词（按序）：" + rawTags)),
                            0.1),
                    root -> parseNorm(root, rawTags, poolKeys), 3);
            normSteps = steps;
            LinkedHashSet<String> mapped = new LinkedHashSet<>();
            for (NormStep s : steps) {
                mapped.add(s.key());
            }
            tags = new ArrayList<>(mapped);
            log.info("⑤类别归一化：{} → {}（LLM 映射，steps={}）", rawTags, tags, steps.size());
        }
        if (tags.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "归一化后 tags 为空");
        }
        ObjectNode tagsNorm = om.createObjectNode();
        tagsNorm.put("v", 1);
        ArrayNode rawArr = tagsNorm.putArray("raw");
        rawTags.forEach(rawArr::add);
        ArrayNode mappedArr = tagsNorm.putArray("mapped");
        tags.forEach(mappedArr::add);
        ArrayNode stepArr = tagsNorm.putArray("steps");
        for (NormStep s : normSteps) {
            ObjectNode n = stepArr.addObject();
            n.put("input", s.input());
            n.put("key", s.key());
            n.put("confidence", s.confidence());
            n.put("reason", s.reason() == null ? "" : s.reason());
        }

        // ---- 5.2.1 类别过滤（丢最弱次标签→θ降档，全档位取并集，每档命中进 trace）----
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

        // ---- 5.2.3b 创作框架红线过滤：用户显式禁止的素材不得进入焊接池 ----
        List<String> intentRedLines = intentRedLines(effectiveIntent);
        if (!intentRedLines.isEmpty()) {
            List<Row> allowedOutlines = filterIntentRows(finalOutlines, intentRedLines);
            List<Row> allowedBeats = filterIntentRows(finalBeats, intentRedLines);
            trace.putArray("intent_redlines").addAll(asTextNode(intentRedLines));
            trace.putObject("intent_filter").put("outlineBefore", finalOutlines.size())
                    .put("outlineAfter", allowedOutlines.size())
                    .put("beatBefore", finalBeats.size())
                    .put("beatAfter", allowedBeats.size());
            if (allowedOutlines.isEmpty() && !finalOutlines.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "创作框架红线过滤后没有可用故事线素材；红线=" + intentRedLines);
            }
            if (allowedBeats.isEmpty() && !finalBeats.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "创作框架红线过滤后没有可用拍素材；红线=" + intentRedLines);
            }
            finalOutlines = allowedOutlines;
            finalBeats = allowedBeats;
        }

        // ---- 5.2.4+5 弧桶分配（同构弧登记跳过、run 内唯一）+ 补差拍 ----
        // 防复刻开关：默认关=不按历史指纹阻断（一致性优先），开=九轮原口径
        // 2026-10-17 用户拍板：衍生新书 anti_repro 默认开（显式 false 才关）——配合指纹前移断跨稿复读
        boolean antiRepro = antiReproDefaulted(dto.antiRepro());
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
        List<DomainCellDO> allCells = domainCellMapper.selectList(new LambdaQueryWrapper<DomainCellDO>()
                .eq(DomainCellDO::getEnabled, true)
                .orderByAsc(DomainCellDO::getId));
        List<DomainCellDO> cellEligible = allCells.stream()
                .filter(c -> parseCatJson(c.getCats()).containsKey(main))
                .toList();
        DomainCellDO cell = weightedPickCell(cellEligible, main, rnd);
        if (cell == null) {
            cell = genericFallbackCell(main, genre, effectiveIntent);
            log.warn("域格缺失，使用确定性通用域格：tag={} genre={} name={}",
                    main, genre, cell.getName());
        }

        // ---- 插档（SAMPLING；jsonb 七列走 insertFull）----
        RunDO run = new RunDO();
        applyRunMetadata(run, genre);
        run.setSeed(seed);
        run.setParamWordsMin(wordsMin);
        run.setParamWordsMax(wordsMax);
        run.setParamTags(tagsJson(tags));
        run.setParamPace(pace);
        run.setParamForm(form);
        run.setParamOutcome(outcome);
        run.setParamIntent(effectiveIntent);
        run.setParamStyle(dto.style() == null || dto.style().isBlank() ? null : dto.style().strip());
        run.setIntakeReview(intake.review() == null ? null : intake.review().toString());
        run.setLenCheck(dto.lenCheck());
        run.setTagsNorm(tagsNorm.toString());
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
        run.setId(runId);
        // 合同不再在建 run 时写占位行：唯一故事合同在 ⑥a 的 CONTRACT 单元生成并冻结
        //（占位行会让“合同已存在”的判定失真，见 derive-contract-first 计划 §2）。
        if (businessModelService != null) {
            businessModelService.initializeCapabilities(runId, tags, effectiveIntent, outcome);
        }

        // ---- 5.3 焊接（K≥2 时 LLM；K<2 直接进 PLANNING）----
        String weldedCode = null;
        if (pickedOutlines.size() >= 2) {
            String segments = pickedOutlines.stream()
                    .map(r -> r.code() + " " + r.labelText())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            List<String> redLines = intentRedLines(effectiveIntent);
            String weldUser = "收束方式：" + outcome
                    + (redLines.isEmpty() ? "" : "\n创作框架红线（输出不得出现违禁素材）：" + String.join("、", redLines))
                    + "\n段列表：\n" + segments;
            JsonNode welded = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_WELD, null, null,
                            List.of(LlmPort.Message.system(WELD_PROMPT),
                                    LlmPort.Message.user(weldUser)),
                            0.3),
                    root -> parseWelded(root, redLines), 3);
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
                cell.getName(), weldedCode, "PLANNING", tags);
    }

    static String normalizeGenre(String genre, GenreProfileRegistry registry) {
        String normalized = genre == null || genre.isBlank() ? "MYSTERY"
                : genre.strip().toUpperCase(java.util.Locale.ROOT);
        registry.require(normalized);
        return normalized;
    }

    static void applyRunMetadata(RunDO run, String genre) {
        run.setGenre(genre);
        run.setStoryKernelVersion(1);
        run.setGenreContractVersion(1);
    }

    private GenreProfileRegistry resolveGenreRegistry() {
        if (genreProfileRegistry != null) {
            return genreProfileRegistry;
        }
        return new GenreProfileRegistry(List.of(new MysteryProfile(), new RomanceProfile()));
    }

    /**
     * 任意类型共用的确定性域格回退。
     *
     * <p>只作为素材池缺格时的最后兜底；新类型不应在 Java 里新增专属分支。
     * 后续可先替换为 AI_GENERATED_DOMAIN，再保留此确定性默认。
     */
    static DomainCellDO genericFallbackCell(String mainTag, String genre, String intent) {
        DomainCellDO cell = new DomainCellDO();
        String label = genre == null || genre.isBlank() ? mainTag : genre;
        cell.setName(label + "·通用域");
        cell.setGrp("DEFAULT");
        cell.setCats("{\"" + mainTag + "\":1.0}");
        cell.setBeatTypes("{事件,情绪,抉择,收束}");
        String note = intent == null || intent.isBlank()
                ? "围绕冲突、选择、代价、关系变化与结局收束组织事件"
                : "围绕创作意图组织冲突、选择、代价、关系变化与结局收束";
        cell.setRelationNote(note);
        cell.setCounterWords("{}");
        cell.setRefIds("[]");
        cell.setEnabled(true);
        cell.setSort(0);
        return cell;
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
        if (dto.wordsMin() == null && dto.wordsMax() == null) {
            // 留空=AI 估档（判形态与带宽；2026-10-17 用户定调），失败 fail-open 回退形态默认带
        } else if (dto.wordsMin() == null || dto.wordsMax() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "wordsMin/wordsMax 要么都传、要么都不传（不传=AI 估档定篇幅与带宽）");
        } else if (dto.wordsMin() <= 0 || dto.wordsMax() <= dto.wordsMin()) {
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
        if (Boolean.TRUE.equals(dto.intakeOptimize()) && (dto.intent() == null || dto.intent().isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "intakeOptimize 开启时 intent 必填（预审对象就是创作思路本身）");
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

    /**
     * P2 裁决：推理/悬疑题材禁开放与循环结局——真相卡（⑥b 对该两标签必出）承诺结局章
     * 还原三要素，与「开放/循环=末章可留悬念」的规划规则互斥（33/50/51 号合同教训，
     * run18 实弹：循环抽中即漏网）；命中即改判闭合。
     */
    /** anti_repro 缺省口径（2026-10-17 拍板）：不传=开，显式 false 才关。 */
    static boolean antiReproDefaulted(Boolean v) {
        return !Boolean.FALSE.equals(v);
    }

    static String coerceMysteryOutcome(String outcome, List<String> tags) {
        if (tags == null || (!"开放".equals(outcome) && !"循环".equals(outcome))) {
            return outcome;
        }
        return (tags.contains("推理") || tags.contains("悬疑")) ? "闭合" : outcome;
    }

    /** v3.7 字数短期档：各形态默认字数带 {min,max}（wordsMin/Max 不传时的确定性回退；AI 估档见 P6 方案）。 */
    static final Map<String, int[]> FORM_WORDS = Map.of(
            "LONG", new int[]{100000, 200000},
            "SHORT_STANDALONE", new int[]{10000, 30000},
            "LIGHT_NOVEL", new int[]{80000, 160000},
            "LONG_INNER_SHORT", new int[]{100000, 200000},
            "LN_INNER_SHORT", new int[]{60000, 120000});

    /**
     * 字数带解析：显式入参原样（validate 已验区间）；全缺省按 form 默认带宽；
     * 只传一半报错；未知形态报错（不猜——DB CHECK 也会拒）。
     */
    static int[] resolveWordBand(RunCreateDTO dto, String form) {
        Integer min = dto.wordsMin();
        Integer max = dto.wordsMax();
        if (min != null && max != null) {
            return new int[]{min, max};
        }
        if (min != null || max != null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "wordsMin/wordsMax 要么都传、要么都不传（不传=按形态默认带宽）");
        }
        int[] d = FORM_WORDS.get(form);
        if (d == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "未知形态 " + form + "，不传字数时无法定带宽——请显式传 wordsMin/wordsMax，或用形态 "
                            + String.join("/", FORM_WORDS.keySet()));
        }
        return new int[]{d[0], d[1]};
    }

    /** 归一化映射一步（落 run.tags_norm.steps；v3.5）。 */
    record NormStep(String input, String key, double confidence, String reason) {
    }

    /** 思路预审结果：优化后 intent + 评审明细（失败时原思路 + error 记录，fail-open）。 */
    record IntakeResult(String intent, ObjectNode review) {
    }

    /**
     * 输入思路 AI 优化（v1，intakeOptimize 开启时）：LLM 预审并产出优化稿。
     * LlmJson 重试耗尽后 fail-open——用原思路建 run，error 落 intake_review，不阻断。
     */
    IntakeResult optimizeIntake(RunCreateDTO dto) {
        String original = dto.intent().strip();
        ObjectNode review = om.createObjectNode();
        review.put("v", 1);
        try {
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_INTAKE, null, null,
                            List.of(LlmPort.Message.system(INTAKE_PROMPT),
                                    LlmPort.Message.user(intakeUser(dto))),
                            0.3),
                    DeriveSampleService::parseIntake, 3);
            String optimized = out.path("optimized_intent").asText("").strip();
            review.put("score", out.path("score").asDouble(-1));
            review.set("holes", out.path("holes"));
            review.set("changes", out.path("changes"));
            boolean changed = !optimized.equals(original);
            review.put("source", changed ? "OPTIMIZED" : "USER");
            log.info("⑤思路预审：score={} holes={} source={}",
                    review.path("score").asDouble(), review.path("holes").size(),
                    review.path("source").asText());
            return new IntakeResult(optimized, review);
        } catch (Exception e) {
            log.warn("⑤思路预审失败（fail-open，用原思路）：{}", e.getMessage());
            review.put("source", "USER");
            review.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return new IntakeResult(original, review);
        }
    }

    /**
     * AI 估档（字数带两头都空时）：LLM 按 tags/intent/style 定 form+带宽；用户已指定 form 则只估带宽。
     * 估出的带宽一律钳入该形态 FORM_WORDS 档内（AI 贪大护栏）。重试耗尽 fail-open 返回 null。
     */
    BandEstimate aiEstimateBand(RunCreateDTO dto, String effectiveIntent, String fixedForm) {
        try {
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_WORD_BAND, null, null,
                            List.of(LlmPort.Message.system(WORD_BAND_PROMPT),
                                    LlmPort.Message.user(wordBandUser(dto, effectiveIntent, fixedForm))),
                            0.3),
                    root -> parseWordBand(root, fixedForm), 3);
            return new BandEstimate(out.path("form").asText("").trim(),
                    out.path("wordsMin").asInt(0), out.path("wordsMax").asInt(0),
                    out.path("reason").asText(""));
        } catch (Exception e) {
            log.warn("⑤AI估档失败（fail-open，回退形态默认带）：{}", e.getMessage());
            return null;
        }
    }

    /** 估档校验（不过喂回重试）：form 合法（或原样返回用户指定形态）、带宽为正整数且 max>min、有 reason。 */
    static JsonNode parseWordBand(JsonNode root, String fixedForm) {
        String form = root.path("form").asText("").trim();
        if (fixedForm != null) {
            if (!fixedForm.equals(form)) {
                throw new LlmJson.Bad("form 必须原样返回用户指定的「" + fixedForm + "」，当前「" + form + "」");
            }
        } else if (!FORM_WORDS.containsKey(form)) {
            throw new LlmJson.Bad("form 必须是 " + String.join("/", FORM_WORDS.keySet()) + "，当前「" + form + "」");
        }
        int min = root.path("wordsMin").asInt(0);
        int max = root.path("wordsMax").asInt(0);
        if (min <= 0 || max <= min) {
            throw new LlmJson.Bad("wordsMin/wordsMax 须为正整数且 max>min，当前 " + min + "~" + max);
        }
        if (!root.has("reason")) {
            throw new LlmJson.Bad("缺 reason（≤30字判断依据）");
        }
        return root;
    }

    /** 估档钳位：把 AI 估计夹进该形态默认档 [lo,hi]（AI 天然贪大，档位护栏必须保留）。 */
    static int[] clampBand(int estMin, int estMax, String form) {
        int[] d = FORM_WORDS.get(form);
        if (d == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "未知形态 " + form + "，合法形态：" + String.join("/", FORM_WORDS.keySet()));
        }
        int min = Math.max(d[0], Math.min(estMin, d[1]));
        int max = Math.max(min, Math.min(estMax, d[1]));
        return new int[]{min, max};
    }

    String wordBandUser(RunCreateDTO dto, String effectiveIntent, String fixedForm) {
        ObjectNode root = om.createObjectNode();
        root.put("类别", String.join("、", dto.tags()));
        root.put("节奏", dto.pace());
        root.put("形态", fixedForm == null ? "未指定（由你判定）" : fixedForm + "（用户已指定，只估字数）");
        root.put("文风", dto.style() == null || dto.style().isBlank() ? "未指定" : dto.style());
        String intent = effectiveIntent == null || effectiveIntent.isBlank() ? "（未提供）" : effectiveIntent;
        root.put("创作思路", intent);
        return root.toString();
    }

    /** AI 估档结果（form 可能被用户指定形态覆盖；带宽经 clampBand 钳定后才落 run）。 */
    record BandEstimate(String form, int wordsMin, int wordsMax, String reason) {
    }

    /** 思路预审校验（不过喂回重试）：optimized_intent 非空、score∈[0,10]、holes/changes 为数组。 */
    static JsonNode parseIntake(JsonNode root) {
        if (root.path("optimized_intent").asText("").trim().isEmpty()) {
            throw new LlmJson.Bad("缺 optimized_intent（优化后的完整创作思路；无修改时原样返回用户思路）");
        }
        double score = root.path("score").asDouble(-1);
        if (score < 0 || score > 10) {
            throw new LlmJson.Bad("score 须为 0~10，当前 " + score);
        }
        if (!root.path("holes").isArray() || !root.path("changes").isArray()) {
            throw new LlmJson.Bad("holes/changes 须为数组（可为空数组）");
        }
        return root;
    }

    String intakeUser(RunCreateDTO dto) {
        ObjectNode root = om.createObjectNode();
        String band = dto.wordsMin() != null && dto.wordsMax() != null
                ? dto.wordsMin() + "~" + dto.wordsMax()
                : "未指定（稍后 AI 估档定篇幅与带宽）";
        root.put("字数区间", band);
        root.put("类别", String.join("、", dto.tags()));
        root.put("节奏", dto.pace());
        root.put("形态", dto.form() == null || dto.form().isBlank() ? "未指定（系统抽）" : dto.form());
        root.put("文风", dto.style() == null || dto.style().isBlank() ? "未指定" : dto.style());
        root.put("创作思路", dto.intent());
        return root.toString();
    }

    /** 归一化解析：条数/input 顺序/池内键/置信 ≥0.6 逐项校验，不过喂回（末次原因带池键清单）。 */
    static List<NormStep> parseNorm(JsonNode root, List<String> rawTags, Set<String> poolKeys) {
        String keys = String.join("、", poolKeys);
        JsonNode arr = root.path("mapping");
        if (!arr.isArray() || arr.size() != rawTags.size()) {
            throw new LlmJson.Bad("mapping 条数必须 = 输入 " + rawTags.size() + "；池键=" + keys);
        }
        List<NormStep> out = new ArrayList<>();
        for (int i = 0; i < rawTags.size(); i++) {
            JsonNode m = arr.get(i);
            String input = m.path("input").asText("").trim();
            String key = m.path("key").asText("").trim();
            double conf = m.path("confidence").asDouble(-1);
            if (!rawTags.get(i).equals(input)) {
                throw new LlmJson.Bad("mapping[" + i + "].input 应为「" + rawTags.get(i)
                        + "」，当前「" + input + "」；池键=" + keys);
            }
            if (!poolKeys.contains(key)) {
                throw new LlmJson.Bad("key「" + key + "」不在池键，禁止发明；池键=" + keys);
            }
            if (conf < 0.6) {
                throw new LlmJson.Bad("「" + input + "」映射 confidence=" + conf
                        + " <0.6，无高置信对应——请在池键内重选最接近者，或如实说明无法映射；池键=" + keys);
            }
            out.add(new NormStep(input, key, conf, m.path("reason").asText("")));
        }
        return out;
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

    /** 从用户创作思路抽取可机械识别的红线词组。 */
    static List<String> intentRedLines(String intent) {
        if (intent == null || intent.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (containsAny(intent, List.of("无死亡", "不写死亡", "没有死亡", "禁止死亡"))
                || (intent.contains("死亡") && !intent.contains("允许死亡"))) {
            out.addAll(List.of("死亡", "命案", "凶杀", "尸体", "遗骸", "死者", "殡葬"));
        }
        if (containsAny(intent, List.of("无超自然", "不写超自然", "没有超自然", "禁止超自然"))
                || (intent.contains("超自然") && !intent.contains("允许超自然"))) {
            out.addAll(List.of("超自然", "鬼", "灵异", "怪谈", "异象", "化形"));
        }
        if (containsAny(intent, List.of("无第二案件", "不写第二案件", "没有第二案件", "禁止第二案件"))
                || (intent.contains("第二案件") && !intent.contains("允许第二案件"))) {
            out.addAll(List.of("第二案件", "第二起案件", "另案", "并案"));
        }
        if (containsAny(intent, List.of("单案短篇", "只有三名角色"))
                || containsAny(intent, List.of("不写珍品", "无珍品", "没有珍品", "禁止珍品",
                "不写密室", "无密室", "没有密室", "禁止密室",
                "不写武装", "无武装", "没有武装", "禁止武装",
                "不写勒索", "无勒索", "没有勒索", "禁止勒索",
                "不写替身术", "无替身术", "没有替身术", "禁止替身术"))) {
            out.addAll(List.of("珍品", "密室", "武装", "勒索", "替身术"));
        }
        if (containsAny(intent, List.of("地点限于旧楼和走廊", "只在旧楼", "限于旧楼", "旧楼和走廊"))) {
            out.addAll(List.of("街市", "林中", "乡野", "花园", "书房", "内室", "市场", "郊外", "城郊"));
        }
        return out.stream().distinct().toList();
    }

    static List<Row> filterIntentRows(List<Row> rows, List<String> redLines) {
        if (redLines == null || redLines.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> firstForbiddenTerm(row.labelText(), redLines) == null)
                .toList();
    }

    static String firstForbiddenTerm(String text, List<String> redLines) {
        if (text == null || redLines == null || redLines.isEmpty()) {
            return null;
        }
        String clean = stripNegations(text);
        for (String term : redLines) {
            if (clean.contains(term)) {
                return term;
            }
        }
        return null;
    }

    private static String stripNegations(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("无死亡", "").replace("不写死亡", "")
                .replace("没有死亡", "").replace("禁止死亡", "")
                .replace("无超自然", "").replace("不写超自然", "")
                .replace("没有超自然", "").replace("禁止超自然", "")
                .replace("无第二案件", "").replace("不写第二案件", "")
                .replace("没有第二案件", "").replace("禁止第二案件", "")
                .replace("无珍品", "").replace("不写珍品", "")
                .replace("没有珍品", "").replace("禁止珍品", "")
                .replace("无密室", "").replace("不写密室", "")
                .replace("没有密室", "").replace("禁止密室", "")
                .replace("无武装", "").replace("不写武装", "")
                .replace("没有武装", "").replace("禁止武装", "")
                .replace("无勒索", "").replace("不写勒索", "")
                .replace("没有勒索", "").replace("禁止勒索", "")
                .replace("无替身术", "").replace("不写替身术", "")
                .replace("没有替身术", "").replace("禁止替身术", "");
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    private static ArrayNode asTextNode(List<String> values) {
        ArrayNode arr = new ArrayNode(STATIC_MAPPER.getNodeFactory());
        values.forEach(arr::add);
        return arr;
    }

    String idArrayJson(List<Row> rows) {
        ArrayNode arr = om.createArrayNode();
        rows.forEach(r -> arr.add(r.id()));
        return arr.toString();
    }

    static JsonNode parseWelded(JsonNode root) {
        return parseWelded(root, List.of());
    }

    static JsonNode parseWelded(JsonNode root, List<String> redLines) {
        JsonNode w = root.path("welded");
        if (!w.path("code").asText("").startsWith("O-F")) {
            throw new LlmJson.Bad("welded.code 必须以 O-F 开头");
        }
        String text = w.path("text").asText("");
        if (text.isEmpty()) {
            throw new LlmJson.Bad("welded.text 不能为空");
        }
        String forbidden = firstForbiddenTerm(stripNegations(text), redLines);
        if (forbidden != null) {
            throw new LlmJson.Bad("welded.text 命中创作框架红线：" + forbidden);
        }
        return w;
    }
}
