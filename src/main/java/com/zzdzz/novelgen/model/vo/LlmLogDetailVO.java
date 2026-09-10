package com.zzdzz.novelgen.model.vo;

/** LLM 调用详情：usage + think 区（reasoningText）与正文区（content）分区展示。 */
public record LlmLogDetailVO(Long id, String node, Long novelId, Long chapterId, String model,
                             int promptTokens, int completionTokens, int totalTokens,
                             int latencyMs, String status, String errorMsg, String createTime,
                             String reasoningText, String content) {
}
