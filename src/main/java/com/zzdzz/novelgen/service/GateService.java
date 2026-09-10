package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** AI 腔黑名单（起步版，随实绩滚动） */
    private static final List<String> BANNED = List.of(
            "心中暗想", "不由得", "仿佛在诉说", "在空气中弥漫", "空气仿佛凝固",
            "嘴角勾起一抹", "眼底闪过一丝", "一丝不易察觉");

    private static final Pattern CN = Pattern.compile("[\\u4e00-\\u9fff]");

    private final StylePackDAO stylePackDAO;
    private final GateReportDAO gateReportDAO;

    public GateService(StylePackDAO stylePackDAO, GateReportDAO gateReportDAO) {
        this.stylePackDAO = stylePackDAO;
        this.gateReportDAO = gateReportDAO;
    }

    @SuppressWarnings("unchecked")
    public boolean checkChapter(long novelId, long chapterId, int chapterNo, String text,
                                int budgetMin, int budgetMax) {
        Map<String, Object> base = fingerprint(novelId);
        Map<String, Object> metrics = computeMetrics(text);
        List<Map<String, Object>> checks = new ArrayList<>();

        int words = ((Number) metrics.get("cjk")).intValue();
        boolean lenOk = words >= budgetMin * 0.85 && words <= budgetMax * 1.15;
        checks.add(check("chapter_length", words, budgetMin * 0.85, budgetMax * 1.15, lenOk));

        checks.addAll(fingerprintChecks(base, metrics));

        boolean noStraightQuote = !text.contains("\"");
        checks.add(check("no_straight_quote", text.contains("\"") ? 1 : 0, 0, 0, noStraightQuote));

        List<String> hits = new ArrayList<>();
        for (String phrase : BANNED) {
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
    public boolean checkScene(long novelId, long chapterId, long sceneId, int sceneNo, String text) {
        Map<String, Object> base = fingerprint(novelId);
        Map<String, Object> metrics = computeMetrics(text);
        List<Map<String, Object>> checks = new ArrayList<>();

        checks.add(check("no_straight_quote", text.contains("\"") ? 1 : 0, 0, 0, !text.contains("\"")));
        List<String> hits = new ArrayList<>();
        for (String phrase : BANNED) {
            if (text.contains(phrase)) hits.add(phrase);
        }
        checks.add(check("banned_phrases", hits.size(), 0, 0, hits.isEmpty()));

        double dunhao = ((Number) metrics.get("dunhao_per1k")).doubleValue();
        double exclam = ((Number) metrics.get("exclam_per1k")).doubleValue();
        double digit = ((Number) metrics.get("digit_per1k")).doubleValue();
        double endPunct = ((Number) metrics.get("dialogue_end_punct_ratio")).doubleValue();
        double dlg = ((Number) metrics.get("dialogue_density_per1k")).doubleValue();
        double endPunctMax = absMaxOf(base, "dialogue_end_punct_ratio", 0.35);
        double digitMax = absMaxOf(base, "digit_per1k", 12.0);
        checks.add(check("dunhao_per1k", dunhao, 0, 1.0, dunhao <= 1.0));
        checks.add(check("exclam_per1k", exclam, 0, 2.0, exclam <= 2.0));
        checks.add(check("digit_per1k", digit, 0, digitMax, digit <= digitMax));
        checks.add(check("dialogue_end_punct_ratio", endPunct, 0, endPunctMax, endPunct <= endPunctMax));
        // 对话密度是手搓风最大的杠杆，场景级就要盯（宽界：基线 ±60%）
        checks.add(check("dialogue_density_per1k", dlg, 7.6, 30.2, dlg >= 7.6 && dlg <= 30.2));

        boolean passed = checks.stream().allMatch(c -> (Boolean) c.get("ok"));
        gateReportDAO.insert(chapterId, sceneId, "mechanical", 0, passed,
                Map.of("scene_no", sceneNo, "checks", checks));
        return passed;
    }

    public String failureSummary(long chapterId) {
        return gateReportDAO.findLatestFailureJson(chapterId);
    }

    /** 指纹指标对照：稀疏特征（基线<3/千字）下界归零只防滥用，其余 ±tolerance。 */
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
            boolean ok;
            if (rule.containsKey("abs_max")) {
                ok = value <= ((Number) rule.get("abs_max")).doubleValue();
            } else if (v < 3.0) {
                // 稀疏指纹（来着/顺便等）下界归零：几千字里出现 0 次属正常，只防滥用
                ok = value <= v * (1 + tol);
            } else {
                ok = value >= v * (1 - tol) && value <= v * (1 + tol);
            }
            checks.add(check(key, value, rule.get("value"), rule.get("abs_max"), ok));
        }
        return checks;
    }

    /** 从指纹 baseline 取某指标的 abs_max；未配置时用兜底值（场景级抽样阈值与章级同源）。 */
    @SuppressWarnings("unchecked")
    private static double absMaxOf(Map<String, Object> fingerprint, String key, double fallback) {
        Map<String, Object> baselineMap = (Map<String, Object>) fingerprint.get("baseline");
        if (baselineMap == null) return fallback;
        Map<String, Object> rule = (Map<String, Object>) baselineMap.get(key);
        if (rule == null || !rule.containsKey("abs_max")) return fallback;
        return ((Number) rule.get("abs_max")).doubleValue();
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
