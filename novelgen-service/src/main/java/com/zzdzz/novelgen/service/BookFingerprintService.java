package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.model.vo.BookFingerprintDraftVO;
import com.zzdzz.novelgen.model.vo.FingerprintApplyVO;
import com.zzdzz.novelgen.model.vo.FingerprintMetricVO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按本书正文提指纹（机械层，零 LLM）：样本单元 = 一章正文，与品类语料提取同一套数学
 * （{@link GenrePresetService#buildBaseline} / {@link GenrePresetService#budgetBand}，同包直取，不复制口径）。
 *
 * 用途两条：导入书籍后（正文已在库）就地按自己的正文提一份指纹；任何已有书（AI 生成/CLI 导原稿）想重校准指纹。
 * 一律走「草稿 → 用户确认 → 采纳」：指纹是门禁阈值的来源，覆盖它等于改这本书后续生成的宽严，不能静默发生。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookFingerprintService {

    private final ChapterDataService chapterData;
    private final StylePackDataService stylePackData;
    private final NovelDataService novelData;
    private final ObjectMapper mapper;

    /** 低置信阈值：章数 <10 与品类语料提取同口径（带宽按 min/max 放宽）。 */
    private static final int LOW_CONFIDENCE_CHAPTERS = 10;
    /** 样本过少的强提示阈值：低于此值提出来基本是噪声，允许采纳但要显式警示。 */
    private static final int TOO_FEW_CHAPTERS = 3;
    /** 正文总量下限（汉字）：低于此值连噪声都算不上，直接拒绝（指纹素材硬下限经验值 1000 字，这里留一倍余量）。 */
    private static final long MIN_TOTAL_CHARS = 2000;

    /** 按本书正文试提指纹（草稿，不落库）。 */
    public BookFingerprintDraftVO draft(long novelId) {
        NovelDO novel = requireNovel(novelId);
        List<ChapterDataService.ChapterTextWithTitleRow> rows = chapterData.listTextsByNovel(novelId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "这本书还没有正文——导入正文或生成章节后才能按本书正文提指纹");
        }
        Map<String, List<Double>> series = new LinkedHashMap<>();
        List<Double> cjkSeries = new ArrayList<>();
        long totalChars = 0;
        int used = 0;
        for (ChapterDataService.ChapterTextWithTitleRow row : rows) {
            Map<String, Object> metrics = GateService.computeMetrics(row.fullText());
            for (Map.Entry<String, Object> e : metrics.entrySet()) {
                if (e.getKey().equals("cjk")) {
                    double cjk = ((Number) e.getValue()).doubleValue();
                    cjkSeries.add(cjk);
                    totalChars += (long) cjk;
                    continue;
                }
                if (e.getValue() instanceof Number n) {
                    series.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(n.doubleValue());
                }
            }
            used++;
        }
        if (totalChars < MIN_TOTAL_CHARS) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "本书正文只有 " + totalChars + " 字，太少——指纹要几千字以上才有意义（建议 ≥" + MIN_TOTAL_CHARS + " 字）");
        }
        Map<String, Object> baseline = GenrePresetService.buildBaseline(series);
        double[] band = GenrePresetService.budgetBand(cjkSeries);
        // 指标行与 JSON 由同一份 baseline 产出（不再二次解析自己刚序列化的文本）
        JsonNode baselineNode = mapper.valueToTree(baseline);
        List<FingerprintMetricVO> metricRows = FingerprintMetricVO.parse(baselineNode);
        String fingerprintJson;
        try {
            fingerprintJson = mapper.writeValueAsString(Map.of("baseline", baseline));
        } catch (Exception e) {
            throw new IllegalStateException("指纹序列化失败", e);
        }
        List<String> notes = new ArrayList<>();
        if (used < LOW_CONFIDENCE_CHAPTERS) {
            notes.add("样本 " + used + " 章偏少（低置信）：带宽按 min/max 放宽计算，建议正文更多章后重提");
        }
        if (used < TOO_FEW_CHAPTERS) {
            notes.add("样本过少（" + used + " 章）：单章文风波动会直接进阈值，采纳后门禁可能过严或过松，建议先攒够 3 章以上");
        }
        if (baseline.isEmpty()) {
            notes.add("没有提取到任何非零指标——正文可能不是小说文体（或几乎全是对白以外的噪声）");
        }
        // 与门禁既有硬规则的交汇（实弹踩到）：对白句末标点占比在场景/章级都按硬下限 0.5 判定，与本书指纹无关。
        // 两种情况都会撞上：①本书基线本就低于 0.5（如「导入书B」风格 0.09）；②全书普遍无标点 → 全零被剔除、
        // 基线里根本没有这一项（此时 GateService.lowerBound 回退硬下限，判定照旧）。
        Object endPunct = baseline.get("dialogue_end_punct_ratio");
        double endPunctValue = endPunct instanceof Map<?, ?> rule && rule.get("value") instanceof Number v
                ? v.doubleValue() : -1;
        if (endPunctValue < 0.5) {
            notes.add("提示：本书对白句末标点占比"
                    + (endPunctValue < 0 ? "未形成基线（全书对白句末普遍无标点，该项已按全零剔除）" : "基线 " + endPunctValue)
                    + "，而门禁对该指标硬性要求 ≥0.5——采纳指纹不改这条既有反 AI 腔规则，续写章节会被要求给对白句末加标点");
        }
        notes.add("统计口径：每章一个样本，指标为每千字密度与行均长，与场景/章级门禁同一套判定数学；全零指标已剔除。");
        log.info("按本书正文提指纹草稿：novelId={} 章数={} 字数={} 指标={}", novelId, used, totalChars, baseline.size());
        return new BookFingerprintDraftVO(novelId, novel.getTitle(), used, totalChars,
                used < LOW_CONFIDENCE_CHAPTERS,
                (int) band[0], (int) band[1], GenrePresetService.round2(band[2]),
                baseline.size(), metricRows, fingerprintJson, notes);
    }

    /**
     * 采纳草稿：覆盖本书风格包指纹；可选同步章长带进本书 gate_config。
     * 指纹 JSON 由前端原样回传（所见即所得），此处只校验「含非空 baseline 的 JSON 对象」与带宽数值区间。
     */
    public void apply(long novelId, FingerprintApplyVO vo) {
        requireNovel(novelId);
        if (vo == null || vo.fingerprintJson() == null || vo.fingerprintJson().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "指纹内容为空");
        }
        JsonNode root;
        try {
            root = mapper.readTree(vo.fingerprintJson());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "指纹必须是合法 JSON");
        }
        if (!root.isObject() || !root.path("baseline").isObject() || root.path("baseline").isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "指纹必须是含 baseline 指标对象的 JSON");
        }
        NovelDO novel = novelData.getById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在或已删除: " + novelId);
        }
        Long packId = novel.getStylePackId();
        if (packId == null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "这本书没有风格包（无指纹载体）——先在素材库给它挂一个品类预设");
        }
        stylePackData.updateFingerprint(packId, root.toString());
        if (Boolean.TRUE.equals(vo.syncBudgetBand())) {
            // 勾了同步却不给数值 = 调用方 bug：报错而不是静默跳过（静默会让用户以为章长带已同步）
            if (vo.budgetMin() == null || vo.budgetMax() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "勾选了同步章长带，但没有给出章长带数值");
            }
            int min = clampInt(vo.budgetMin(), 300, 20000, "章长下限");
            int max = clampInt(vo.budgetMax(), 300, 20000, "章长上限");
            if (min > max) {
                throw new BizException(ErrorCode.PARAM_ERROR, "章长下限不能大于上限");
            }
            double tolerance = vo.chapterLengthTolerance() == null ? 0.15
                    : Math.max(0.0, Math.min(0.5, vo.chapterLengthTolerance()));
            stylePackData.updateGateConfigByNovel(novelId,
                    DeriveSupport.applyBudgetBand(stylePackData.findGateConfigByNovel(novelId), min, max, tolerance));
        }
        log.info("按本书正文提指纹已采纳：novelId={} packId={} 指标={} 同步章长带={}", novelId, packId,
                root.path("baseline").size(), Boolean.TRUE.equals(vo.syncBudgetBand()));
    }

    private NovelDO requireNovel(long novelId) {
        NovelDO novel = novelData.getById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在: " + novelId);
        }
        return novel;
    }

    private static int clampInt(int value, int lo, int hi, String label) {
        if (value < lo || value > hi) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "超出范围（" + lo + "-" + hi + "）：" + value);
        }
        return value;
    }
}
