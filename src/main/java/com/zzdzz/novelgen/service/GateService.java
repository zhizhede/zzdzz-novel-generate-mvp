package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.GateReportDAO;
import com.zzdzz.novelgen.dao.StylePackDAO;
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
public class GateService {

    /** AI 腔黑名单兜底：风格包未配置 gate_config 时使用；正式值随包落库（V4 起）。 */
    private static final List<String> BANNED_FALLBACK = List.of(
            "心中暗想", "不由得", "仿佛在诉说", "在空气中弥漫", "空气仿佛凝固",
            "嘴角勾起一抹", "眼底闪过一丝", "一丝不易察觉");

    private static final Pattern CN = Pattern.compile("[\\u4e00-\\u9fff]");

    private final StylePackDAO stylePackDAO;
    private final GateReportDAO gateReportDAO;
    private final ChapterDAO chapterDAO;
    private final TuningService tuning;

    public GateService(StylePackDAO stylePackDAO, GateReportDAO gateReportDAO,
                       ChapterDAO chapterDAO, TuningService tuning) {
        this.stylePackDAO = stylePackDAO;
        this.gateReportDAO = gateReportDAO;
        this.chapterDAO = chapterDAO;
        this.tuning = tuning;
    }

    @SuppressWarnings("unchecked")
    public boolean checkChapter(long novelId, long chapterId, int chapterNo, String text,
                                int budgetMin, int budgetMax) {
        Map<String, Object> base = fingerprint(novelId);
        Map<String, Object> metrics = computeMetrics(text);
        List<Map<String, Object>> checks = new ArrayList<>();

        int words = ((Number) metrics.get("cjk")).intValue();
        Map<String, Object> gateCfg = gateConfig(novelId);
        double lenTol = configDouble(gateCfg, "chapter_length_tolerance", 0.15);
        boolean lenOk = words >= budgetMin * (1 - lenTol) && words <= budgetMax * (1 + lenTol);
        checks.add(check("chapter_length", words, budgetMin * (1 - lenTol), budgetMax * (1 + lenTol), lenOk));

        // 开篇复写检查：本章前 3 行不得与上一章末 3 行重复（场景续写惯性把衔接写成复写的实锤 bug）
        String prevText = chapterNo > 1 ? chapterDAO.findFullText(novelId, chapterNo - 1) : null;
        int overlap = prevText == null ? 0 : openingOverlap(text, prevText);
        checks.add(check("opening_overlap", overlap, 0, 0, overlap == 0));

        // 比喻密度：人类手稿 2.4-7/千字，AI 生成可冲到 15/千字（描写铺场的量化信号）
        double simileMax = tuning.d("simile_per1k_abs_max", 8.0);
        double simile = ((Number) metrics.get("simile_per1k")).doubleValue();
        checks.add(check("simile_per1k", simile, null, simileMax, simile <= simileMax));

        checks.addAll(fingerprintChecks(base, metrics));

        boolean noStraightQuote = !text.contains("\"");
        checks.add(check("no_straight_quote", text.contains("\"") ? 1 : 0, 0, 0, noStraightQuote));

        List<String> hits = new ArrayList<>();
        for (String phrase : bannedPhrases(gateCfg)) {
            if (text.contains(phrase)) hits.add(phrase);
        }
        checks.add(check("banned_phrases", hits.size(), 0, 0, hits.isEmpty()));

        boolean passed = checks.stream().allMatch(c -> (Boolean) c.get("ok"));
        gateReportDAO.insert(chapterId, null, "mechanical", 0, passed,
                Map.of("chapter_no", chapterNo, "words", words,
                        "banned_hits", hits, "checks", checks));
        return passed;
    }

    /**
     * 场景级门禁：只查确定性硬规则与方差可控的指标（对话密度/句末标点/顿号/感叹号/数字）。
     * 破折号等稀疏统计留到章级判定——几百字样本上单场景方差过大。
     */
    @SuppressWarnings("unchecked")
    public boolean checkScene(long novelId, long chapterId, long sceneId, int sceneNo, String text, int wordsBudget) {
        Map<String, Object> base = fingerprint(novelId);
        Map<String, Object> metrics = computeMetrics(text);
        List<Map<String, Object>> checks = new ArrayList<>();

        // 场景长度：预算比例带（43 章超长实锤后新增）。场景超长若放行，章级修订受 ±10% 约束救不回来
        double sceneLenMin = tuning.d("scene_len_min_ratio", 0.4);
        double sceneLenMax = tuning.d("scene_len_max_ratio", 1.6);
        int cjk = ((Number) metrics.get("cjk")).intValue();
        boolean lenOk = cjk >= wordsBudget * sceneLenMin && cjk <= wordsBudget * sceneLenMax;
        checks.add(check("scene_length", cjk, (int) (wordsBudget * sceneLenMin),
                (int) (wordsBudget * sceneLenMax), lenOk));

        checks.add(check("no_straight_quote", text.contains("\"") ? 1 : 0, 0, 0, !text.contains("\"")));
        List<String> hits = new ArrayList<>();
        for (String phrase : bannedPhrases(gateConfig(novelId))) {
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
        double endPunctMax = upperBound(base, "dialogue_end_punct_ratio", 0.35);
        double dlgMax = upperBound(base, "dialogue_density_per1k", 30.2);
        checks.add(check("dunhao_per1k", dunhao, null, dunhaoMax, dunhao <= dunhaoMax));
        checks.add(check("exclam_per1k", exclam, null, exclamMax, exclam <= exclamMax));
        checks.add(check("digit_per1k", digit, null, digitMax, digit <= digitMax));
        checks.add(check("dialogue_end_punct_ratio", endPunct, null, endPunctMax, endPunct <= endPunctMax));
        // 对话密度：场景级只防灌水（上界）；低界留章级——叙事型场景天然低对话，几百字样本下界误杀
        checks.add(check("dialogue_density_per1k", dlg, null, dlgMax, dlg <= dlgMax));

        boolean passed = checks.stream().allMatch(c -> (Boolean) c.get("ok"));
        gateReportDAO.insert(chapterId, sceneId, "mechanical", 0, passed,
                Map.of("scene_no", sceneNo, "checks", checks));
        return passed;
    }

    public String failureSummary(long chapterId) {
        return gateReportDAO.findLatestFailureJson(chapterId);
    }

    /** 场景级失败意见：只取该场景自己的最新失败报告。 */
    public String failureSummary(long chapterId, long sceneId) {
        return gateReportDAO.findLatestSceneFailureJson(chapterId, sceneId);
    }

    /** 失败指标人话摘要（事件流水用）：「dialogue_density_per1k=3.92（基线12.51）」。无失败返回空串。 */
    public String failedChecksText(long chapterId) {
        return failedChecksText(gateReportDAO.findLatestFailureJson(chapterId));
    }

    public String failedChecksText(long chapterId, long sceneId) {
        return failedChecksText(gateReportDAO.findLatestSceneFailureJson(chapterId, sceneId));
    }

    @SuppressWarnings("unchecked")
    private String failedChecksText(String failureJson) {
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
                sb.append(c.get("check")).append('=').append(c.get("value"))
                        .append("（基线").append(c.get("baseline")).append('）');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 指纹指标对照：稀疏特征（基线<3/千字）下界归零只防滥用，其余 ±tolerance；abs_min 显式下界（对话密度防叙述铺场）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fingerprintChecks(Map<String, Object> base,
                                                        Map<String, Object> metrics) {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            String key = e.getKey();
            if (key.equals("cjk")) continue;
            Map<String, Object> baselineMap = (Map<String, Object>) base.get("baseline");
            Map<String, Object> rule = (Map<String, Object>) baselineMap.get(key);
            if (rule == null) continue;
            double value = ((Number) e.getValue()).doubleValue();
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
        String json = stylePackDAO.findGateConfigByNovel(novelId);
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> fingerprint(long novelId) {
        String json = stylePackDAO.findFingerprintByNovel(novelId);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("fingerprint 不可解析", e);
        }
    }

    private Map<String, Object> check(String name, Object value, Object expect, Object absMax, boolean ok) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", name);
        m.put("value", value);
        m.put("baseline", expect);
        if (absMax != null) m.put("abs_max", absMax);
        m.put("ok", ok);
        return m;
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
        if (cjk == 0 || nonEmpty.isEmpty()) return Map.of("cjk", 0);
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
