package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.model.enums.GateType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 机械门禁（确定性代码，零 LLM）：风格指纹指标对照基线 + 硬规则（直引号/黑名单/章长预算）。
 * 指标计算为静态纯函数（可单测）；结果落 gate_reports 供断点重放与 M2 AI 评审对照。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GateService {

    /**
     * 单条门禁检查。序列化形状与历史 Map 落库逐键一致：check/value/baseline/abs_max/ok，
     * abs_max 可空且为 null 时不出现（_gate_reports 存量数据与档案页兼容线）。
     */
    public record GateCheck(String check, Object value, Object baseline,
                            @JsonProperty("abs_max") @JsonInclude(JsonInclude.Include.NON_NULL) Object absMax,
                            boolean ok) {
    }

    /** 一次门禁判定：是否通过 + 全量检查条目。 */
    public record GateVerdict(boolean passed, List<GateCheck> checks) {

        /** 未过的条目（编辑反馈/重写喂回用）。 */
        public List<GateCheck> failedChecks() {
            return checks.stream().filter(c -> !c.ok()).toList();
        }
    }

    /** AI 腔黑名单兜底：风格包未配置 gate_config 时使用；正式值随包落库（V4 起）。 */
    private static final List<String> BANNED_FALLBACK = List.of(
            "心中暗想", "不由得", "仿佛在诉说", "在空气中弥漫", "空气仿佛凝固",
            "嘴角勾起一抹", "眼底闪过一丝", "一丝不易察觉");

    private static final Pattern CN = Pattern.compile("[\\u4e00-\\u9fff]");

    private final StylePackDataService stylePackData;
    private final GateReportDataService gateReportData;
    private final ChapterDataService chapterData;
    private final TuningService tuning;
    private final com.zzdzz.novelgen.service.data.NovelDataService novelData;


    @SuppressWarnings("unchecked")
    public GateVerdict checkChapter(long novelId, long chapterId, int chapterNo, String text,
                                int budgetMin, int budgetMax) {
        GateVerdict verdict = evaluateChapter(novelId, chapterNo, text, budgetMin, budgetMax);
        Map<String, Object> metrics = computeMetrics(text);
        int words = ((Number) metrics.get("cjk")).intValue();
        List<String> hits = bannedPhrases(gateConfig(novelId)).stream().filter(text::contains).toList();
        gateReportData.insert(chapterId, null, GateType.MECHANICAL.wire(), 0, verdict.passed(),
                Map.of("chapter_no", chapterNo, "words", words,
                        "banned_hits", hits, "checks", verdict.checks()));
        return verdict;
    }

    /**
     * 只判定、不落报告：成品终检关要在多个候选稿之间比较（回退稿/再修订稿），
     * 给没被采用的候选稿留一行门禁报告会污染「最新报告 = 成品实况」这条既有约定；
     * 比较完由调用方对**最终采用的正文**调一次 {@link #checkChapter} 落报告。
     */
    @SuppressWarnings("unchecked")
    public GateVerdict evaluateChapter(long novelId, int chapterNo, String text,
                                       int budgetMin, int budgetMax) {
        Map<String, Object> base = fingerprint(novelId);
        Map<String, Object> metrics = computeMetrics(text);
        List<GateCheck> checks = new ArrayList<>();

        int words = ((Number) metrics.get("cjk")).intValue();
        Map<String, Object> gateCfg = gateConfig(novelId);
        double lenTol = configDouble(gateCfg, "chapter_length_tolerance", 0.15);
        boolean lenOk = words >= budgetMin * (1 - lenTol) && words <= budgetMax * (1 + lenTol);
        checks.add(check("chapter_length", words, budgetMin * (1 - lenTol), budgetMax * (1 + lenTol), lenOk));

        // 开篇复写检查：本章前 3 行不得与上一章末 3 行重复（场景续写惯性把衔接写成复写的实锤 bug）
        String prevText = chapterNo > 1 ? chapterData.findFullText(novelId, chapterNo - 1) : null;
        int overlap = prevText == null ? 0 : openingOverlap(text, prevText);
        checks.add(check("opening_overlap", overlap, 0, 0, overlap == 0));

        // 人称一致性（读取链：chapters.pov → derive_config.pov → 无配置不查）。
        // 只抓整章级错配；默认 pov_check_block=0 只报不拦——passed 计算在下方对该项单独放行，
        // 报告里 ok 如实记录，档案页能看到漂移但不触发重写（判据主观，先观察误报再开拦截）。
        GateCheck pov = povCheck(effectivePov(novelId, chapterNo), text);
        if (pov != null) {
            checks.add(pov);
        }

        // 比喻密度：人类手稿 2.4-7/千字，AI 生成可冲到 15/千字（描写铺场的量化信号）
        double simileMax = tuning.d("simile_per1k_abs_max", 8.0);
        double simile = ((Number) metrics.get("simile_per1k")).doubleValue();
        checks.add(check("simile_per1k", simile, null, simileMax, simile <= simileMax));

        // 对白密度上界（**章级主判据**，2026-10-07 自场景级上移）：章级样本 ~3000 字指标稳定
        //（实测 12 个已成稿章 20.7–33.8），30.2 上限偶发越界且章级修订易收敛；场景级 450 字
        // 单个「≈2.2/千字方差过大（实弹同轮 1 章 5 次卡边、30.44/30.49 边界反复磨），只留 1.5 倍极端护栏。
        double dlg = ((Number) metrics.get("dialogue_density_per1k")).doubleValue();
        double dlgMax = densityCap(gateCfg, base);
        checks.add(check("dialogue_density_per1k", dlg, null, dlgMax, dlg <= dlgMax));

        checks.addAll(fingerprintChecks(base, metrics));

        boolean noStraightQuote = !text.contains("\"");
        checks.add(check("no_straight_quote", text.contains("\"") ? 1 : 0, 0, 0, noStraightQuote));

        List<String> hits = new ArrayList<>();
        for (String phrase : bannedPhrases(gateCfg)) {
            if (text.contains(phrase)) hits.add(phrase);
        }
        checks.add(check("banned_phrases", hits.size(), 0, 0, hits.isEmpty()));

        // pov_check_block（tuning，默认 0=只报不拦）：人称错配在报告里 ok 如实为 false，但不参与
        // passed 判定——判据主观（自由间接引语/对白里都有「我」），先观察误报率再开拦截。
        // 开启（>=1）后该项与其他硬闸同权，错配即打回重写。
        boolean povBlock = tuning.d("pov_check_block", 0.0) >= 1.0;
        boolean passed = checks.stream().allMatch(c -> c.ok() || (!povBlock && "pov_consistent".equals(c.check())));
        return new GateVerdict(passed, checks);
    }

    /** 人称读取链（章级→书级→null），供门禁与提示词共用口径——判据只认 DeriveSupport.effectivePov 的结果。 */
    private String effectivePov(long novelId, int chapterNo) {
        String chapterPov = chapterData.find(novelId, chapterNo).map(ChapterDO::getPov).orElse(null);
        String bookPov = DeriveSupport.parse(novelData.findDeriveConfig(novelId)).pov();
        return DeriveSupport.effectivePov(chapterPov, bookPov);
    }

    /**
     * 人称一致性（**整章级、保守判据**）：只抓「配置第一人称却整章第三人称叙述」与反向的明显错配，
     * 不判章内 head-hopping、不判自由间接引语——那类交给 AI 审校（主观判断）。
     * 判据先剥掉对白（「」/“”/"" 内文），只看叙述层的「我」：
     * <ul>
     *   <li>期望第一人称：叙述层「我」== 0 → 错配（整章漂移的实锤；ch1 那种主体第一人称+局部插叙不会命中）</li>
     *   <li>期望第三人称：叙述层「我」≥ 30/千字 → 错配（每 33 字一个「我」只能是第一人称叙述）</li>
     *   <li>多视角轮换（章级为空时的书级值）/未知人称/叙述样本不足（剥对白后 &lt;100 字）→ null 不出检查</li>
     * </ul>
     * 返回 null=本章不适用；否则给出 check 项（ok 如实反映判定，拦不拦由调用方按 pov_check_block 决定）。
     */
    public static GateCheck povCheck(String expectedPov, String text) {
        if (expectedPov == null || expectedPov.isBlank() || text == null || text.isBlank()) {
            return null;
        }
        String exp = expectedPov.strip();
        if (exp.contains("多视角") || exp.contains("全知")) {
            return null; // 多视角逐章由章纲定；全知视角允许自由进出人物内心，判人称只会误报
        }
        String narration = stripDialogue(text);
        int narrCjk = (int) CN.matcher(narration).results().count();
        if (narrCjk < 100) {
            return null; // 对白占比过高，叙述层样本不足——不判（fail-open）
        }
        int wo = (int) count(narration, "我");
        if (exp.contains("第一人称")) {
            // 期望第一人称：叙述层必须有「我」；0 次=整章漂成第三人称
            return new GateCheck("pov_consistent", wo, 1, null, wo >= 1);
        }
        if (exp.contains("第三人称")) {
            // 期望第三人称：「我」密度≥30/千字=叙述层实为第一人称
            double per1k = wo * 1000.0 / narrCjk;
            return new GateCheck("pov_consistent", round(per1k), null, 30.0, per1k < 30.0);
        }
        return null; // 全知/未知口径不判
    }

    /** 剥对白：三类引号成对移除（直引号虽被门禁禁用，但审校前的稿子可能有）。残缺引号不剥（保守，宁漏不误）。 */
    static String stripDialogue(String text) {
        String s = text.replaceAll("「[^」]*」", "");
        s = s.replaceAll("“[^”]*”", "");
        return s.replaceAll("\"[^\"]*\"", "");
    }

    /**
     * 场景级门禁：只查确定性硬规则与方差可控的指标（对白密度极端护栏/句末标点/顿号/感叹号/数字）。
     * 破折号等稀疏统计与对白密度主判据留到章级——几百字样本上方差过大（2026-10-07 密度实弹上移）。
     */
    @SuppressWarnings("unchecked")
    public GateVerdict checkScene(long novelId, long chapterId, long sceneId, int sceneNo, String text, int wordsBudget) {
        Map<String, Object> base = fingerprint(novelId);
        Map<String, Object> metrics = computeMetrics(text);
        List<GateCheck> checks = new ArrayList<>();

        // 场景长度：预算比例带（43 章超长实锤后新增）。场景超长若放行，章级修订受 ±10% 约束救不回来
        double sceneLenMin = tuning.d("scene_len_min_ratio", TuningDefaults.SCENE_LEN_MIN_RATIO);
        double sceneLenMax = tuning.d("scene_len_max_ratio", TuningDefaults.SCENE_LEN_MAX_RATIO);
        int cjk = ((Number) metrics.get("cjk")).intValue();
        boolean lenOk = cjk >= wordsBudget * sceneLenMin && cjk <= wordsBudget * sceneLenMax;
        checks.add(check("scene_length", cjk, (int) (wordsBudget * sceneLenMin),
                (int) (wordsBudget * sceneLenMax), lenOk));

        checks.add(check("no_straight_quote", text.contains("\"") ? 1 : 0, 0, 0, !text.contains("\"")));
        Map<String, Object> gateCfg = gateConfig(novelId);
        List<String> hits = new ArrayList<>();
        for (String phrase : bannedPhrases(gateCfg)) {
            if (text.contains(phrase)) hits.add(phrase);
        }
        checks.add(check("banned_phrases", hits.size(), 0, 0, hits.isEmpty()));

        double dunhao = ((Number) metrics.get("dunhao_per1k")).doubleValue();
        double exclam = ((Number) metrics.get("exclam_per1k")).doubleValue();
        double digit = ((Number) metrics.get("digit_per1k")).doubleValue();
        double endPunct = ((Number) metrics.get("dialogue_end_punct_ratio")).doubleValue();
        double dlg = ((Number) metrics.get("dialogue_density_per1k")).doubleValue();
        // 场景级阈值全部读指纹基线（abs_max 优先，否则 基线*(1+容差)），多风格包可移植
        double dunhaoMax = upperBound(base, "dunhao_per1k", 1.0);
        double exclamMax = upperBound(base, "exclam_per1k", 2.0);
        double digitMax = upperBound(base, "digit_per1k", 12.0);
        // 对白密度三层回退（gate_config dialogue_density_max → 指纹 → 30.2）。
        // 场景级只留 1.5× 极端护栏：450 字样本单个「≈2.2/千字（小样本方差大），主判据已上移
        // 章级——本检查只拦病理级灌水（实测自然输出 25-37，病理稿 55+），不再参与常规卡边。
        double dlgMax = densityCap(gateCfg, base) * 1.5;
        checks.add(check("dunhao_per1k", dunhao, null, dunhaoMax, dunhao <= dunhaoMax));
        checks.add(check("exclam_per1k", exclam, null, exclamMax, exclam <= exclamMax));
        checks.add(check("digit_per1k", digit, null, digitMax, digit <= digitMax));
        // 对白句末标点是下限指标（要高合规——曾按上限 0.35 执法，强制出「走吧」他说 式无标点对白，已翻转）
        double endPunctFloor = lowerBound(base, "dialogue_end_punct_ratio", 0.5);
        checks.add(check("dialogue_end_punct_ratio", endPunct, endPunctFloor, null, endPunct >= endPunctFloor));
        // 对白密度：场景级仅极端护栏（1.5× 章级上限），主判据在章级 evaluateChapter（2026-10-07 上移）
        checks.add(check("dialogue_density_per1k", dlg, null, dlgMax, dlg <= dlgMax));

        boolean passed = checks.stream().allMatch(GateCheck::ok);
        gateReportData.insert(chapterId, sceneId, GateType.MECHANICAL.wire(), 0, passed,
                Map.of("scene_no", sceneNo, "checks", checks));
        return new GateVerdict(passed, checks);
    }

    public String failureSummary(long chapterId) {
        return gateReportData.findLatestFailureJson(chapterId);
    }

    /** 场景级失败意见：只取该场景自己的最新失败报告。 */
    public String failureSummary(long chapterId, long sceneId) {
        return gateReportData.findLatestSceneFailureJson(chapterId, sceneId);
    }

    /** 失败指标人话摘要（事件流水用）：「dialogue_density_per1k=3.92（上限30.2）」。无失败返回空串。 */
    public String failedChecksText(long chapterId) {
        return failedChecksText(gateReportData.findLatestFailureJson(chapterId));
    }

    public String failedChecksText(long chapterId, long sceneId) {
        return failedChecksText(gateReportData.findLatestSceneFailureJson(chapterId, sceneId));
    }

    /**
     * 失败项格式：双界（基线~上限）/ 仅上限 / 仅基线。
     * 2026-10-07 卡边实弹修复：旧格式只打 baseline 不打 abs_max——对白密度显示「基线null」
     * （修订轮不知道 30.2 上限，两次收敛到 30.44 卡死）；行均长只显示中位数 23.68（修订轮朝
     * 中位数压，过冲到 15.65 跌破真实下限 20.13）。修订轮必须看到完整目标区间才不会修 A 坏 B。
     */
    static String failedChecksText(String failureJson) {
        if (failureJson == null) {
            return "";
        }
        try {
            Map<String, Object> result = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(failureJson, Map.class);
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) result.getOrDefault("checks", List.of())) {
                Map<String, Object> c = (Map<String, Object>) o;
                if (Boolean.TRUE.equals(c.get("ok"))) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("；");
                }
                Object baseline = c.get("baseline");
                Object absMax = c.get("abs_max");
                sb.append(c.get("check")).append('=').append(c.get("value"));
                if (baseline != null && absMax != null) {
                    sb.append("（").append(baseline).append('~').append(absMax).append('）');
                } else if (absMax != null) {
                    sb.append("（上限").append(absMax).append('）');
                } else if (baseline != null) {
                    sb.append("（基线").append(baseline).append('）');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // fail-open 之前是静默的：前端只显示空原因、排查无痕（本次统一补日志，返回值不变）
            log.warn("门禁失败摘要生成失败（前端将显示空原因）：{}；原始报告：{}", e.getMessage(),
                    failureJson.length() > 200 ? failureJson.substring(0, 200) + "..." : failureJson);
            return "";
        }
    }

    /** 指纹指标对照：稀疏特征（基线<3/千字）下界归零只防滥用，其余 ±tolerance；abs_min 显式下界（对话密度防叙述铺场）。
     * dialogue_end_punct_ratio 内置硬下限 0.5（基线缺失同样生效）——兜住「对话句末无标点」式文风污染。 */
    @SuppressWarnings("unchecked")
    private List<GateCheck> fingerprintChecks(Map<String, Object> base,
                                                        Map<String, Object> metrics) {
        List<GateCheck> checks = new ArrayList<>();
        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            String key = e.getKey();
            if (key.equals("cjk")) continue;
            // 对白密度跳过：统一由章级显式密度检查按三层回退处理（gate_config→指纹→30.2），
            // 避免有密度基线的指纹产生两条同名检查、两套带宽打架
            if (key.equals("dialogue_density_per1k")) continue;
            Map<String, Object> baselineMap = (Map<String, Object>) base.get("baseline");
            Map<String, Object> rule = (Map<String, Object>) baselineMap.get(key);
            double value = ((Number) e.getValue()).doubleValue();
            if (key.equals("dialogue_end_punct_ratio")) {
                double floor = 0.5;
                if (rule != null && rule.containsKey("value")) {
                    double v = ((Number) rule.get("value")).doubleValue();
                    double tol = ((Number) rule.get("tolerance")).doubleValue();
                    floor = rule.containsKey("abs_min")
                            ? ((Number) rule.get("abs_min")).doubleValue()
                            : Math.max(0.5, v * (1 - tol));
                }
                checks.add(check(key, value, floor, null, value >= floor));
                continue;
            }
            if (rule == null) continue;
            double v = ((Number) rule.get("value")).doubleValue();
            double tol = ((Number) rule.get("tolerance")).doubleValue();
            double upper = rule.containsKey("abs_max")
                    ? ((Number) rule.get("abs_max")).doubleValue() : v * (1 + tol);
            boolean ok;
            if (rule.containsKey("abs_min")) {
                ok = value >= ((Number) rule.get("abs_min")).doubleValue() && value <= upper;
            } else if (v < 3.0) {
                // 稀疏指纹（来着/顺便等）下界归零：几千字里出现 0 次属正常，只防滥用
                ok = value <= upper;
            } else {
                ok = value >= v * (1 - tol) && value <= upper;
            }
            checks.add(check(key, value, rule.get("value"), rule.get("abs_max"), ok));
        }
        return checks;
    }

    /** 门禁配置（黑名单等）：读风格包 gate_config；无则用代码兜底。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> gateConfig(long novelId) {
        String json = stylePackData.findGateConfigByNovel(novelId);
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            // 静默回默认会让黑名单/章长容差/评审阈值悄悄漂移——必须留痕
            log.warn("门禁配置解析失败（作品 {}），本轮回退代码兜底值，质量口径可能漂移：{}", novelId, e.getMessage());
            return Map.of();
        }
    }

    /** 书级门禁参数：gate_config 有值即用（Number 或数字字符串，人工编辑容错），否则返回 fallback（调用方传平台 tuning 值）。 */
    public double configValue(long novelId, String key, double fallback) {
        Object v = gateConfig(novelId).get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                log.warn("门禁参数不是数字（作品 {} 键 {}={}），回退默认值 {}", novelId, key, s, fallback);
            }
        }
        return fallback;
    }

    /** 书级字符串门禁参数（如 gate_recheck_action）：gate_config 有值即用，否则返回 fallback（调用方传平台 tuning 值）。 */
    public String configText(long novelId, String key, String fallback) {
        Object v = gateConfig(novelId).get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    /** 有效评审标准五项（gate_config > tuning > 代码默认），工作台/风格包调参面板回显用。 */
    public java.util.LinkedHashMap<String, Double> readerStandards(long novelId) {
        java.util.LinkedHashMap<String, Double> m = new java.util.LinkedHashMap<>();
        m.put("reader_fat_ratio_block", configValue(novelId, "reader_fat_ratio_block", tuning.d("reader_fat_ratio_block", TuningDefaults.READER_FAT_RATIO_BLOCK)));
        m.put("reader_fat_ratio_hard", configValue(novelId, "reader_fat_ratio_hard", tuning.d("reader_fat_ratio_hard", TuningDefaults.READER_FAT_RATIO_HARD)));
        m.put("reader_fix_len_min", configValue(novelId, "reader_fix_len_min", tuning.d("reader_fix_len_min", TuningDefaults.READER_FIX_LEN_MIN)));
        m.put("reader_fix_len_max", configValue(novelId, "reader_fix_len_max", tuning.d("reader_fix_len_max", TuningDefaults.READER_FIX_LEN_MAX)));
        m.put("ai_review_fix_floor", configValue(novelId, "ai_review_fix_floor", tuning.d("ai_review_fix_floor", TuningDefaults.AI_REVIEW_FIX_FLOOR)));
        return m;
    }

    /** 评审标准快照 JSON（章节生成开始时随章落库，回看当时口径）。 */
    public String readerStandardsJson(long novelId) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(readerStandards(novelId));
        } catch (Exception e) {
            log.warn("评审标准快照序列化失败（作品 {}），该章不回看口径：{}", novelId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> bannedPhrases(Map<String, Object> gateCfg) {
        Object v = gateCfg.get("banned_phrases");
        return v instanceof List ? (List<String>) v : BANNED_FALLBACK;
    }

    private static double configDouble(Map<String, Object> cfg, String key, double fallback) {
        return cfg.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }

    /** 对白密度上限三层回退：书级 gate_config 键 dialogue_density_max → 指纹 abs_max/基线 → 硬编码 30.2。
     * 2026-10-07 卡边实弹：旧指纹（外部工具导入的子集）普遍缺密度基线，恒落 30.2；键名由
     * scene_dialogue_density_max 统一改为 dialogue_density_max（检查已上移章级，场景级只是 1.5× 护栏共用本上限）。 */
    private static double densityCap(Map<String, Object> gateCfg, Map<String, Object> base) {
        return configDouble(gateCfg, "dialogue_density_max",
                upperBound(base, "dialogue_density_per1k", 30.2));
    }

    /** 指标的场景级上限：读指纹 baseline（abs_max 优先，否则 基线*(1+容差)）；未配置用兜底值。 */
    @SuppressWarnings("unchecked")
    private static double upperBound(Map<String, Object> fingerprint, String key, double fallback) {
        Map<String, Object> baselineMap = (Map<String, Object>) fingerprint.get("baseline");
        if (baselineMap == null) return fallback;
        Map<String, Object> rule = (Map<String, Object>) baselineMap.get(key);
        if (rule == null) return fallback;
        if (rule.containsKey("abs_max")) {
            return ((Number) rule.get("abs_max")).doubleValue();
        }
        if (rule.containsKey("value")) {
            return ((Number) rule.get("value")).doubleValue()
                    * (1 + ((Number) rule.getOrDefault("tolerance", 0.6)).doubleValue());
        }
        return fallback;
    }

    /** 指标的场景级下限（比例类合规指标用）：abs_min 优先，否则 max(硬下限, 基线*(1-容差))；基线缺失用硬下限。 */
    @SuppressWarnings("unchecked")
    private static double lowerBound(Map<String, Object> fingerprint, String key, double hardFloor) {
        Map<String, Object> baselineMap = (Map<String, Object>) fingerprint.get("baseline");
        if (baselineMap == null) return hardFloor;
        Map<String, Object> rule = (Map<String, Object>) baselineMap.get(key);
        if (rule == null || !rule.containsKey("value")) return hardFloor;
        if (rule.containsKey("abs_min")) {
            return ((Number) rule.get("abs_min")).doubleValue();
        }
        double v = ((Number) rule.get("value")).doubleValue();
        double tol = ((Number) rule.getOrDefault("tolerance", 0.6)).doubleValue();
        return Math.max(hardFloor, v * (1 - tol));
    }

    @SuppressWarnings("unchecked")
    /** 指纹指标中文名（写作提示用；未收录的键回退指标名）。 */
    private static final Map<String, String> METRIC_LABELS = Map.ofEntries(
            Map.entry("line_avg_len", "每行平均长度（字）"),
            Map.entry("dash_per1k", "破折号"),
            Map.entry("ellipsis_per1k", "省略号"),
            Map.entry("exclam_per1k", "感叹号"),
            Map.entry("question_per1k", "问号"),
            Map.entry("dunhao_per1k", "顿号"),
            Map.entry("digit_per1k", "阿拉伯数字"),
            Map.entry("simile_per1k", "比喻"),
            Map.entry("dialogue_density_per1k", "对白行"),
            Map.entry("dialogue_end_punct_ratio", "对白句末标点占比"),
            // 口头禅类指标按「口头禅『X』」命名：这三项是现有风格包里实际出现的全部（少了会以键名裸奔到界面上）
            Map.entry("tic_haiyou_per1k", "口头禅「还有」"),
            Map.entry("tic_laizhe_per1k", "口头禅「来着」"),
            Map.entry("tic_shunbian_per1k", "口头禅「顺便」"));

    /** 指纹指标中文名（未收录的键回退键名本身）：写作提示与文风指纹页共用这一份口径，勿在他处另建映射。 */
    public static String metricLabel(String key) {
        return METRIC_LABELS.getOrDefault(key, key);
    }

    /**
     * 指纹量化目标转写作口径（场景生成 system 注入）：与 fingerprintChecks 同一套判定数学
     * （abs_min 两端 / 稀疏指标（基线<3）只设上限 / 其余 ±tolerance、abs_max 优先）——
     * 写手第一稿就朝及格线写，而不是靠门禁打回后试错。无指纹返回 null。
     */
    @SuppressWarnings("unchecked")
    public String fingerprintGuidance(long novelId) {
        String json = stylePackData.findFingerprintByNovel(novelId);
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, Object> base;
        try {
            base = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("指纹量化目标解析失败（作品 {}），本章提示词不带量化目标（写手闭卷）：{}", novelId, e.getMessage());
            return null;
        }
        Map<String, Object> baselineMap = (Map<String, Object>) base.get("baseline");
        if (baselineMap == null || baselineMap.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : baselineMap.entrySet()) {
            String key = e.getKey();
            Map<String, Object> rule = (Map<String, Object>) e.getValue();
            if (rule == null || !rule.containsKey("value")) {
                continue;
            }
            double v = ((Number) rule.get("value")).doubleValue();
            double tol = ((Number) rule.get("tolerance")).doubleValue();
            double upper = rule.containsKey("abs_max")
                    ? ((Number) rule.get("abs_max")).doubleValue() : v * (1 + tol);
            String label = metricLabel(key);
            String unit = key.endsWith("_per1k") ? "每千字" : "";
            String range;
            if (rule.containsKey("abs_min")) {
                range = num(((Number) rule.get("abs_min")).doubleValue()) + "–" + num(upper);
            } else if (v < 3.0) {
                range = "≤" + num(upper);
            } else {
                range = num(v * (1 - tol)) + "–" + num(upper);
            }
            sb.append(label).append(unit).append(' ').append(range)
              .append("（目标 ").append(num(v)).append("）；");
        }
        List<String> directives = derivedDirectives(baselineMap);
        if (!directives.isEmpty()) {
            sb.append("\n执行口径：");
            for (String d : directives) {
                sb.append("\n- ").append(d);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 从指纹数据推导写作口径（长句/碎句取向、标点禁用与保底、口头禅），与门禁判定同源。 */
    @SuppressWarnings("unchecked")
    private List<String> derivedDirectives(Map<String, Object> baselineMap) {
        List<String> out = new ArrayList<>();
        // 对白句末标点：无条件下发（门禁内置下限 0.5 同口径）——曾有错配画像诱导模型全书写「走吧」他说 式无标点对白
        out.add("对白句末必须带句末标点（。？！…），引号后接叙述动作时用逗号衔接——写「走吧。」他说，禁止「走吧」他说 式无标点对白");
        Map<String, Object> line = (Map<String, Object>) baselineMap.get("line_avg_len");
        if (line != null && line.containsKey("value")) {
            double v = ((Number) line.get("value")).doubleValue();
            if (v >= 40) {
                out.add("叙述段由多句长句构成，善用从句、排比与列举把信息延宕铺开——严禁一句一段的碎句排版");
            } else if (v < 25) {
                out.add("行文以短句为主，节奏干脆，少用长复合句");
            }
        }
        Map<String, Object> ell = (Map<String, Object>) baselineMap.get("ellipsis_per1k");
        if (ell != null) {
            double up = ((Number) ell.get("value")).doubleValue() * (1 + ((Number) ell.get("tolerance")).doubleValue());
            if (ell.containsKey("abs_max")) {
                up = ((Number) ell.get("abs_max")).doubleValue();
            }
            if (up < 0.5) {
                out.add("禁用省略号");
            }
        }
        Map<String, Object> dash = (Map<String, Object>) baselineMap.get("dash_per1k");
        if (dash != null) {
            double up = ((Number) dash.get("value")).doubleValue() * (1 + ((Number) dash.get("tolerance")).doubleValue());
            if (dash.containsKey("abs_max")) {
                up = ((Number) dash.get("abs_max")).doubleValue();
            }
            if (up < 4) {
                out.add("破折号克制（每千字 ≤" + num(up) + "），补充说明改用逗号或句号衔接");
            }
        }
        for (String key : new String[]{"dunhao_per1k", "exclam_per1k"}) {
            Map<String, Object> rule = (Map<String, Object>) baselineMap.get(key);
            if (rule != null && rule.containsKey("value")) {
                double v = ((Number) rule.get("value")).doubleValue();
                double lo = v * (1 - ((Number) rule.get("tolerance")).doubleValue());
                if (v >= 3 && lo > 0.3) {
                    out.add("基线非零的标点有下界（通篇密度为 0 同样打回）——" + metricLabel(key)
                            + "按目标 " + num(v) + "/千字 左右安排");
                    break;
                }
            }
        }
        for (Map.Entry<String, Object> e : baselineMap.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("tic_")) {
                continue;
            }
            Map<String, Object> rule = (Map<String, Object>) e.getValue();
            if (rule != null && rule.containsKey("abs_max")) {
                String word = key.substring("tic_".length(), key.length() - "_per1k".length());
                out.add("口头禅「" + word + "」能删则删（每千字 ≤" + num(((Number) rule.get("abs_max")).doubleValue()) + "）");
            }
        }
        return out;
    }

    private static String num(double d) {
        if (d >= 20) {
            return String.valueOf(Math.round(d));
        }
        if (d >= 1) {
            return String.format("%.1f", d);
        }
        return String.format("%.2f", d);
    }

    /**
     * 本书指纹基线。**没有指纹时返回空基线（fail-open）而不是抛异常**——「未选预设导入的书」在用户
     * 采纳「按本书正文提指纹」草稿之前就是这种状态，抛异常会让这本书的门禁直接炸（B0001 + 章 FAILED），
     * 而空基线的实际语义是「跳过指纹类指标校验」，其余硬规则（字数带/黑名单/直引号/比喻上限）照常执行。
     */
    private Map<String, Object> fingerprint(long novelId) {
        String json = stylePackData.findFingerprintByNovel(novelId);
        if (json == null || json.isBlank()) {
            log.warn("本书没有指纹基线，门禁跳过指纹类指标（只跑字数带/黑名单/直引号/比喻上限）：novelId={}", novelId);
            // 必须是「有 baseline 键但空对象」的形状：下游 fingerprintChecks/upperBound 直接取 fingerprint.get("baseline")，
            // 给裸空 Map 会在取 baseline 时 NPE（fail-open 变成换个地方炸——单测实测踩到过）。
            return Map.of("baseline", Map.of());
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("fingerprint 不可解析", e);
        }
    }

    private GateCheck check(String name, Object value, Object expect, Object absMax, boolean ok) {
        return new GateCheck(name, value, expect, absMax, ok);
    }

    // ===== 纯函数指标计算（静态，供单测） =====

    /** 开篇复写行数：本章前 3 个非空行中，有多少行逐行出现在上一章末 3 个非空行里。 */
    public static int openingOverlap(String currentText, String previousText) {
        List<String> prev = nonEmptyLines(previousText);
        java.util.Set<String> tail = new java.util.HashSet<>();
        for (int i = Math.max(0, prev.size() - 3); i < prev.size(); i++) {
            tail.add(prev.get(i));
        }
        int n = 0;
        List<String> cur = nonEmptyLines(currentText);
        for (int i = 0; i < Math.min(3, cur.size()); i++) {
            if (tail.contains(cur.get(i))) n++;
        }
        return n;
    }

    private static List<String> nonEmptyLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            if (!l.strip().isEmpty()) out.add(l.strip());
        }
        return out;
    }

    public static Map<String, Object> computeMetrics(String text) {
        String[] lines = text.split("\n");
        List<String> nonEmpty = new ArrayList<>();
        for (String l : lines) if (!l.strip().isEmpty()) nonEmpty.add(l.strip());
        int cjk = (int) CN.matcher(text).results().count();
        if (cjk == 0 || nonEmpty.isEmpty()) {
            // 零中文/空文本（模型拒答、供应商风控英文回执、乱码）：返回**全量键的零值**而不是残缺 map——
            // 消费方按正常键集取值（checkScene 取 dialogue_end_punct_ratio 等），残缺 map 会 NPE 炸掉整章
            //（2026-10-07 实弹：MiniMax 内容审核拒答 60 字符英文 → 门禁 NPE → 章 FAILED）。
            // 零值语义 = 最差稿：长度/对白指标全不过 → 走既有的「门禁不过带意见重写」自愈，而不是崩。
            Map<String, Object> zero = new LinkedHashMap<>();
            zero.put("cjk", 0);
            zero.put("line_avg_len", 0);
            zero.put("dialogue_density_per1k", 0);
            zero.put("dunhao_per1k", 0);
            zero.put("dash_per1k", 0);
            zero.put("ellipsis_per1k", 0);
            zero.put("exclam_per1k", 0);
            zero.put("digit_per1k", 0);
            zero.put("tic_laizhe_per1k", 0);
            zero.put("tic_shunbian_per1k", 0);
            zero.put("tic_haiyou_per1k", 0);
            zero.put("dialogue_end_punct_ratio", 0);
            zero.put("simile_per1k", 0);
            return zero;
        }
        double per1k = 1000.0 / cjk;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cjk", cjk);
        m.put("line_avg_len", round(nonEmpty.stream().mapToInt(String::length).average().orElse(0)));
        m.put("dialogue_density_per1k", round(count(text, "「") * per1k));
        m.put("dunhao_per1k", round(count(text, "、") * per1k));
        m.put("dash_per1k", round(count(text, "——") * per1k));
        m.put("ellipsis_per1k", round(count(text, "……") * per1k));
        m.put("exclam_per1k", round(count(text, "！") * per1k));
        m.put("digit_per1k", round(countDigits(text) * per1k));
        m.put("tic_laizhe_per1k", round(count(text, "来着") * per1k));
        m.put("tic_shunbian_per1k", round(count(text, "顺便") * per1k));
        m.put("tic_haiyou_per1k", round(count(text, "还有") * per1k));
        m.put("dialogue_end_punct_ratio", dialogueEndPunctRatio(nonEmpty));
        m.put("simile_per1k", round((count(text, "像") + count(text, "仿佛") + count(text, "如同")
                + count(text, "好似")) * per1k));
        return m;
    }

    private static double dialogueEndPunctRatio(List<String> lines) {
        int total = 0, punct = 0;
        for (String s : lines) {
            if (s.endsWith("」")) {
                total++;
                String inner = s.substring(0, s.lastIndexOf("」"));
                if (!inner.isEmpty() && "。？！…".indexOf(inner.charAt(inner.length() - 1)) >= 0) punct++;
            }
        }
        return total == 0 ? 0.0 : Math.round(punct * 1000.0 / total) / 1000.0;
    }

    private static long count(String t, String s) {
        return (t.length() - t.replace(s, "").length()) / s.length();
    }

    private static int countDigits(String t) {
        int n = 0;
        for (char c : t.toCharArray()) if (c >= '0' && c <= '9') n++;
        return n;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
