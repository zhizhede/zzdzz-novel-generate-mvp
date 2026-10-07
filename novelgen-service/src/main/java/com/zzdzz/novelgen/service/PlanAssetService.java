package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.model.enums.NovelSourceType;
import com.zzdzz.novelgen.model.dto.PlanAssetQueryDTO;
import com.zzdzz.novelgen.model.vo.PlanAssetVO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.VolumeReviewDataService;
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
 * 规划资产库（只读聚合查询）：把「大纲 / 卷纲 / 章纲」三层的落库结果收成一个可筛选列表。
 * 三层来源：大纲＝canon_docs(kind=misc, name=大纲) 一本书一行；卷纲＝chapters 按 (书, 卷号) 聚合 + volume_reviews；
 * 章纲＝chapters 一行一章（章纲 YAML 落在 chapter.outline_yaml）。
 * 这里只做读模型拼装与筛选排序，不写库、不改任何规划与生成口径；大纲缺失的书**也出行**（标 hasOutline=false），
 * 这样这一页同时是「哪些书还没大纲 / 哪些章还没章纲」的缺口清单。
 */
@Service
@RequiredArgsConstructor
public class PlanAssetService {

    /** 与 PlanningService 同一口径：大纲落在 canon_docs 的 misc/大纲。 */
    private static final String STORY_KIND = "misc";
    private static final String STORY_NAME = "大纲";
    /** 与 NovelService.SKELETON_OUTLINE_MARKER 同值：克隆样本资产时预填的大纲骨架标记（激活时会被拦）。 */
    private static final String SKELETON_OUTLINE_MARKER = "> 由样本《";

    static final String LEVEL_OUTLINE = "OUTLINE";
    static final String LEVEL_VOLUME = "VOLUME";
    static final String LEVEL_CHAPTER = "CHAPTER";

    private final NovelDataService novelData;
    private final ChapterDataService chapterData;
    private final CanonDocDataService canonData;
    private final VolumeReviewDataService reviewData;
    /** 静态判定函数（matches/comparator）也要解析 JSON，故用类级单例而不是注入实例字段。 */
    private static final ObjectMapper PARSER = new ObjectMapper();

    /** 规划资产列表：按 level 取一层读模型 → 条件筛选 → 排序。 */
    public List<PlanAssetVO> query(PlanAssetQueryDTO condition) {
        String level = level(condition == null ? null : condition.level());
        Map<Long, NovelDO> novels = new LinkedHashMap<>();
        for (NovelDO n : novelData.listAlive()) {
            novels.put(n.getId(), n);
        }
        List<ChapterDataService.ChapterPlanRow> rows = chapterData.listPlanRows();
        Map<String, VolumeReviewDataService.VolumeReviewRow> reviews = new LinkedHashMap<>();
        for (VolumeReviewDataService.VolumeReviewRow r : reviewData.listAll()) {
            reviews.put(r.novelId() + ":" + r.volNo(), r);
        }
        List<PlanAssetVO> out = switch (level) {
            case LEVEL_OUTLINE -> outlineRows(novels);
            case LEVEL_VOLUME -> volumeRows(novels, rows, reviews);
            default -> chapterRows(novels, rows);
        };
        return out.stream()
                .filter(row -> matches(row, condition))
                .sorted(comparator(condition == null ? null : condition.sort()))
                .toList();
    }

    // ===== 三层读模型 =====

    /** 大纲层：活书各一行；没有大纲文档的书也出行（hasOutline=false），缺口一眼可见。 */
    private List<PlanAssetVO> outlineRows(Map<Long, NovelDO> novels) {
        Map<Long, CanonDocDO> docs = new LinkedHashMap<>();
        for (CanonDocDO doc : canonData.listAliveByKindName(STORY_KIND, STORY_NAME)) {
            docs.put(doc.getNovelId(), doc);
        }
        List<PlanAssetVO> rows = new ArrayList<>();
        for (NovelDO novel : novels.values()) {
            CanonDocDO doc = docs.get(novel.getId());
            String content = doc == null ? null : doc.getContent();
            boolean has = content != null && !content.isBlank();
            rows.add(new PlanAssetVO(LEVEL_OUTLINE, novel.getId(), novel.getTitle(),
                    NovelSourceType.normalize(novel.getSourceType()), null, null, null, null, null, null, null,
                    null, null, null, null, null,
                    has, has ? (long) content.length() : null, content,
                    has && content.strip().startsWith(SKELETON_OUTLINE_MARKER),
                    null, null, null, null, null, null,
                    null, null, null, null, null,
                    doc == null ? novel.getCreateTime() : doc.getCreateTime(),
                    doc == null ? null : doc.getUpdateTime()));
        }
        return rows;
    }

    /** 卷纲层：按 (书, 卷号) 聚合章行；volume_no 为空的导入章归一组（arc=未分卷）。 */
    private List<PlanAssetVO> volumeRows(Map<Long, NovelDO> novels,
                                         List<ChapterDataService.ChapterPlanRow> rows,
                                         Map<String, VolumeReviewDataService.VolumeReviewRow> reviews) {
        Map<String, List<ChapterDataService.ChapterPlanRow>> groups = new LinkedHashMap<>();
        for (ChapterDataService.ChapterPlanRow r : rows) {
            groups.computeIfAbsent(r.novelId() + ":" + r.volumeNo(), k -> new ArrayList<>()).add(r);
        }
        List<PlanAssetVO> out = new ArrayList<>();
        for (Map.Entry<String, List<ChapterDataService.ChapterPlanRow>> e : groups.entrySet()) {
            List<ChapterDataService.ChapterPlanRow> group = e.getValue();
            ChapterDataService.ChapterPlanRow first = group.get(0);
            NovelDO novel = novels.get(first.novelId());
            if (novel == null) {
                continue;   // 已删书的残留行（历史兜底）不进列表
            }
            Integer volNo = first.volumeNo();
            String arc = group.stream().map(ChapterDataService.ChapterPlanRow::arc)
                    .filter(a -> a != null && !a.isBlank()).findFirst().orElse(null);
            int fromNo = group.stream().mapToInt(ChapterDataService.ChapterPlanRow::chapterNo).min().orElse(0);
            int toNo = group.stream().mapToInt(ChapterDataService.ChapterPlanRow::chapterNo).max().orElse(0);
            int withOutline = (int) group.stream().filter(r -> r.outlineChars() > 0).count();
            int withText = (int) group.stream().filter(r -> r.textChars() > 0).count();
            long textChars = group.stream().mapToLong(ChapterDataService.ChapterPlanRow::textChars).sum();
            Integer budgetMin = group.stream().map(ChapterDataService.ChapterPlanRow::budgetMin)
                    .filter(b -> b != null && b > 0).min(Integer::compareTo).orElse(null);
            Integer budgetMax = group.stream().map(ChapterDataService.ChapterPlanRow::budgetMax)
                    .filter(b -> b != null && b > 0).max(Integer::compareTo).orElse(null);
            OffsetDateTime created = group.stream().map(ChapterDataService.ChapterPlanRow::createTime)
                    .filter(t -> t != null).max(Comparator.naturalOrder()).orElse(null);
            OffsetDateTime updated = group.stream().map(ChapterDataService.ChapterPlanRow::updateTime)
                    .filter(t -> t != null).max(Comparator.naturalOrder()).orElse(null);
            VolumeReviewDataService.VolumeReviewRow review = volNo == null ? null
                    : reviews.get(first.novelId() + ":" + volNo);
            JsonNode report = review == null ? null : parse(review.reportJson());
            JsonNode reviewNode = report == null ? null : report.path("review");
            JsonNode drifts = reviewNode == null ? null : reviewNode.path("drifts");
            int driftCount = drifts != null && drifts.isArray() ? drifts.size() : 0;
            int majorCount = 0;
            if (drifts != null && drifts.isArray()) {
                for (JsonNode d : drifts) {
                    if ("major".equalsIgnoreCase(d.path("severity").asText(""))) {
                        majorCount++;
                    }
                }
            }
            out.add(new PlanAssetVO(LEVEL_VOLUME, first.novelId(), novel.getTitle(),
                    NovelSourceType.normalize(novel.getSourceType()), volNo, arc,
                    null, null, null, null, null,
                    group.size(), fromNo, toNo, withOutline, withText,
                    withOutline > 0, null, null, null,
                    budgetMin, budgetMax, null, withText > 0, textChars, null,
                    review != null, reviewNode == null ? null : text(reviewNode, "summary"),
                    driftCount, majorCount, review == null ? null : review.reportJson(),
                    created, updated));
        }
        return out;
    }

    /** 章纲层：一章一行（章纲 YAML 原样带出，正文只带字数）。 */
    private List<PlanAssetVO> chapterRows(Map<Long, NovelDO> novels,
                                          List<ChapterDataService.ChapterPlanRow> rows) {
        List<PlanAssetVO> out = new ArrayList<>();
        for (ChapterDataService.ChapterPlanRow r : rows) {
            NovelDO novel = novels.get(r.novelId());
            if (novel == null) {
                continue;   // 已删书的残留行（历史兜底）不进列表
            }
            out.add(new PlanAssetVO(LEVEL_CHAPTER, r.novelId(), novel.getTitle(),
                    NovelSourceType.normalize(novel.getSourceType()), r.volumeNo(), r.arc(),
                    r.chapterNo(), r.title(), r.goal(), r.hook(), r.timeNote(),
                    null, null, null, null, null,
                    r.outlineChars() > 0, r.outlineChars(), r.outlineYaml(), null,
                    r.budgetMin() > 0 ? r.budgetMin() : null, r.budgetMax() > 0 ? r.budgetMax() : null,
                    r.status(), r.textChars() > 0, r.textChars(), arraySize(r.foreshadowRefs()),
                    null, null, null, null, null,
                    r.createTime(), r.updateTime()));
        }
        return out;
    }

    // ===== 筛选与排序（纯函数，单测锁定语义） =====

    /** 单行条件判定：与层级无关的条件在不适用的层上不生效（如大纲层不判章状态/正文）。 */
    static boolean matches(PlanAssetVO row, PlanAssetQueryDTO q) {
        if (q == null) {
            return true;
        }
        if (notAll(q.level()) && !q.level().strip().equalsIgnoreCase(row.level())) {
            return false;
        }
        if (q.novelId() != null && row.novelId() != q.novelId()) {
            return false;
        }
        if (notAll(q.sourceType()) && !q.sourceType().strip().equalsIgnoreCase(row.sourceType())) {
            return false;
        }
        if (notBlank(q.keyword())) {
            String kw = q.keyword().strip().toLowerCase(Locale.ROOT);
            String haystack = String.join("\n", nullToEmpty(row.novelTitle()), nullToEmpty(row.arc()),
                    nullToEmpty(row.chapterTitle()), nullToEmpty(row.goal()), nullToEmpty(row.hook()),
                    nullToEmpty(row.timeNote()), nullToEmpty(row.outline())).toLowerCase(Locale.ROOT);
            if (!haystack.contains(kw)) {
                return false;
            }
        }
        if (q.volumeNo() != null && !LEVEL_OUTLINE.equals(row.level())) {
            // 0 = 只看未分卷（volume_no 为空的导入章）
            if (q.volumeNo() == 0 ? row.volumeNo() != null : !q.volumeNo().equals(row.volumeNo())) {
                return false;
            }
        }
        if (q.fromChapter() != null || q.toChapter() != null) {
            if (LEVEL_CHAPTER.equals(row.level())) {
                if (q.fromChapter() != null && row.chapterNo() < q.fromChapter()) {
                    return false;
                }
                if (q.toChapter() != null && row.chapterNo() > q.toChapter()) {
                    return false;
                }
            } else if (LEVEL_VOLUME.equals(row.level())) {
                // 卷：章号区间与查询区间有交集即命中
                if (q.toChapter() != null && row.fromChapter() > q.toChapter()) {
                    return false;
                }
                if (q.fromChapter() != null && row.toChapter() < q.fromChapter()) {
                    return false;
                }
            } else {
                return false;   // 大纲层没有章号概念
            }
        }
        // 章状态只对章纲层成立（卷是若干章的集合，单值状态对它无意义）
        if (notBlank(q.status()) && LEVEL_CHAPTER.equals(row.level())
                && !q.status().strip().equalsIgnoreCase(nullToEmpty(row.status()))) {
            return false;
        }
        if (!triState(q.hasOutline(), row.hasOutline())) {
            return false;
        }
        // 正文：大纲层没有正文概念，该条件不生效
        if (!LEVEL_OUTLINE.equals(row.level()) && !triState(q.hasText(), row.hasText())) {
            return false;
        }
        // 骨架标记只对大纲层有意义（卷/章层没有骨架概念，该条件不生效）
        if (LEVEL_OUTLINE.equals(row.level()) && !triState(q.skeleton(), row.skeleton())) {
            return false;
        }
        Long chars = LEVEL_OUTLINE.equals(row.level()) ? row.outlineChars() : row.textChars();
        if (q.minChars() != null && (chars == null || chars < q.minChars())) {
            return false;
        }
        if (q.maxChars() != null && (chars == null || chars > q.maxChars())) {
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

    /** 排序口径（纯函数）：默认「书 + 卷号 + 章号」＝通读顺序；降序一律 nullsLast(逆序)。 */
    static Comparator<PlanAssetVO> comparator(String sort) {
        Comparator<OffsetDateTime> timeAsc = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<OffsetDateTime> timeDesc = Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<Long> charsDesc = Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<PlanAssetVO> order = Comparator.comparingLong(PlanAssetVO::novelId)
                .thenComparing(PlanAssetVO::volumeNo, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(row -> row.chapterNo() == null ? 0 : row.chapterNo());
        Comparator<PlanAssetVO> title = Comparator
                .comparing((PlanAssetVO row) -> nullToEmpty(row.novelTitle()))
                .thenComparing(row -> row.chapterNo() == null ? 0 : row.chapterNo());
        String key = sort == null ? "" : sort.strip().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ORDER_DESC" -> order.reversed();
            case "TIME_DESC" -> Comparator.comparing(PlanAssetVO::createTime, timeDesc);
            case "TIME_ASC" -> Comparator.comparing(PlanAssetVO::createTime, timeAsc);
            case "TEXT_DESC" -> Comparator.comparing(
                    row -> LEVEL_OUTLINE.equals(row.level()) ? row.outlineChars() : row.textChars(), charsDesc);
            case "OUTLINE_DESC" -> Comparator.comparing(PlanAssetVO::outlineChars, charsDesc);
            case "TITLE_ASC" -> title;
            default -> order;
        };
    }

    // ===== 小工具 =====

    /** 层级规范化：未知值落 CHAPTER（默认看章纲）。 */
    static String level(String raw) {
        if (raw != null && LEVEL_OUTLINE.equalsIgnoreCase(raw.strip())) {
            return LEVEL_OUTLINE;
        }
        if (raw != null && LEVEL_VOLUME.equalsIgnoreCase(raw.strip())) {
            return LEVEL_VOLUME;
        }
        return LEVEL_CHAPTER;
    }

    /** 三态条件（YES/NO/ALL）：行值为 null 时只有 NO 命中（「还没有」正是缺口清单要的）。 */
    private static boolean triState(String condition, Boolean actual) {
        if (!notAll(condition)) {
            return true;
        }
        boolean want = "YES".equalsIgnoreCase(condition.strip());
        return want == Boolean.TRUE.equals(actual);
    }

    private static Integer arraySize(String json) {
        JsonNode node = json == null ? null : parse(json);
        return node != null && node.isArray() ? node.size() : null;
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return PARSER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "格式应为 yyyy-MM-dd：" + value);
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
