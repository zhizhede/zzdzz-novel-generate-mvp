package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.GateReportDAO;
import com.zzdzz.novelgen.dao.LlmCallLogDAO;
import com.zzdzz.novelgen.dao.SceneDAO;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.vo.ChapterDetailVO;
import com.zzdzz.novelgen.model.vo.ChapterListItemVO;
import com.zzdzz.novelgen.model.vo.GateReportVO;
import com.zzdzz.novelgen.model.vo.LlmTotalsVO;
import com.zzdzz.novelgen.model.vo.SceneVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 章查询：列表摘要 + 详情聚合（正文/场景/最新章级门禁/本章 LLM 用量）。 */
@Service
public class ChapterQueryService {

    private final ChapterDAO chapterDAO;
    private final SceneDAO sceneDAO;
    private final GateReportDAO gateReportDAO;
    private final LlmCallLogDAO llmCallLogDAO;
    private final ObjectMapper mapper;

    public ChapterQueryService(ChapterDAO chapterDAO, SceneDAO sceneDAO,
                               GateReportDAO gateReportDAO, LlmCallLogDAO llmCallLogDAO,
                               ObjectMapper mapper) {
        this.chapterDAO = chapterDAO;
        this.sceneDAO = sceneDAO;
        this.gateReportDAO = gateReportDAO;
        this.llmCallLogDAO = llmCallLogDAO;
        this.mapper = mapper;
    }

    public List<ChapterListItemVO> listByNovel(long novelId) {
        return chapterDAO.listSummariesByNovel(novelId).stream()
                .map(c -> new ChapterListItemVO(c.id(), c.chapterNo(), c.title(), c.status(),
                        c.round(), c.budgetMin(), c.budgetMax()))
                .toList();
    }

    public ChapterDetailVO detail(long chapterId) {
        ChapterDO ch = chapterDAO.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        List<SceneVO> scenes = sceneDAO.findByChapter(chapterId).stream()
                .map(s -> new SceneVO(s.id(), s.sceneNo(), s.goal(), s.draftText(),
                        s.gateStatus(), s.revisionRound()))
                .toList();
        return new ChapterDetailVO(ch.id(), ch.chapterNo(), ch.title(), ch.status(), ch.round(),
                ch.budgetMin(), ch.budgetMax(), ch.fullText(), scenes,
                latestGateReport(chapterId), toTotalsVO(llmCallLogDAO.totalsBy(null, chapterId)));
    }

    private GateReportVO latestGateReport(long chapterId) {
        GateReportDAO.LatestChapterReport row = gateReportDAO.findLatestChapterReport(chapterId);
        if (row == null) return null;
        try {
            JsonNode node = mapper.readTree(row.resultJson());
            List<java.util.Map<String, Object>> checks = mapper.convertValue(
                    node.path("checks"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {
                    });
            return new GateReportVO("mechanical", row.passed(),
                    row.createTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    checks);
        } catch (Exception e) {
            throw new IllegalStateException("门禁报告不可解析", e);
        }
    }

    private LlmTotalsVO toTotalsVO(LlmCallLogDAO.Totals t) {
        return new LlmTotalsVO(t.calls(), t.promptTokens(), t.completionTokens(),
                t.totalTokens(), t.avgLatencyMs());
    }
}
