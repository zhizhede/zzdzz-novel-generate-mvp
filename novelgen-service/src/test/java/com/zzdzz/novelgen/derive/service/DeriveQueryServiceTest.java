package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
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
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.CalibrationDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.BookDetailVO;
import com.zzdzz.novelgen.model.vo.derive.BookVO;
import com.zzdzz.novelgen.model.vo.derive.QcReportVO;
import com.zzdzz.novelgen.model.vo.derive.RunChapterVO;
import com.zzdzz.novelgen.model.vo.derive.RunDetailVO;
import com.zzdzz.novelgen.model.vo.derive.RunListItemVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 随机衍生读模型：筛选/排序纯函数语义 + 进度聚合 + NOT_FOUND 守卫。 */
class DeriveQueryServiceTest {

    private final RunMapper runMapper = mock(RunMapper.class);
    private final NovelMapper novelMapper = mock(NovelMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final BeatInstanceMapper beatInstanceMapper = mock(BeatInstanceMapper.class);
    private final QcReportMapper qcReportMapper = mock(QcReportMapper.class);
    private final CalibrationMapper calibrationMapper = mock(CalibrationMapper.class);
    private final SourceChapterMapper sourceChapterMapper = mock(SourceChapterMapper.class);
    private final AssetOutlineMapper assetOutlineMapper = mock(AssetOutlineMapper.class);
    private final AssetBeatMapper assetBeatMapper = mock(AssetBeatMapper.class);
    private final AssetWorldMapper assetWorldMapper = mock(AssetWorldMapper.class);
    private final LabelIndexMapper labelIndexMapper = mock(LabelIndexMapper.class);

    private final DeriveQueryService svc = new DeriveQueryService(
            runMapper, novelMapper, chapterMapper, beatInstanceMapper,
            qcReportMapper, calibrationMapper, sourceChapterMapper,
            assetOutlineMapper, assetBeatMapper, assetWorldMapper, labelIndexMapper,
            new ObjectMapper());

    private static RunListItemVO runRow(long id, String status, Long novelId, String title,
                                        String intent, OffsetDateTime created) {
        return new RunListItemVO(id, status, 1, 42L, 1000, 2000, "[\"推理\"]", "SHORT_STANDALONE",
                intent, false, novelId, title, created, created);
    }

    private static BookVO bookRow(long id, String title, String sourceType, OffsetDateTime created) {
        return new BookVO(id, title, sourceType, "作者甲", "平台", 8, 10000, created);
    }

    private static RunDO runDo(Long novelId) {
        RunDO r = new RunDO();
        r.setId(7L);
        r.setStatus("DRAFTING");
        r.setQcRound(1);
        r.setSeed(42L);
        r.setParamWordsMin(10000);
        r.setParamWordsMax(20000);
        r.setParamTags("[\"推理\",\"悬疑\"]");
        r.setParamPace(new BigDecimal("0.50"));
        r.setNovelId(novelId);
        return r;
    }

    @Test
    void runMatches_keywordHitsTitleOrIntent() {
        RunListItemVO row = runRow(1, "QC", 10L, "雾岛囚影", "海边旅馆连环失踪", null);
        assertTrue(DeriveQueryService.matches(row, new RunQueryDTO("雾岛", null, null, null, null, null)));
        assertTrue(DeriveQueryService.matches(row, new RunQueryDTO("旅馆", null, null, null, null, null)));
        assertFalse(DeriveQueryService.matches(row, new RunQueryDTO("校园", null, null, null, null, null)));
    }

    @Test
    void runMatches_statusNovelIdAndDateRange() {
        OffsetDateTime created = LocalDate.of(2026, 10, 15).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        RunListItemVO row = runRow(1, "QC", 10L, "雾岛囚影", null, created);
        assertTrue(DeriveQueryService.matches(row, new RunQueryDTO(null, "qc", null, null, null, null)));
        assertTrue(DeriveQueryService.matches(row, new RunQueryDTO(null, "ALL", null, null, null, null)));
        assertFalse(DeriveQueryService.matches(row, new RunQueryDTO(null, "FAILED", null, null, null, null)));
        assertTrue(DeriveQueryService.matches(row, new RunQueryDTO(null, null, 10L, null, null, null)));
        assertFalse(DeriveQueryService.matches(row, new RunQueryDTO(null, null, 11L, null, null, null)));
        assertTrue(DeriveQueryService.matches(row, new RunQueryDTO(null, null, null, "2026-10-15", "2026-10-15", null)));
        assertFalse(DeriveQueryService.matches(row, new RunQueryDTO(null, null, null, "2026-10-16", null, null)));
        assertThrows(BizException.class,
                () -> DeriveQueryService.matches(row, new RunQueryDTO(null, null, null, "bad-date", null, null)));
    }

    @Test
    void runComparator_defaultsToIdDesc() {
        RunListItemVO a = runRow(1, "QC", null, null, null, null);
        RunListItemVO b = runRow(2, "QC", null, null, null, null);
        assertTrue(DeriveQueryService.runComparator(null).compare(a, b) > 0);
        assertTrue(DeriveQueryService.runComparator("TIME_ASC").compare(a, b) >= 0);
        assertTrue(DeriveQueryService.runComparator("ID_ASC").compare(a, b) < 0);
    }

    @Test
    void bookMatches_defaultsToSourceOnly() {
        BookVO source = bookRow(1, "源书A", "SOURCE", null);
        BookVO generated = bookRow(2, "成品B", "GENERATED", null);
        BookQueryDTO defaults = new BookQueryDTO(null, null, null, null, null);
        assertTrue(DeriveQueryService.matches(source, defaults));
        assertFalse(DeriveQueryService.matches(generated, defaults));
        BookQueryDTO all = new BookQueryDTO(null, "ALL", null, null, null);
        assertTrue(DeriveQueryService.matches(generated, all));
        BookQueryDTO onlyGenerated = new BookQueryDTO(null, "GENERATED", null, null, null);
        assertFalse(DeriveQueryService.matches(source, onlyGenerated));
        assertTrue(DeriveQueryService.matches(generated, onlyGenerated));
    }

    @Test
    void bookMatches_keywordAuthorAndDate() {
        OffsetDateTime created = LocalDate.of(2026, 10, 16).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        BookVO row = bookRow(1, "黑潮号", "SOURCE", created);
        assertTrue(DeriveQueryService.matches(row, new BookQueryDTO("黑潮", null, null, null, null)));
        assertTrue(DeriveQueryService.matches(row, new BookQueryDTO("作者甲", null, null, null, null)));
        assertFalse(DeriveQueryService.matches(row, new BookQueryDTO("白夜", null, null, null, null)));
        assertTrue(DeriveQueryService.matches(row, new BookQueryDTO(null, null, "2026-10-01", "2026-10-31", null)));
        assertFalse(DeriveQueryService.matches(row, new BookQueryDTO(null, null, null, "2026-10-15", null)));
    }

    @Test
    void bookComparator_defaultsToIdDescAndTitleAsc() {
        BookVO a = bookRow(1, "B书", "SOURCE", null);
        BookVO b = bookRow(2, "A书", "SOURCE", null);
        assertTrue(DeriveQueryService.bookComparator(null).compare(a, b) > 0);
        assertTrue(DeriveQueryService.bookComparator("TITLE_ASC").compare(a, b) > 0);
        assertTrue(DeriveQueryService.bookComparator("ID_ASC").compare(a, b) < 0);
    }

    @Test
    void getRun_notFound() {
        when(runMapper.selectById(99L)).thenReturn(null);
        assertThrows(BizException.class, () -> svc.getRun(99L));
    }

    @Test
    void getRun_aggregatesProgressAndQc() {
        RunDO run = runDo(10L);
        NovelDO novel = new NovelDO();
        novel.setId(10L);
        novel.setTitle("雾锁孤岛");
        ChapterDO c1 = new ChapterDO();
        c1.setChapterNo(1);
        c1.setContent("正文一");
        c1.setStatus("QC_PASS");
        ChapterDO c2 = new ChapterDO();
        c2.setChapterNo(2);
        c2.setContent("正文二");
        c2.setStatus("DRAFTED");
        ChapterDO c3 = new ChapterDO();
        c3.setChapterNo(3);
        c3.setStatus("PLANNED");
        BeatInstanceDO b1 = new BeatInstanceDO();
        b1.setStatus("VERIFIED");
        BeatInstanceDO b2 = new BeatInstanceDO();
        b2.setStatus("WRITTEN");
        BeatInstanceDO b3 = new BeatInstanceDO();
        b3.setStatus("PLANNED");
        QcReportDO r2 = new QcReportDO();
        r2.setRoundNo(2);
        r2.setVerdict("REWORK");
        QcReportDO r1 = new QcReportDO();
        r1.setRoundNo(1);
        r1.setVerdict("PASS");
        CalibrationDO cal = new CalibrationDO();
        cal.setWordsPred(18000);
        cal.setWordsActual(18277);

        when(runMapper.selectById(7L)).thenReturn(run);
        when(novelMapper.selectById(10L)).thenReturn(novel);
        when(chapterMapper.selectList(any())).thenReturn(List.of(c1, c2, c3));
        when(beatInstanceMapper.selectList(any())).thenReturn(List.of(b1, b2, b3));
        when(qcReportMapper.selectList(any())).thenReturn(List.of(r2, r1));
        when(calibrationMapper.selectList(any())).thenReturn(List.of(cal));

        RunDetailVO vo = svc.getRun(7L);
        assertEquals("DRAFTING", vo.status());
        assertEquals("雾锁孤岛", vo.novelTitle());
        assertEquals(3, vo.progress().chapterTotal());
        assertEquals(2, vo.progress().chapterWritten());
        assertEquals(1, vo.progress().chapterQcPass());
        assertEquals(3, vo.progress().beatTotal());
        assertEquals(1, vo.progress().beatWritten());
        assertEquals(1, vo.progress().beatVerified());
        assertEquals(2, vo.qc().reportCount());
        assertEquals("REWORK", vo.qc().latestVerdict());
        assertEquals(2, vo.qc().latestRound());
        assertEquals(18000, vo.qc().wordsPred());
        assertEquals(18277, vo.qc().wordsActual());
    }

    @Test
    void listRunChapters_emptyWhenNoNovelAndHasTextWithoutContent() {
        RunDO run = runDo(null);
        when(runMapper.selectById(7L)).thenReturn(run);
        assertTrue(svc.listRunChapters(7L, false, null).isEmpty());

        RunDO planned = runDo(10L);
        ChapterDO c = new ChapterDO();
        c.setChapterNo(1);
        c.setTitle("第1章");
        c.setContent("有正文");
        c.setStatus("DRAFTED");
        when(runMapper.selectById(8L)).thenReturn(planned);
        when(chapterMapper.selectList(any())).thenReturn(List.of(c));
        List<RunChapterVO> rows = svc.listRunChapters(8L, false, null);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).hasText());
        assertNull(rows.get(0).content());
        List<RunChapterVO> withText = svc.listRunChapters(8L, true, null);
        assertEquals("有正文", withText.get(0).content());
    }

    @Test
    void listRuns_fillsNovelTitleAndFiltersKeyword() {
        RunDO r1 = runDo(10L);
        r1.setId(1L);
        RunDO r2 = runDo(11L);
        r2.setId(2L);
        NovelDO n1 = new NovelDO();
        n1.setId(10L);
        n1.setTitle("雾岛囚影");
        NovelDO n2 = new NovelDO();
        n2.setId(11L);
        n2.setTitle("心动超速");
        when(runMapper.selectList(any())).thenReturn(List.of(r2, r1));
        when(novelMapper.selectBatchIds(any())).thenReturn(List.of(n1, n2));

        List<RunListItemVO> all = svc.listRuns(new RunQueryDTO(null, null, null, null, null, null));
        assertEquals(2, all.size());
        assertEquals("心动超速", all.get(0).novelTitle());
        assertEquals(2L, all.get(0).runId(), "默认 id 倒序");

        List<RunListItemVO> filtered = svc.listRuns(new RunQueryDTO("雾岛", null, null, null, null, null));
        assertEquals(1, filtered.size());
        assertEquals("雾岛囚影", filtered.get(0).novelTitle());
    }

    @Test
    void listBooks_defaultSourceOnlyAndIdDesc() {
        NovelDO source = new NovelDO();
        source.setId(1L);
        source.setTitle("源书A");
        source.setSourceType("SOURCE");
        NovelDO generated = new NovelDO();
        generated.setId(2L);
        generated.setTitle("成品B");
        generated.setSourceType("GENERATED");
        when(novelMapper.selectList(any())).thenReturn(List.of(generated, source));

        List<BookVO> defaults = svc.listBooks(null);
        assertEquals(1, defaults.size());
        assertEquals("源书A", defaults.get(0).title());

        List<BookVO> all = svc.listBooks(new BookQueryDTO(null, "ALL", null, null, null));
        assertEquals(2, all.size());
        assertEquals("成品B", all.get(0).title(), "默认 id 倒序，成品 id 更大在前");
    }

    @Test
    void getBook_countsAndStyleProfile() {
        NovelDO novel = new NovelDO();
        novel.setId(3L);
        novel.setTitle("字母组合");
        novel.setSourceType("SOURCE");
        novel.setStorySettings("{\"v\":1,\"style_profile\":{\"sentence\":\"短句\"}}");
        when(novelMapper.selectById(3L)).thenReturn(novel);
        when(sourceChapterMapper.selectCount(any())).thenReturn(12L);
        when(assetOutlineMapper.selectCount(any())).thenReturn(4L, 4L);
        when(assetBeatMapper.selectCount(any())).thenReturn(16L);
        when(assetWorldMapper.selectCount(any())).thenReturn(8L);
        when(labelIndexMapper.selectCount(any())).thenReturn(28L);

        BookDetailVO vo = svc.getBook(3L);
        assertEquals(12, vo.sourceChapterCount());
        assertEquals(4, vo.outlineCount());
        assertEquals(4, vo.outlineLabeledCount());
        assertEquals(16, vo.beatCount());
        assertEquals(8, vo.worldCount());
        assertEquals(28, vo.labelCount());
        assertTrue(vo.hasStyleProfile());

        novel.setStorySettings(null);
        assertFalse(svc.getBook(3L).hasStyleProfile());
    }

    @Test
    void listQcReports_verdictFilterAndJsonParse() {
        RunDO run = runDo(10L);
        when(runMapper.selectById(7L)).thenReturn(run);
        QcReportDO pass = new QcReportDO();
        pass.setId(1L);
        pass.setRoundNo(1);
        pass.setVerdict("PASS");
        pass.setIssues("[{\"code\":\"A\"}]");
        QcReportDO rework = new QcReportDO();
        rework.setId(2L);
        rework.setRoundNo(2);
        rework.setVerdict("REWORK");
        rework.setIssues("[{\"code\":\"B\"},{\"code\":\"C\"}]");
        when(qcReportMapper.selectList(any())).thenReturn(List.of(rework, pass));

        List<QcReportVO> all = svc.listQcReports(7L, null);
        assertEquals(2, all.size());
        assertEquals("REWORK", all.get(0).verdict());
        assertEquals(2, all.get(0).issues().size());
        List<QcReportVO> onlyPass = svc.listQcReports(7L, "PASS");
        assertEquals(1, onlyPass.size());
        assertEquals(1, onlyPass.get(0).roundNo());
    }
}
