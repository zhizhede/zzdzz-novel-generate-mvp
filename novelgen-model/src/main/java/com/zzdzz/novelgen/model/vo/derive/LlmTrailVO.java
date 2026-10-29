package com.zzdzz.novelgen.model.vo.derive;

import java.time.OffsetDateTime;

/** run 的 LLM 调用时间线（运行过程页签；derive 节点的 log 以 llm_call_log.novel_id=runId 归属）。 */
public record LlmTrailVO(
        long id,
        String node,
        String status,
        String effectiveStatus,
        String effectiveStatusLabel,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        int latencyMs,
        String error,
        OffsetDateTime createTime
) {
}
