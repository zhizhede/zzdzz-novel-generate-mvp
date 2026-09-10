package com.zzdzz.novelgen.model.entity;

public record LlmCallLogDO(Long id, String node, Long novelId, Long chapterId, String model,
        int promptTokens, int completionTokens, int totalTokens, int latencyMs, String status,
        String errorMsg, String reasoningText, String requestJson, String responseJson,
        boolean isDeleted) {}
