package com.zzdzz.novelgen.llm;

import java.util.List;

/**
 * LLM 访问的唯一边界。业务代码只依赖本接口，Spring AI 等实现以 adapter 方式在 M4 后接入。
 * 所有实现必须把每次调用全量记入 llm_call_log（含失败）。
 */
public interface LlmPort {

    /** node 用于调用台账与成本归集，如 "outline" / "scene_draft" / "ai_review" / "smoke" */
    ChatResult chat(ChatRequest request);

    record Message(String role, String content) {
        public static Message system(String content) { return new Message("system", content); }
        public static Message user(String content)   { return new Message("user", content); }
    }

    record ChatRequest(String node, Long novelId, Long chapterId,
                       List<Message> messages, Double temperature) {}

    record Usage(int promptTokens, int completionTokens, int totalTokens) {}

    record ChatResult(long callLogId, String content, String reasoning, Usage usage) {}
}
