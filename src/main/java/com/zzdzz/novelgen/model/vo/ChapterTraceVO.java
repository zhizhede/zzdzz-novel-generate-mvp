package com.zzdzz.novelgen.model.vo;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 章生成档案（trace）：这章是怎么来的——一屏时间线的三路数据源。
 * steps=步骤状态行；calls=按章 LLM 台账（仅元数据，全文走 /api/llm-logs/{id}）；
 * checks=全轮次门禁/评审判定（含场景级与读者评审五问）；nodeStats=按节点用量与成本小计。
 * 前端按 createTime 合并渲染；历史章（V20 之前）steps 可能为空，calls/checks 照样重建时间线。
 */
public record ChapterTraceVO(long chapterId, int chapterNo, String title, String status,
                             List<StepItem> steps, List<CallItem> calls, List<CheckItem> checks,
                             List<NodeStatItem> nodeStats) {

    public record StepItem(String step, String subKey, int attempt, String status,
                           String detail, String createTime, String updateTime) {
    }

    /** LLM 调用元数据行；cost 无价目行时为 null。 */
    public record CallItem(long id, String node, String model, String status,
                           int promptTokens, int completionTokens, int totalTokens, int cachedTokens,
                           int latencyMs, Double cost, String createTime) {
    }

    /** 门禁/评审判定行；result 为解析后的判定 JSON（checks 明细/五问 verdict/issues 原文）。 */
    public record CheckItem(Long sceneId, String gateType, int round, boolean passed,
                            JsonNode result, String createTime) {
    }

    public record NodeStatItem(String node, int calls, long totalTokens, Double cost) {
    }
}
