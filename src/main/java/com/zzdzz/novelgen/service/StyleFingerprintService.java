package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.model.dto.ImportedSampleDTO;
import com.zzdzz.novelgen.model.dto.NovelDTO;
import com.zzdzz.novelgen.model.dto.StylePackDTO;
import com.zzdzz.novelgen.model.vo.StyleFingerprintQueryVO;
import com.zzdzz.novelgen.model.vo.StyleFingerprintVO;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.PresetCorpusDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文风指纹库（只读聚合查询）：把散在三处的指纹提取结果收成一张表——
 * 导入样本（imported_samples.analysis 快照）、品类预设与书籍风格包（style_packs.fingerprint）。
 * 三者数据源各自独立，此处只做读模型拼装 + 筛选排序，不写库、不改任何提取与门禁口径。
 */
@Service
@RequiredArgsConstructor
public class StyleFingerprintService {

    private final ImportedSampleDataService sampleData;
    private final StylePackDataService stylePackData;
    private final NovelDataService novelData;
    private final PresetCorpusDataService corpusData;
    private final ObjectMapper mapper;

    /** 指纹页列表：三来源合并 → 条件筛选 → 排序。 */
    public List<StyleFingerprintVO> query(StyleFingerprintQueryVO condition) {
        List<StylePackDTO> presets = stylePackData.listPresets();
        Map<Long, StylePackDTO> presetById = new LinkedHashMap<>();
        for (StylePackDTO preset : presets) {
            presetById.put(preset.getId(), preset);
        }
        Map<Long, ImportedSampleDTO> sampleById = new LinkedHashMap<>();
        for (ImportedSampleDTO sample : sampleData.listAlive()) {
            sampleById.put(sample.getId(), sample);
        }
        Map<String, long[]> genreScale = genreScale();

        List<StyleFingerprintVO> rows = new ArrayList<>();
        rows.addAll(sampleRows(presetById));
        rows.addAll(presetRows(presets, sampleById, genreScale));
        rows.addAll(bookRows(sampleById, genreScale));

        return rows.stream()
                .filter(row -> matches(row, condition))
                .sorted(comparator(condition == null ? null : condition.sort()))
                .toList();
    }

    // ===== 三来源读模型 =====

    /** 导入样本行：analysis 为空（早于台账功能的 backfill 导入）时回退用其采纳预设的指纹，保证仍有指标可看。 */
    private List<StyleFingerprintVO> sampleRows(Map<Long, StylePackDTO> presetById) {
        List<StyleFingerprintVO> rows = new ArrayList<>();
        for (ImportedSampleDTO sample : sampleData.listAlive()) {
            JsonNode analysis = parse(sample.getAnalysis());
            JsonNode fingerprint = firstText(analysis, "fingerprintJson");
            JsonNode baseline = baselineOf(fingerprint);
            StylePackDTO adoptedPreset = sample.getPresetId() == null ? null : presetById.get(sample.getPresetId());
            if (baseline == null && adoptedPreset != null) {
                baseline = baselineOf(parse(adoptedPreset.getFingerprint()));
            }
            List<StyleFingerprintVO.SimilarityVO> similarities = new ArrayList<>();
            if (analysis != null) {
                for (JsonNode s : analysis.path("similarities")) {
                    similarities.add(new StyleFingerprintVO.SimilarityVO(s.path("presetId").asLong(),
                            s.path("name").asText(""), s.path("score").asDouble(), s.path("comparable").asBoolean(true)));
                }
            }
            rows.add(new StyleFingerprintVO(
                    "SAMPLE", sample.getId(), sample.getTitle(),
                    adoptedPreset == null ? null : adoptedPreset.getName(),
                    sample.getGenre(), "台账来源：" + sample.getSource(),
                    sample.getChunks(), sample.getTotalChars(),
                    metricCount(baseline, analysis == null ? null : analysis.path("metricCount")),
                    analysis == null ? null : intOrNull(analysis, "budgetMin"),
                    analysis == null ? null : intOrNull(analysis, "budgetMax"),
                    null,
                    analysis == null ? null : analysis.path("lowConfidence").asBoolean(false),
                    analysis == null ? null : analysis.path("recommendation").asText(null),
                    sample.getId(), sample.getPresetId(),
                    adoptedPreset == null ? null : adoptedPreset.getName(),
                    null, sample.getCreateTime(), sample.getUpdateTime(),
                    baseline != null, metrics(baseline),
                    fingerprint == null ? null : fingerprint.toString(),
                    stringList(analysis == null ? null : analysis.path("notes")),
                    similarities,
                    stringList(parse(sample.getTags()))));
        }
        return rows;
    }

    /** 品类预设行：语料规模按「品类」取（预设不存品类，品类经样本台账回链），章长带取自身 gate_config。 */
    private List<StyleFingerprintVO> presetRows(List<StylePackDTO> presets, Map<Long, ImportedSampleDTO> sampleById,
                                               Map<String, long[]> genreScale) {
        List<StyleFingerprintVO> rows = new ArrayList<>();
        for (StylePackDTO preset : presets) {
            String genre = null;
            for (ImportedSampleDTO sample : sampleById.values()) {
                if (preset.getId().equals(sample.getPresetId())) {
                    genre = sample.getGenre();
                    break;
                }
            }
            long[] scale = genre == null ? null : genreScale.get(genre);
            JsonNode baseline = baselineOf(parse(preset.getFingerprint()));
            double[] band = budgetBand(stylePackData.findGateConfigById(preset.getId()));
            rows.add(new StyleFingerprintVO(
                    "PRESET", preset.getId(), preset.getName(), preset.getName(), genre, preset.getDescription(),
                    scale == null ? null : (int) scale[0], scale == null ? null : scale[1],
                    metricCount(baseline, null),
                    band == null ? null : (int) band[0], band == null ? null : (int) band[1],
                    band == null ? null : band[2],
                    null, null, null, preset.getId(), preset.getName(), null,
                    preset.getCreateTime(), preset.getUpdateTime(),
                    baseline != null, metrics(baseline), preset.getFingerprint(),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
        }
        return rows;
    }

    /**
     * 书籍风格包行：只收仍存活书籍的包（软删书的孤儿包不进列表）。
     * 品类与源样本经 derive_config.sourceSampleId 回链；语料规模/章长预算留空——书籍没有「提取语料」这一读数。
     */
    private List<StyleFingerprintVO> bookRows(Map<Long, ImportedSampleDTO> sampleById, Map<String, long[]> genreScale) {
        List<StyleFingerprintVO> rows = new ArrayList<>();
        for (NovelDTO novel : novelData.listAlive()) {
            if (novel.getStylePackId() == null) {
                continue;
            }
            StylePackDTO pack = stylePackData.getById(novel.getStylePackId());
            if (pack == null) {
                continue;
            }
            Long sourceSampleId = sourceSampleId(novel.getId());
            ImportedSampleDTO source = sourceSampleId == null ? null : sampleById.get(sourceSampleId);
            JsonNode baseline = baselineOf(parse(pack.getFingerprint()));
            double[] band = budgetBand(stylePackData.findGateConfigById(pack.getId()));
            rows.add(new StyleFingerprintVO(
                    "BOOK", pack.getId(), novel.getTitle(), pack.getName(),
                    source == null ? null : source.getGenre(), novel.getDescription(),
                    null, null,
                    metricCount(baseline, null),
                    band == null ? null : (int) band[0], band == null ? null : (int) band[1],
                    band == null ? null : band[2],
                    null, null,
                    sourceSampleId, null, null, novel.getId(),
                    pack.getCreateTime(), pack.getUpdateTime(),
                    baseline != null, metrics(baseline), pack.getFingerprint(),
                    new ArrayList<>(), new ArrayList<>(),
                    source == null ? new ArrayList<>() : stringList(parse(source.getTags()))));
        }
        return rows;
    }

    // ===== 筛选与排序 =====

    /** 单行条件判定（纯函数：无库依赖，便于单测锁定筛选语义）。 */
    static boolean matches(StyleFingerprintVO row, StyleFingerprintQueryVO q) {
        if (q == null) {
            return true;
        }
        if (notAll(q.source()) && !q.source().strip().equalsIgnoreCase(row.source())) {
            return false;
        }
        if (notBlank(q.keyword())) {
            String kw = q.keyword().strip().toLowerCase(Locale.ROOT);
            String haystack = String.join("\n",
                    nullToEmpty(row.name()), nullToEmpty(row.genre()), nullToEmpty(row.description()),
                    nullToEmpty(row.stylePackName()), nullToEmpty(row.presetName()),
                    String.join(" ", row.tags())).toLowerCase(Locale.ROOT);
            if (!haystack.contains(kw)) {
                return false;
            }
        }
        if (notBlank(q.genre()) && !q.genre().strip().equals(row.genre())) {
            return false;
        }
        if ("LOW".equalsIgnoreCase(q.confidence()) && !Boolean.TRUE.equals(row.lowConfidence())) {
            return false;
        }
        if ("HIGH".equalsIgnoreCase(q.confidence()) && Boolean.TRUE.equals(row.lowConfidence())) {
            return false;
        }
        if (q.minMetrics() != null && (row.metricCount() == null || row.metricCount() < q.minMetrics())) {
            return false;
        }
        if (q.minChars() != null && (row.totalChars() == null || row.totalChars() < q.minChars())) {
            return false;
        }
        if (q.maxChars() != null && (row.totalChars() == null || row.totalChars() > q.maxChars())) {
            return false;
        }
        LocalDate from = parseDate(q.from(), "起始日期");
        LocalDate to = parseDate(q.to(), "结束日期");
        LocalDate created = row.createTime() == null ? null
                : row.createTime().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        if (from != null && (created == null || created.isBefore(from))) {
            return false;
        }
        if (to != null && (created == null || created.isAfter(to))) {
            return false;
        }
        return true;
    }

    /** 排序口径（纯函数）：未知取值走默认「提取时间倒序」。 */
    static Comparator<StyleFingerprintVO> comparator(String sort) {
        // 降序一律 nullsLast(逆序)：没有该读数的行（书籍无语料字数）排到最后，而不是被 reversed() 翻到最前
        Comparator<OffsetDateTime> timeAsc = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<OffsetDateTime> timeDesc = Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<Integer> metricsDesc = Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<Long> charsDesc = Comparator.nullsLast(Comparator.reverseOrder());
        String key = sort == null ? "" : sort.strip().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "TIME_ASC" -> Comparator.comparing(StyleFingerprintVO::createTime, timeAsc);
            case "METRICS_DESC" -> Comparator.comparing(StyleFingerprintVO::metricCount, metricsDesc);
            case "CHARS_DESC" -> Comparator.comparing(StyleFingerprintVO::totalChars, charsDesc);
            case "NAME_ASC" -> Comparator.comparing(row -> nullToEmpty(row.name()));
            default -> Comparator.comparing(StyleFingerprintVO::createTime, timeDesc);
        };
    }

    // ===== 解析助手 =====

    /** 指纹 JSON 文本 → baseline 节点；空/坏 JSON 一律回 null（指纹页只展示，不因坏数据报错）。 */
    private JsonNode baselineOf(JsonNode fingerprint) {
        if (fingerprint == null || fingerprint.isNull()) {
            return null;
        }
        JsonNode baseline = fingerprint.path("baseline");
        return baseline.isObject() && !baseline.isEmpty() ? baseline : null;
    }

    /** 指纹 baseline → 指标行（与「按本书正文提指纹」草稿共用 FingerprintMetricVO.parse，口径只有一份）。 */
    private List<com.zzdzz.novelgen.model.vo.FingerprintMetricVO> metrics(JsonNode baseline) {
        return com.zzdzz.novelgen.model.vo.FingerprintMetricVO.parse(baseline);
    }

    /** 指标项数：样本优先用分析快照记下的项数，其余按 baseline 实际键数。 */
    private Integer metricCount(JsonNode baseline, JsonNode analysisCount) {
        if (analysisCount != null && analysisCount.isNumber()) {
            return analysisCount.asInt();
        }
        return baseline == null ? null : baseline.size();
    }

    /** 章长预算带：读风格包 gate_config（预设/书籍共用）。缺配置返回 null。 */
    private double[] budgetBand(String gateConfigJson) {
        JsonNode cfg = parse(gateConfigJson);
        if (cfg == null || !cfg.path("budget_min").isNumber() || !cfg.path("budget_max").isNumber()) {
            return null;
        }
        double tolerance = cfg.path("chapter_length_tolerance").isNumber()
                ? cfg.path("chapter_length_tolerance").asDouble() : 0.15;
        return new double[]{cfg.path("budget_min").asDouble(), cfg.path("budget_max").asDouble(), tolerance};
    }

    /** 品类 → [语料章数, 语料总字数]（样本/预设行的「语料规模」读数来源）。 */
    private Map<String, long[]> genreScale() {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (PresetCorpusDataService.GenreSummary g : corpusData.genreSummaries()) {
            out.put(g.genre(), new long[]{g.chapters(), g.words()});
        }
        return out;
    }

    /** 书籍的源样本 id（derive_config.sourceSampleId）；无配置返回 null。 */
    private Long sourceSampleId(long novelId) {
        JsonNode cfg = parse(novelData.findDeriveConfig(novelId));
        if (cfg == null || !cfg.path("sourceSampleId").canConvertToLong()) {
            return null;
        }
        return cfg.path("sourceSampleId").asLong();
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取一个内嵌 JSON 字符串字段（analysis.fingerprintJson 存的是「JSON 文本的字符串」）。 */
    private JsonNode firstText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return parse(node.path(field).asText());
    }

    private static List<String> stringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode n : array) {
            if (n.isTextual() && !n.asText().isBlank()) {
                out.add(n.asText());
            }
        }
        return out;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asInt() : null;
    }

    private static LocalDate parseDate(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "格式应为 yyyy-MM-dd：" + text);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notAll(String s) {
        return notBlank(s) && !"ALL".equalsIgnoreCase(s.strip());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
