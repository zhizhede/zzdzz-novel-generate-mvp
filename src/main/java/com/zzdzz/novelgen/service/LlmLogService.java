package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import com.zzdzz.novelgen.service.data.PipelineEventDataService;
import com.zzdzz.novelgen.model.entity.LlmCallLogDO;
import com.zzdzz.novelgen.model.vo.LlmLogDetailVO;
import com.zzdzz.novelgen.model.vo.LlmLogVO;
import com.zzdzz.novelgen.model.vo.LlmTotalsVO;
import com.zzdzz.novelgen.model.vo.PageVO;
import com.zzdzz.novelgen.model.vo.PipelineEventVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** LLM 调用台账查询：分页列表 + think/正文分区详情（含完整 prompt 分段与单条成本）+ 管线事件流水。 */
@Service
@RequiredArgsConstructor
public class LlmLogService {

    private final LlmCallLogDataService llmCallLogData;
    private final PipelineEventDataService pipelineEventData;
    private final LlmNodeConfigService nodeConfig;
    private final ObjectMapper mapper;


    public PageVO<LlmLogVO> page(Long novelId, Long chapterId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        List<LlmLogVO> items = llmCallLogData
                .findPage(novelId, chapterId, safeSize, (safePage - 1) * safeSize).stream()
                .map(l -> new LlmLogVO(l.getId(), l.getNode(), l.getNovelId(), l.getChapterId(), l.getModel(),
                        l.getPromptTokens(), l.getCompletionTokens(), l.getTotalTokens(), l.getCachedTokens(),
                        l.getLatencyMs(), l.getStatus(), iso(l.getCreateTime()),
                        l.getReasoningText() == null ? 0 : l.getReasoningText().length()))
                .toList();
        return new PageVO<>(items, llmCallLogData.countBy(novelId, chapterId), safePage, safeSize);
    }

    public LlmTotalsVO totals(Long novelId, Long chapterId) {
        LlmCallLogDataService.Totals t = llmCallLogData.totalsBy(novelId, chapterId);
        return new LlmTotalsVO(t.calls(), t.promptTokens(), t.completionTokens(),
                t.totalTokens(), t.avgLatencyMs());
    }

    /** 事件流水：按作品（可选章号）倒序取最近 N 条，时间在 UI 反转展示。 */
    public List<PipelineEventVO> events(Long novelId, Integer chapterNo, int limit) {
        return pipelineEventData.list(novelId, chapterNo, Math.min(Math.max(1, limit), 500)).stream()
                .map(e -> new PipelineEventVO(e.id(), novelId, e.chapterNo(), e.stage(), e.phase(),
                        e.payloadJson(), e.createTime()))
                .toList();
    }

    public LlmLogDetailVO detail(long id) {
        LlmCallLogDO log = llmCallLogData.findById(id);
        if (log == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "调用记录不存在: " + id);
        }
        return new LlmLogDetailVO(log.getId(), log.getNode(), log.getNovelId(), log.getChapterId(), log.getModel(),
                log.getPromptTokens(), log.getCompletionTokens(), log.getTotalTokens(), log.getCachedTokens(),
                log.getLatencyMs(), log.getStatus(), log.getErrorMsg(), iso(log.getCreateTime()),
                nodeConfig.costOf(log), promptMessages(log.getRequestJson()),
                log.getReasoningText(), extractContent(log.getResponseJson()));
    }

    /** request_json.messages 逐条分段：完整 prompt（上下文包）可回放；解析失败回退空列表不拦展示。 */
    private List<LlmLogDetailVO.PromptMessageVO> promptMessages(String requestJson) {
        if (requestJson == null) return List.of();
        try {
            JsonNode arr = mapper.readTree(requestJson).path("messages");
            if (!arr.isArray()) return List.of();
            List<LlmLogDetailVO.PromptMessageVO> out = new java.util.ArrayList<>();
            for (JsonNode m : arr) {
                out.add(new LlmLogDetailVO.PromptMessageVO(
                        m.path("role").asText(""), m.path("content").asText("")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String iso(java.time.OffsetDateTime t) {
        return t == null ? null : t.toString();
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
