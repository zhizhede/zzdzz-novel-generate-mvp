package com.zzdzz.novelgen.model.vo;

/** LLM 调用列表项（正文与 think 不进列表，详情接口再取）。 */
public record LlmLogVO(Long id, String node, Long novelId, Long chapterId, String model,
                       int promptTokens, int completionTokens, int totalTokens,
                       int latencyMs, String status, String createTime, int reasoningChars) {
}
