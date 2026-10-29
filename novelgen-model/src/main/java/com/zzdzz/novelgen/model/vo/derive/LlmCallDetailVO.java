package com.zzdzz.novelgen.model.vo.derive;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 单次 LLM 调用全文（运行过程「看详情」）：发出去的提示词全文、模型返回正文与思考、
 * 本轮校验不合规原因。账本行只给摘要，点开即读原始 request/response。
 */
public record LlmCallDetailVO(
        long id,
        Long runId,
        Long novelId,
        String node,
        String model,
        String status,
        String effectiveStatus,
        String effectiveStatusLabel,
        int promptTokens,
        int cachedTokens,
        int completionTokens,
        int totalTokens,
        int latencyMs,
        String error,
        String reasoning,
        List<Msg> request,
        String requestRaw,
        String content,
        String responseRaw,
        OffsetDateTime createTime
) {
    public record Msg(String role, String content) {
    }
}
