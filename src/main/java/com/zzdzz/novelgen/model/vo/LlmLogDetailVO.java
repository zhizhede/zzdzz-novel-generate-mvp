package com.zzdzz.novelgen.model.vo;

import java.util.List;

/** LLM 调用详情：usage + 完整 prompt（messages 分段）+ think 区（reasoningText）与正文区（content）分区展示。
 * cost 为单条成本（元，按价目与峰谷折算），无价目行时为 null。 */
public record LlmLogDetailVO(Long id, String node, Long novelId, Long chapterId, String model,
                             int promptTokens, int completionTokens, int totalTokens, int cachedTokens,
                             int latencyMs, String status, String errorMsg, String createTime,
                             Double cost, List<PromptMessageVO> promptMessages,
                             String reasoningText, String content) {

    /** request_json.messages 逐条分段（完整上下文包可回放：AI 当时看到了什么）。 */
    public record PromptMessageVO(String role, String content) {
    }
}
