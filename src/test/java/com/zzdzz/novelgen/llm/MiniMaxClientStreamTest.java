package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式解析行为基线（2026-09-20 实测协议锁定）：
 * M3 思考以 <think> 内联标签随 content 增量下发、标签边界可劈在两帧之间；
 * usage 走 stream_options.include_usage 终帧；无 [DONE]，以流关闭为准。
 */
class MiniMaxClientStreamTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 收集 (think?, piece) 对，便于断言。 */
    private static final class Collector implements LlmPort.StreamDelta {
        final List<String> think = new ArrayList<>();
        final List<String> text = new ArrayList<>();

        @Override
        public void accept(boolean isThink, String piece) {
            (isThink ? think : text).add(piece);
        }

        /** ThinkSplitter 吃 BiConsumer，适配一层。 */
        BiConsumer<Boolean, String> bi() {
            return this::accept;
        }
    }

    // ---------- ThinkSplitter ----------

    @Test
    void thinkTagStraddlesDeltas() {
        Collector c = new Collector();
        MiniMaxClient.ThinkSplitter s = new MiniMaxClient.ThinkSplitter();
        s.feed("<thi", c.bi());
        s.feed("nk>The user", c.bi());
        s.feed(" wants</thi", c.bi());
        s.feed("nk>\n\n正文开始", c.bi());
        s.finish(c.bi());
        assertThat(String.join("", c.think)).isEqualTo("The user wants");
        assertThat(String.join("", c.text)).isEqualTo("\n\n正文开始");
    }

    @Test
    void noThinkMeansAllText() {
        Collector c = new Collector();
        MiniMaxClient.ThinkSplitter s = new MiniMaxClient.ThinkSplitter();
        s.feed("直接正文", c.bi());
        s.feed("没有思考", c.bi());
        s.finish(c.bi());
        assertThat(c.think).isEmpty();
        assertThat(String.join("", c.text)).isEqualTo("直接正文没有思考");
    }

    @Test
    void partialOpenPrefixHeldUntilDecided() {
        Collector c = new Collector();
        MiniMaxClient.ThinkSplitter s = new MiniMaxClient.ThinkSplitter();
        s.feed("<think", c.bi()); // 可能是标签前半，也可能字面正文——先扣住
        assertThat(c.think).isEmpty();
        assertThat(c.text).isEmpty();
        s.feed(">思考内容</think>正文", c.bi());
        s.finish(c.bi());
        assertThat(String.join("", c.think)).isEqualTo("思考内容");
        assertThat(String.join("", c.text)).isEqualTo("正文");
    }

    @Test
    void unclosedThinkFinishGoesToThinkStream() {
        Collector c = new Collector();
        MiniMaxClient.ThinkSplitter s = new MiniMaxClient.ThinkSplitter();
        s.feed("<think>没收尾的思考", c.bi());
        s.finish(c.bi());
        assertThat(String.join("", c.think)).isEqualTo("没收尾的思考");
        assertThat(c.text).isEmpty();
    }

    @Test
    void textBeforeOpenTagEmittedAsText() {
        Collector c = new Collector();
        MiniMaxClient.ThinkSplitter s = new MiniMaxClient.ThinkSplitter();
        s.feed("前言<think>思考</think>后文", c.bi());
        s.finish(c.bi());
        assertThat(c.text).containsExactly("前言", "后文");
        assertThat(String.join("", c.think)).isEqualTo("思考");
    }

    // ---------- StreamAccumulator ----------

    private static String frame(String contentDelta, String finishReason, String usageJson) {
        StringBuilder sb = new StringBuilder("{\"choices\":[{\"index\":0,\"delta\":{");
        if (contentDelta != null) sb.append("\"content\":\"").append(contentDelta).append("\"");
        sb.append("}");
        if (finishReason != null) sb.append(",\"finish_reason\":\"").append(finishReason).append("\"");
        sb.append("}]");
        if (usageJson != null) sb.append(",\"usage\":").append(usageJson);
        return sb.append("}").toString();
    }

    @Test
    void accumulatorAssemblesEquivalentNonStreamResponse() throws Exception {
        Collector c = new Collector();
        MiniMaxClient.StreamAccumulator acc = new MiniMaxClient.StreamAccumulator(mapper, c);
        acc.acceptData(frame("<think>思考", null, null));
        acc.acceptData(frame("片段</think>\\n\\n正文内容", null, null));
        acc.acceptData(frame(null, "stop", "{\"total_tokens\":237,\"prompt_tokens\":186,\"completion_tokens\":51,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":128}}"));
        acc.finish();

        var response = acc.assembledResponse();
        // content 保持服务端原样（含 <think>，JSON \n 转义解析为真实换行），与非流式同构
        String raw = response.path("choices").path(0).path("message").path("content").asText();
        assertThat(raw).isEqualTo("<think>思考片段</think>\n\n正文内容");
        // 后处理复用阻塞路径的抽取逻辑
        assertThat(MiniMaxClient.extractContent(response)).isEqualTo("正文内容");
        assertThat(MiniMaxClient.extractReasoning(response)).isEqualTo("思考片段");
        assertThat(response.path("usage").path("total_tokens").asInt()).isEqualTo(237);
        assertThat(response.path("usage").path("prompt_tokens_details").path("cached_tokens").asInt()).isEqualTo(128);
        // 增量切分：思考流 + 正文流各归其位
        assertThat(String.join("", c.think)).isEqualTo("思考片段");
        assertThat(String.join("", c.text)).isEqualTo("\n\n正文内容");
        assertThat(acc.frames()).isEqualTo(3);
    }

    @Test
    void badFrameSkippedNotFatal() {
        Collector c = new Collector();
        MiniMaxClient.StreamAccumulator acc = new MiniMaxClient.StreamAccumulator(mapper, c);
        acc.acceptData("不是JSON{{{");
        acc.acceptData(frame("正文", null, null));
        // frames 只计成功解析帧——零帧重试判定以「有无有效帧」为准
        assertThat(acc.frames()).isEqualTo(1);
        assertThat(String.join("", c.text)).isEqualTo("正文");
    }

    @Test
    void reasoningContentDeltaGoesToThinkStream() {
        Collector c = new Collector();
        MiniMaxClient.StreamAccumulator acc = new MiniMaxClient.StreamAccumulator(mapper, c);
        acc.acceptData("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"独立思考字段\"}}]}");
        acc.finish();
        assertThat(String.join("", c.think)).isEqualTo("独立思考字段");
        assertThat(acc.assembledResponse().path("choices").path(0).path("message")
                .path("reasoning_content").asText()).isEqualTo("独立思考字段");
    }

    // ---------- SseSubscriber 行装配 ----------

    private static final class NoopSubscription implements Flow.Subscription {
        @Override
        public void request(long n) { }
        @Override
        public void cancel() { }
    }

    @Test
    void sseLinesAssembledAcrossBuffers() throws Exception {
        MiniMaxClient.SseSubscriber sub = new MiniMaxClient.SseSubscriber();
        sub.onSubscribe(new NoopSubscription());
        // 一行劈成三段 + \r\n 兼容 + 无换行尾部 + 非 data 行留痕
        byte[] all = "data: {\"a\":1}\r\ndata: {\"b\":2}\ndata: [DONE]\nerror-body-snippet".getBytes(StandardCharsets.UTF_8);
        sub.onNext(List.of(ByteBuffer.wrap(all, 0, 9), ByteBuffer.wrap(all, 9, 8), ByteBuffer.wrap(all, 17, all.length - 17)));
        sub.onComplete();

        var queue = sub.queue();
        assertThat((String) queue.poll()).isEqualTo("{\"a\":1}");
        assertThat((String) queue.poll()).isEqualTo("{\"b\":2}");
        assertThat((String) queue.poll()).isEqualTo("[DONE]");
        assertThat(queue.poll()).isSameAs(MiniMaxClient.SseSubscriber.DONE);
        assertThat(sub.nonDataHint()).isEqualTo("error-body-snippet");
    }

    @Test
    void doneSentinelComesLast() throws Exception {
        MiniMaxClient.SseSubscriber sub = new MiniMaxClient.SseSubscriber();
        sub.onSubscribe(new NoopSubscription());
        sub.onNext(List.of(ByteBuffer.wrap("data: x\n".getBytes(StandardCharsets.UTF_8))));
        sub.onComplete();
        var queue = sub.queue();
        assertThat((String) queue.poll()).isEqualTo("x");
        assertThat(queue.poll()).isSameAs(MiniMaxClient.SseSubscriber.DONE);
        assertThat(queue.poll()).isNull();
    }
}
