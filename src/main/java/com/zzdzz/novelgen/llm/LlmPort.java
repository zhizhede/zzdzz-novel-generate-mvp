package com.zzdzz.novelgen.llm;

import java.util.List;

/**
 * LLM 访问的唯一边界。业务代码只依赖本接口，Spring AI 等实现以 adapter 方式在 M4 后接入。
 * 所有实现必须把每次调用全量记入 llm_call_log（含失败）。
 */
public interface LlmPort {

    /** node 用于调用台账与成本归集，如 "outline" / "scene_draft" / "ai_review" / "smoke" */
    ChatResult chat(ChatRequest request);

    /** 流式增量消费者：think=true 为 <think> 思考片段，false 为正文片段。回调在调用线程执行，禁止长阻塞。 */
    @FunctionalInterface
    interface StreamDelta {
        void accept(boolean think, String piece);
    }

    /**
     * 流式调用（长文本节点实时展示用）。实现要求：llm_call_log 记账与非流式同精度
     * （完整 request/response、精确 usage），流式只是传输形态差异。
     * 默认实现忽略增量直接走阻塞 chat——开关关闭/实现未支持时零成本回退。
     */
    default ChatResult chatStream(ChatRequest request, StreamDelta onDelta) {
        return chat(request);
    }

    record Message(String role, String content) {
        public static Message system(String content) { return new Message("system", content); }
        public static Message user(String content)   { return new Message("user", content); }
    }

    record ChatRequest(String node, Long novelId, Long chapterId,
                       List<Message> messages, Double temperature) {}

    record Usage(int promptTokens, int completionTokens, int totalTokens) {}

    record ChatResult(long callLogId, String content, String reasoning, Usage usage) {}
}
