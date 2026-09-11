package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.LlmCallLogDAO;
import com.zzdzz.novelgen.dao.PipelineEventDAO;
import com.zzdzz.novelgen.model.entity.LlmCallLogDO;
import com.zzdzz.novelgen.model.vo.LlmLogDetailVO;
import com.zzdzz.novelgen.model.vo.LlmLogVO;
import com.zzdzz.novelgen.model.vo.LlmTotalsVO;
import com.zzdzz.novelgen.model.vo.PageVO;
import com.zzdzz.novelgen.model.vo.PipelineEventVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** LLM 调用台账查询：分页列表 + think/正文分区详情 + 管线事件流水。 */
@Service
public class LlmLogService {

    private final LlmCallLogDAO llmCallLogDAO;
    private final PipelineEventDAO pipelineEventDAO;
    private final ObjectMapper mapper;

    public LlmLogService(LlmCallLogDAO llmCallLogDAO, PipelineEventDAO pipelineEventDAO,
                         ObjectMapper mapper) {
        this.llmCallLogDAO = llmCallLogDAO;
        this.pipelineEventDAO = pipelineEventDAO;
        this.mapper = mapper;
    }

    public PageVO<LlmLogVO> page(Long novelId, Long chapterId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        List<LlmLogVO> items = llmCallLogDAO
                .findPage(novelId, chapterId, safeSize, (safePage - 1) * safeSize).stream()
                .map(l -> new LlmLogVO(l.id(), l.node(), l.novelId(), l.chapterId(), l.model(),
                        l.promptTokens(), l.completionTokens(), l.totalTokens(), l.latencyMs(),
                        l.status(), null,
                        l.reasoningText() == null ? 0 : l.reasoningText().length()))
                .toList();
        return new PageVO<>(items, llmCallLogDAO.countBy(novelId, chapterId), safePage, safeSize);
    }

    public LlmTotalsVO totals(Long novelId, Long chapterId) {
        LlmCallLogDAO.Totals t = llmCallLogDAO.totalsBy(novelId, chapterId);
        return new LlmTotalsVO(t.calls(), t.promptTokens(), t.completionTokens(),
                t.totalTokens(), t.avgLatencyMs());
    }

    /** 事件流水：按作品（可选章号）倒序取最近 N 条，时间在 UI 反转展示。 */
    public List<PipelineEventVO> events(Long novelId, Integer chapterNo, int limit) {
        return pipelineEventDAO.list(novelId, chapterNo, Math.min(Math.max(1, limit), 500)).stream()
                .map(e -> new PipelineEventVO(e.id(), novelId, e.chapterNo(), e.stage(), e.phase(),
                        e.payloadJson(), e.createTime()))
                .toList();
    }

    public LlmLogDetailVO detail(long id) {
        LlmCallLogDO log = llmCallLogDAO.findById(id);
        if (log == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "调用记录不存在: " + id);
        }
        return new LlmLogDetailVO(log.id(), log.node(), log.novelId(), log.chapterId(), log.model(),
                log.promptTokens(), log.completionTokens(), log.totalTokens(), log.latencyMs(),
                log.status(), log.errorMsg(), null, log.reasoningText(), extractContent(log.responseJson()));
    }

    /** 从留档响应里剥出正文：库里是原始响应，think 区（reasoningText）与正文区在此分离。 */
    private String extractContent(String responseJson) {
        if (responseJson == null) return null;
        try {
            JsonNode root = mapper.readTree(responseJson);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null) return null;
            return content.replaceAll("(?s)<think>.*?</think>", "").trim();
        } catch (Exception e) {
            return null;
        }
    }
}
