package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.model.dto.NovelDTO;
import com.zzdzz.novelgen.model.dto.PresetCorpusDTO;
import com.zzdzz.novelgen.model.dto.StylePackDTO;
import com.zzdzz.novelgen.model.vo.PresetCorpusVO;
import com.zzdzz.novelgen.model.vo.PresetDraftVO;
import com.zzdzz.novelgen.model.vo.PresetVO;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.PresetCorpusDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段三·特征提取管线第一块（机械层，零 LLM）：品类语料 → 指纹带宽 + 章长预算带 → 预设。
 * 量级完全用户驱动：品类随时新增（自由命名）、语料导入多少算多少、n&lt;10 低置信提示不拒绝。
 * 黑名单对照挖掘 / rulesMd LLM 提取 / 范例库 / LLM 判定指标抽样属后续批。
 */
@Service
@RequiredArgsConstructor
public class GenrePresetService {

    private final PresetCorpusDataService corpusData;
    private final StylePackDataService stylePackData;
    private final NovelDataService novelData;
    private final ObjectMapper mapper;

    // ===== 语料 CRUD =====

    public List<PresetCorpusDataService.GenreSummary> genres() {
        return corpusData.genreSummaries();
    }

    public List<PresetCorpusVO> listCorpus(String genre) {
        return corpusData.listByGenre(requireText(genre, "品类必填")).stream()
                .map(PresetCorpusVO::from).toList();
    }

    public void addCorpus(String genre, String title, String content) {
        String g = requireText(genre, "品类必填");
        if (g.length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "品类名过长（≤64 字）");
        }
        String c = requireText(content, "语料正文必填");
        PresetCorpusDTO row = new PresetCorpusDTO();
        row.setGenre(g);
        row.setTitle(title == null || title.isBlank() ? null : title.strip());
        row.setContent(c);
        row.setWordCount(((Number) GateService.computeMetrics(c).get("cjk")).intValue());
        corpusData.save(row);
    }

    public void deleteCorpus(long id) {
        if (corpusData.getById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "语料不存在: " + id);
        }
        corpusData.update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PresetCorpusDTO>()
                .eq("id", id).set("is_deleted", true).set("delete_time", java.time.OffsetDateTime.now()));
    }

    // ===== 提取与预设 =====

    /** 对某品类语料提取草稿（机械指标分位带宽，规模自适应）。 */
    public PresetDraftVO extractDraft(String genre) {
        String g = requireText(genre, "品类必填");
        List<PresetCorpusDTO> rows = corpusData.listByGenre(g);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "品类「" + g + "」无语料，先导入");
        }
        Map<String, List<Double>> series = new LinkedHashMap<>();
        List<Double> cjkSeries = new ArrayList<>();
        for (PresetCorpusDTO row : rows) {
            Map<String, Object> m = GateService.computeMetrics(row.getContent());
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey().equals("cjk")) {
                    cjkSeries.add(((Number) e.getValue()).doubleValue());
                    continue;
                }
                if (e.getValue() instanceof Number n) {
                    series.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(n.doubleValue());
                }
            }
        }
        Map<String, Object> baseline = buildBaseline(series);
        double[] band = budgetBand(cjkSeries);
        List<String> notes = new ArrayList<>();
        int n = rows.size();
        if (n < 10) {
            notes.add("样本 " + n + " 章偏少（低置信）：带宽按 min/max 放宽计算，建议补语料后重提");
        }
        notes.add("本批为机械指标；黑名单对照挖掘、文风规则（rulesMd）、范例库、LLM 判定指标抽样属后续批");
        String fingerprintJson;
        try {
            fingerprintJson = mapper.writeValueAsString(Map.of("baseline", baseline));
        } catch (Exception e) {
            throw new IllegalStateException("指纹序列化失败", e);
        }
        return new PresetDraftVO(g, n, n < 10,
                (int) band[0], (int) band[1], round2(band[2]),
                baseline.size(), fingerprintJson, notes);
    }

    /** 采纳为预设：机械提取幂等重跑（零成本），落 is_preset 风格包行。 */
    public long adopt(String genre, String name, String description) {
        String presetName = requireText(name, "预设名必填");
        PresetDraftVO d = extractDraft(genre);
        String gateConfig = "{\"banned_phrases\":[],\"no_straight_quote\":true,\"chapter_length_tolerance\":"
                + d.chapterLengthTolerance() + "}";
        return stylePackData.insertPreset(presetName,
                description == null ? "源品类：" + d.genre() + "，" + d.chapters() + " 章语料" : description,
                "", d.fingerprintJson(), gateConfig);
    }

    public List<PresetVO> listPresets() {
        return stylePackData.listPresets().stream().map(PresetVO::from).toList();
    }

    /** 应用到书：拷贝预设的指纹/门禁/规则进该书的风格包（覆盖，前端二次确认）。 */
    public void applyToNovel(long presetId, long novelId) {
        StylePackDTO preset = stylePackData.getById(presetId);
        if (preset == null || !preset.isPreset()) {
            throw new BizException(ErrorCode.NOT_FOUND, "预设不存在: " + presetId);
        }
        NovelDTO novel = novelData.getById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在: " + novelId);
        }
        String gate = stylePackData.findGateConfigById(presetId);
        stylePackData.updateFingerprint(novel.getStylePackId(), preset.getFingerprint());
        if (gate != null) {
            stylePackData.updateGateConfigByNovel(novelId, gate);
        }
        stylePackData.updateRulesMdByNovel(novelId, preset.getRulesMd() == null ? "" : preset.getRulesMd());
    }

    // ===== 提取数学（纯函数，可单测） =====

    /** 指纹基线：每指标 value=中位 / tolerance=分布相对半宽（夹紧 0.15-1.5）/ abs_max=分布上界×1.1。全零指标剔除。 */
    static Map<String, Object> buildBaseline(Map<String, List<Double>> series) {
        Map<String, Object> baseline = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : series.entrySet()) {
            List<Double> v = e.getValue();
            if (v.stream().allMatch(x -> x == 0)) {
                continue;
            }
            double p10 = percentile(v, 0.10);
            double p50 = percentile(v, 0.50);
            double p90 = percentile(v, 0.90);
            double p95 = percentile(v, 0.95);
            double tolerance = Math.min(1.5, Math.max(0.15, (p90 - p10) / (2 * Math.max(p50, 0.5))));
            double absMax = Math.max(p95 * 1.1, p50 * 1.3);
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("value", round2(p50));
            rule.put("tolerance", round2(tolerance));
            rule.put("abs_max", round2(absMax));
            baseline.put(e.getKey(), rule);
        }
        return baseline;
    }

    /** 章长预算带 [min, max, tolerance]：p15/p85 取整到 50 字；容差=带相对宽度夹紧 0.10-0.35；n<10 用 min/max 放宽。 */
    static double[] budgetBand(List<Double> cjkSeries) {
        double lo;
        double hi;
        if (cjkSeries.size() < 10) {
            lo = cjkSeries.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            hi = cjkSeries.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        } else {
            lo = percentile(cjkSeries, 0.15);
            hi = percentile(cjkSeries, 0.85);
        }
        double mid = percentile(cjkSeries, 0.50);
        double tolerance = Math.min(0.35, Math.max(0.10, (hi - lo) / Math.max(mid, 1)));
        return new double[]{Math.round(lo / 50) * 50, Math.round(hi / 50) * 50, tolerance};
    }

    /** 线性插值分位（小样本稳健，无需正态假设）。 */
    static double percentile(List<Double> values, double q) {
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        double idx = q * (sorted.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(lo + 1, sorted.size() - 1);
        double frac = idx - lo;
        return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String requireText(String s, String message) {
        if (s == null || s.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, message);
        }
        return s.strip();
    }
}
