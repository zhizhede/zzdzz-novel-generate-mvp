package com.zzdzz.novelgen.model.vo;

/** LLM 用量汇总（按章节或全局聚合）。 */
public record LlmTotalsVO(long calls, long promptTokens, long completionTokens,
                          long totalTokens, long avgLatencyMs) {
}
