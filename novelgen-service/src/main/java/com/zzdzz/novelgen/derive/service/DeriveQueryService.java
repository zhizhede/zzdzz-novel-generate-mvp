package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CalibrationMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.QcReportMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.SourceChapterMapper;
import com.zzdzz.novelgen.model.dto.derive.BookQueryDTO;
import com.zzdzz.novelgen.model.dto.derive.RunQueryDTO;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.AssetWorldDO;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.CalibrationDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.SourceChapterDO;
import com.zzdzz.novelgen.model.vo.derive.BeatVO;
import com.zzdzz.novelgen.model.vo.derive.BookDetailVO;
import com.zzdzz.novelgen.model.vo.derive.BookVO;
import com.zzdzz.novelgen.model.vo.derive.QcReportVO;
import com.zzdzz.novelgen.model.vo.derive.RunChapterVO;
import com.zzdzz.novelgen.model.vo.derive.RunDetailVO;
import com.zzdzz.novelgen.model.vo.derive.RunListItemVO;
import com.zzdzz.novelgen.model.vo.derive.SourceChapterVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 随机衍生读模型：run 进度/结果、质检历史、拍进度、书详情与带筛选列表的查询编排。
 *
 * <p>列表按「读全量 → 内存筛 → 排序」执行（书/run 为百千级以内，读侧内存筛，不额外落 SQL，
 * 口径同 zzdzz {@code NovelService.list}）；筛选/排序语义收敛在 {@link #matches} 与
 * {@link #comparator} 系列纯函数里，由单测锁定。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveQueryService {

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final QcReportMapper qcReportMapper;
    private final CalibrationMapper calibrationMapper;
    private final SourceChapterMapper sourceChapterMapper;
    private final AssetOutlineMapper assetOutlineMapper;
    private final AssetBeatMapper assetBeatMapper;
    private final AssetWorldMapper assetWorldMapper;
    private final LabelIndexMapper labelIndexMapper;
    private final ObjectMapper objectMapper;

    /** run 列表（带筛选；novelTitle 回填成品书名供 keyword 命中与展示）。 */
    public List<RunListItemVO> listRuns(RunQueryDTO q) {
        List<RunDO> runs = runMapper.selectList(
                new LambdaQueryWrapper<RunDO>().orderByDesc(RunDO::getId));
        Map<Long, String> titles = loadNovelTitles(runs);
        return runs.stream()
                .map(r -> toRunItem(r, titles.get(r.getNovelId())))
                .filter(row -> matches(row, q))
                .sorted(runComparator(q == null ? null : q.sort()))
                .toList();
    }

    /** run 详情：状态机 + ⑤参数 + 章/拍进度聚合 + 最新质检判定 + 标定读数。 */
    public RunDetailVO getRun(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        NovelDO novel = run.getNovelId() == null ? null : novelMapper.selectById(run.getNovelId());
        List<ChapterDO> chapters = run.getNovelId() == null ? List.of()
                : chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, run.getNovelId()));
        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>().eq(BeatInstanceDO::getRunId, runId));
        List<QcReportDO> reports = qcReportMapper.selectList(
                new LambdaQueryWrapper<QcReportDO>()
                        .eq(QcReportDO::getRunId, runId)
                        .orderByDesc(QcReportDO::getRoundNo));
        List<CalibrationDO> cals = calibrationMapper.selectList(
                new LambdaQueryWrapper<CalibrationDO>()
                        .eq(CalibrationDO::getRunId, runId)
                        .orderByDesc(CalibrationDO::getId));
        CalibrationDO cal = cals.isEmpty() ? null : cals.get(0);
        QcReportDO latest = reports.isEmpty() ? null : reports.get(0);

        RunDetailVO.Progress progress = new RunDetailVO.Progress(
                chapters.size(),
                count(chapters, c -> notBlank(c.getContent())),
                count(chapters, c -> "QC_PASS".equalsIgnoreCase(c.getStatus())),
                beats.size(),
                count(beats, b -> "WRITTEN".equalsIgnoreCase(b.getStatus())),
                count(beats, b -> "VERIFIED".equalsIgnoreCase(b.getStatus())),
                count(beats, b -> "SKIPPED".equalsIgnoreCase(b.getStatus())));
        RunDetailVO.QcSummary qc = new RunDetailVO.QcSummary(
                reports.size(),
                latest == null ? null : latest.getVerdict(),
                latest == null ? null : latest.getRoundNo(),
                cal == null ? null : cal.getWordsPred(),
                cal == null ? null : cal.getWordsActual());

        return new RunDetailVO(
                run.getId(), run.getStatus(), run.getQcRound(), run.getSeed(),
                run.getParamWordsMin(), run.getParamWordsMax(), run.getParamTags(),
                run.getParamPace(), run.getParamForm(), run.getParamOutcome(),
                run.getParamIntent(), run.getParamStyle(),
                run.getAntiRepro(), run.getLenCheck(),
                run.getB0(), run.getPMin(), run.getPMax(), run.getStepStatus(),
                run.getNovelId(), novel == null ? null : novel.getTitle(),
                progress, qc, run.getCreatedTime(), run.getUpdatedTime());
    }

    /**
     * 成品章进度列表（按章号升序）。
     * run 未挂成品书（⑥a 未跑）返回空列表而非报错；content 仅 includeText=true 时填充。
     */
    public List<RunChapterVO> listRunChapters(long runId, boolean includeText, String status) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (run.getNovelId() == null) {
            return List.of();
        }
        LambdaQueryWrapper<ChapterDO> wrapper = new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, run.getNovelId())
                .orderByAsc(ChapterDO::getChapterNo);
        if (notBlank(status)) {
            wrapper.eq(ChapterDO::getStatus, status.strip());
        }
        return chapterMapper.selectList(wrapper).stream()
                .map(c -> new RunChapterVO(
                        c.getChapterNo() == null ? 0 : c.getChapterNo(),
                        c.getTitle(), c.getBudget(), c.getCharCount(), c.getStatus(),
                        notBlank(c.getContent()), c.getComment(),
                        includeText ? c.getContent() : null))
                .toList();
    }

    /** 质检历史（轮次降序=最新在前；verdict 可筛 PASS/REWORK/FAIL）。 */
    public List<QcReportVO> listQcReports(long runId, String verdict) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        return qcReportMapper.selectList(new LambdaQueryWrapper<QcReportDO>()
                        .eq(QcReportDO::getRunId, runId)
                        .orderByDesc(QcReportDO::getRoundNo)).stream()
                .filter(r -> !notBlank(verdict)
                        || verdict.strip().equalsIgnoreCase(r.getVerdict()))
                .map(r -> new QcReportVO(
                        r.getId(), r.getRoundNo() == null ? 0 : r.getRoundNo(), r.getVerdict(),
                        parseJson(r.getScriptResult()), parseJson(r.getLlmResult()),
                        parseJson(r.getIssues()), parseJson(r.getRepairLog()),
                        r.getCreatedTime()))
                .toList();
    }

    /** 拍实例进度（seq 升序；status/chapterNo 可筛）。 */
    public List<BeatVO> listBeats(long runId, String status, Integer chapterNo) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        LambdaQueryWrapper<BeatInstanceDO> wrapper = new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByAsc(BeatInstanceDO::getSeq);
        if (notBlank(status)) {
            wrapper.eq(BeatInstanceDO::getStatus, status.strip());
        }
        if (chapterNo != null) {
            wrapper.eq(BeatInstanceDO::getChapterNo, chapterNo);
        }
        return beatInstanceMapper.selectList(wrapper).stream()
                .map(b -> new BeatVO(
                        b.getSeq() == null ? 0 : b.getSeq(), b.getBeatType(), b.getPacing(),
                        b.getSource(), b.getChapterNo(), b.getStatus(), b.getFilledText()))
                .toList();
    }

    /** 书列表（带筛选；缺省 sourceType=SOURCE 且 id 倒序，保持旧 /books 行为）。 */
    public List<BookVO> listBooks(BookQueryDTO q) {
        BookQueryDTO condition = q == null ? new BookQueryDTO(null, null, null, null, null) : q;
        return novelMapper.selectList(new LambdaQueryWrapper<NovelDO>().orderByDesc(NovelDO::getId)).stream()
                .map(DeriveQueryService::toBookItem)
                .filter(row -> matches(row, condition))
                .sorted(bookComparator(condition.sort()))
                .toList();
    }

    /** 书详情：元数据 + 素材处理进度读数（源章/弧/拍/世界/标签计数、文风卡有无）。 */
    public BookDetailVO getBook(long novelId) {
        NovelDO novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "书不存在: " + novelId);
        }
        int sourceChapterCount = toInt(sourceChapterMapper.selectCount(
                new LambdaQueryWrapper<SourceChapterDO>().eq(SourceChapterDO::getNovelId, novelId)));
        int outlineCount = toInt(assetOutlineMapper.selectCount(
                new LambdaQueryWrapper<AssetOutlineDO>().eq(AssetOutlineDO::getSrcNovelId, novelId)));
        int outlineLabeledCount = toInt(assetOutlineMapper.selectCount(
                new LambdaQueryWrapper<AssetOutlineDO>()
                        .eq(AssetOutlineDO::getSrcNovelId, novelId)
                        .isNotNull(AssetOutlineDO::getLabelText)));
        int beatCount = toInt(assetBeatMapper.selectCount(
                new LambdaQueryWrapper<AssetBeatDO>().eq(AssetBeatDO::getSrcNovelId, novelId)));
        int worldCount = toInt(assetWorldMapper.selectCount(
                new LambdaQueryWrapper<AssetWorldDO>().eq(AssetWorldDO::getSrcNovelId, novelId)));
        int labelCount = toInt(labelIndexMapper.selectCount(
                new LambdaQueryWrapper<LabelIndexDO>().eq(LabelIndexDO::getSrcNovelId, novelId)));
        return new BookDetailVO(
                novel.getId(), novel.getTitle(), novel.getSourceType(), novel.getAuthor(),
                novel.getPlatform(), novel.getSummary(), novel.getCategoryTags(),
                novel.getChapterCount(), novel.getCharCount(), novel.getRunId(),
                sourceChapterCount, outlineCount, outlineLabeledCount,
                beatCount, worldCount, labelCount, hasStyleProfile(novel),
                novel.getCreatedTime(), novel.getUpdatedTime());
    }

    /** 源章列表（seq 升序；content 仅 includeText=true 时填充）。书不存在报 NOT_FOUND。 */
    public List<SourceChapterVO> listSourceChapters(long novelId, boolean includeText) {
        NovelDO novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "书不存在: " + novelId);
        }
        return sourceChapterMapper.selectList(new LambdaQueryWrapper<SourceChapterDO>()
                        .eq(SourceChapterDO::getNovelId, novelId)
                        .orderByAsc(SourceChapterDO::getSeq)).stream()
                .map(c -> new SourceChapterVO(
                        c.getSeq() == null ? 0 : c.getSeq(), c.getTitle(), c.getCharCount(),
                        includeText ? c.getContent() : null))
                .toList();
    }

    /** run 单行条件判定（纯函数：无库依赖）。keyword 命中成品书名与创作思路；未填条件不参与筛选。 */
    static boolean matches(RunListItemVO row, RunQueryDTO q) {
        if (q == null) {
            return true;
        }
        if (notBlank(q.keyword())) {
            String kw = q.keyword().strip().toLowerCase(Locale.ROOT);
            String haystack = ((row.novelTitle() == null ? "" : row.novelTitle()) + "\n"
                    + (row.intent() == null ? "" : row.intent())).toLowerCase(Locale.ROOT);
            if (!haystack.contains(kw)) {
                return false;
            }
        }
        if (notAll(q.status()) && !q.status().strip().equalsIgnoreCase(row.status())) {
            return false;
        }
        if (q.novelId() != null && !q.novelId().equals(row.novelId())) {
            return false;
        }
        return inDateRange(q.from(), q.to(), row.createTime());
    }

    /** run 排序口径（纯函数）：未指定/未知 sort 保持默认 id 倒序（新 run 在前）。 */
    static Comparator<RunListItemVO> runComparator(String sort) {
        Comparator<OffsetDateTime> timeAsc = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<OffsetDateTime> timeDesc = Comparator.nullsLast(Comparator.reverseOrder());
        String key = sort == null ? "" : sort.strip().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ID_ASC" -> Comparator.comparingLong(RunListItemVO::runId);
            case "TIME_ASC" -> Comparator.comparing(RunListItemVO::createTime, timeAsc);
            case "TIME_DESC" -> Comparator.comparing(RunListItemVO::createTime, timeDesc);
            default -> Comparator.comparing(RunListItemVO::runId, Comparator.reverseOrder());
        };
    }

    /**
     * 书单行条件判定（纯函数）。sourceType 缺省按 SOURCE（旧 /books 行为），
     * ALL 放行全部；keyword 命中书名与作者；未填条件不参与筛选。
     */
    static boolean matches(BookVO row, BookQueryDTO q) {
        if (q == null) {
            return "SOURCE".equalsIgnoreCase(row.sourceType());
        }
        if (notBlank(q.keyword())) {
            String kw = q.keyword().strip().toLowerCase(Locale.ROOT);
            String haystack = ((row.title() == null ? "" : row.title()) + "\n"
                    + (row.author() == null ? "" : row.author())).toLowerCase(Locale.ROOT);
            if (!haystack.contains(kw)) {
                return false;
            }
        }
        String sourceType = q.sourceType() == null || q.sourceType().isBlank()
                ? "SOURCE" : q.sourceType().strip();
        if (!"ALL".equalsIgnoreCase(sourceType) && !sourceType.equalsIgnoreCase(row.sourceType())) {
            return false;
        }
        return inDateRange(q.from(), q.to(), row.createTime());
    }

    /** 书排序口径（纯函数）：未指定/未知 sort 保持默认 id 倒序（旧行为）。 */
    static Comparator<BookVO> bookComparator(String sort) {
        Comparator<OffsetDateTime> timeAsc = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<OffsetDateTime> timeDesc = Comparator.nullsLast(Comparator.reverseOrder());
        String key = sort == null ? "" : sort.strip().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ID_ASC" -> Comparator.comparingLong(BookVO::id);
            case "TIME_ASC" -> Comparator.comparing(BookVO::createTime, timeAsc);
            case "TIME_DESC" -> Comparator.comparing(BookVO::createTime, timeDesc);
            case "TITLE_ASC" -> Comparator.comparing(r -> r.title() == null ? "" : r.title());
            default -> Comparator.comparing(BookVO::id, Comparator.reverseOrder());
        };
    }

    private Map<Long, String> loadNovelTitles(List<RunDO> runs) {
        Set<Long> ids = runs.stream()
                .map(RunDO::getNovelId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return novelMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(NovelDO::getId, NovelDO::getTitle, (a, b) -> a));
    }

    private RunListItemVO toRunItem(RunDO r, String novelTitle) {
        return new RunListItemVO(
                r.getId(), r.getStatus(), r.getQcRound(), r.getSeed(),
                r.getParamWordsMin(), r.getParamWordsMax(), r.getParamTags(),
                r.getParamForm(), r.getParamIntent(), r.getAntiRepro(),
                r.getNovelId(), novelTitle, r.getCreatedTime(), r.getUpdatedTime());
    }

    private static BookVO toBookItem(NovelDO n) {
        return new BookVO(n.getId(), n.getTitle(), n.getSourceType(), n.getAuthor(),
                n.getPlatform(), n.getChapterCount(), n.getCharCount(), n.getCreatedTime());
    }

    private boolean hasStyleProfile(NovelDO novel) {
        String settings = novel.getStorySettings();
        if (settings == null || settings.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(settings);
            return root.path("style_profile").isObject();
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean inDateRange(String from, String to, OffsetDateTime created) {
        LocalDate fromDate = parseDate(from, "起始日期");
        LocalDate toDate = parseDate(to, "结束日期");
        if (fromDate == null && toDate == null) {
            return true;
        }
        LocalDate createdDate = created == null ? null
                : created.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        if (fromDate != null && (createdDate == null || createdDate.isBefore(fromDate))) {
            return false;
        }
        if (toDate != null && (createdDate == null || createdDate.isAfter(toDate))) {
            return false;
        }
        return true;
    }

    private static LocalDate parseDate(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "格式应为 yyyy-MM-dd");
        }
    }

    private static <T> int count(List<T> rows, Predicate<T> test) {
        return (int) rows.stream().filter(test).count();
    }

    private static int toInt(Long value) {
        return value == null ? 0 : value.intValue();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notAll(String s) {
        return notBlank(s) && !"ALL".equalsIgnoreCase(s.strip());
    }
}
