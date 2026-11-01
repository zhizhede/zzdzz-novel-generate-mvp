package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CalibrationMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.QcReportMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.derive.mapper.WorldStateMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 直通口径（2026-10-19）：qc(runId, true) 只跳 AI 快检五项——脚本硬判照跑、
 * qc_report 落档、PASS 升章 QC_PASS，脚本不过走 REWORK 熔断。治 run29 直通
 * 整个⑧被跳过（无 qc_report、章全 DRAFTED 却 PASSED）的无档直过。
 */
class DeriveQcDirectSkipLlmTest {

    private final RunMapper runMapper = mock(RunMapper.class);
    private final NovelMapper novelMapper = mock(NovelMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final BeatInstanceMapper beatInstanceMapper = mock(BeatInstanceMapper.class);
    private final WordlistMapper wordlistMapper = mock(WordlistMapper.class);
    private final QcReportMapper qcReportMapper = mock(QcReportMapper.class);
    private final LlmJson llmJson = mock(LlmJson.class);
    private final DeriveQcService svc = new DeriveQcService(
            runMapper, novelMapper, chapterMapper, beatInstanceMapper, wordlistMapper,
            qcReportMapper, mock(CalibrationMapper.class), mock(WorkFingerprintMapper.class),
            mock(AssetOutlineMapper.class), mock(LabelIndexMapper.class),
            mock(WorldStateMapper.class), llmJson);

    private static RunDO run(int qcRound) {
        RunDO r = new RunDO();
        r.setId(7L);
        r.setStatus("QC");
        r.setNovelId(5L);
        r.setQcRound(qcRound);
        r.setParamWordsMin(1);
        r.setParamWordsMax(100000);
        r.setParamPace(new BigDecimal("0.50"));
        r.setLenCheck(false);
        r.setAntiRepro(false);
        return r;
    }

    private static NovelDO novel(String assets) {
        NovelDO n = new NovelDO();
        n.setId(5L);
        n.setTitle("测试书");
        n.setStoryAssets(assets);
        return n;
    }

    private static ChapterDO ch(int no, String title, String content) {
        ChapterDO c = new ChapterDO();
        c.setId((long) no);
        c.setChapterNo(no);
        c.setTitle(title);
        c.setContent(content);
        c.setCharCount(content.length());
        c.setBudget(0);
        return c;
    }

    private void stub(int qcRound, NovelDO novel, List<ChapterDO> chapters) {
        when(runMapper.selectById(7L)).thenReturn(run(qcRound));
        when(novelMapper.selectById(5L)).thenReturn(novel);
        when(chapterMapper.selectList(any())).thenReturn(chapters);
        when(beatInstanceMapper.selectList(any())).thenReturn(List.of());
        when(wordlistMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void skipLlm_pass_writesReport_skipsLlm_promotesChapters() {
        stub(0, novel("{\"protagonist\":\"艾琳\"}"), List.of(
                ch(1, "起雾的码头", "艾琳在码头等船。"),
                ch(2, "铜戒", "艾琳掏出铜戒看了看。")));

        var vo = svc.qc(7L, true);

        assertEquals("PASS", vo.verdict());
        assertTrue(vo.issues().stream().anyMatch(s -> s.contains("直通模式")), "报告须留直通痕：" + vo.issues());
        verifyNoInteractions(llmJson);

        ArgumentCaptor<QcReportDO> report = ArgumentCaptor.forClass(QcReportDO.class);
        verify(qcReportMapper).insertFull(report.capture());
        assertEquals("PASS", report.getValue().getVerdict());
        assertTrue(report.getValue().getIssues().contains("直通模式"));

        verify(runMapper).updateStatus(7L, "PASSED", 1);
        verify(chapterMapper).updateStatus(1L, "QC_PASS");
        verify(chapterMapper).updateStatus(2L, "QC_PASS");
    }

    @Test
    void skipLlm_scriptFail_goesRework_notPassed() {
        // 主角名未出现 → 7结构指纹 硬判不过
        stub(0, novel("{\"protagonist\":\"艾琳\"}"), List.of(
                ch(1, "起雾的码头", "今夜风很大。")));

        var vo = svc.qc(7L, true);

        assertEquals("REWORK", vo.verdict());
        verify(runMapper).updateStatus(7L, "REWORK", 1);
        verify(runMapper, never()).updateStatus(eq(7L), eq("PASSED"), anyInt());
        verify(qcReportMapper).insertFull(any(QcReportDO.class));
        verifyNoInteractions(llmJson);
    }

    @Test
    void qcDraft_scriptFail_isAdvisoryAndPasses() {
        stub(0, novel("{\"protagonist\":\"艾琳\"}"), List.of(
                ch(1, "起雾的码头", "今夜风很大。")));

        var vo = svc.qcDraft(7L);

        assertEquals("PASS", vo.verdict());
        assertTrue(vo.issues().stream().anyMatch(s -> s.contains("直通初稿")),
                "直通初稿报告必须带降级说明：" + vo.issues());
        verify(runMapper).updateStatus(7L, "PASSED", 1);
        verify(chapterMapper).updateStatus(1L, "QC_PASS");
        verifyNoInteractions(llmJson);

        ArgumentCaptor<QcReportDO> report = ArgumentCaptor.forClass(QcReportDO.class);
        verify(qcReportMapper).insertFull(report.capture());
        assertEquals("PASS", report.getValue().getVerdict());
        assertTrue(report.getValue().getIssues().contains("直通初稿"));
    }

    @Test
    void defaultQcPath_stillRunsLlm() {
        stub(0, novel("{\"protagonist\":\"艾琳\"}"), List.of(
                ch(1, "起雾的码头", "艾琳在码头等船。")));
        when(llmJson.ask(any(), any(), anyInt(), any())).thenReturn(null);

        try {
            svc.qc(7L);
        } catch (Exception ignored) {
            // 单测只关心「默认路径会碰 LLM」；快检桩返回 null 后续解析异常不影响断言
        }
        verify(llmJson).ask(any(), any(), eq(2), any());
    }
}
