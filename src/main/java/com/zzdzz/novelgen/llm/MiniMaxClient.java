package com.zzdzz.novelgen.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.zzdzz.novelgen.service.data.LlmNodeConfigDataService;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * MiniMax OpenAI 兼容协议实现。除返回结果外，把请求/响应全量写入 llm_call_log，
 * 失败也落一行 error——这是断点重放与成本审计的依据。
 * 模型/参数按节点路由：llm_node_config 有 enabled 行则覆盖（model/temperature/max_tokens/extra_json），
 * 留空项走调用方或全局默认；配置查询失败不拦截调用。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MiniMaxClient implements LlmPort {

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final LlmCallLogDataService callLogDAO;
    private final com.zzdzz.novelgen.service.data.LlmNodeConfigDataService nodeConfigDAO;
    private final RestClient restClient;
    private final HttpClient streamClient;

    @Override
    public ChatResult chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        com.zzdzz.novelgen.model.entity.LlmNodeConfigDO cfg = resolveConfig(request.node());
        String model = cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()
                ? cfg.getModel() : props.model();
        Map<String, Object> body = buildBody(request, cfg, model);
        String requestJson = serialize(body);

        JsonNode response;
        try {
            response = post(body);
        } catch (Exception first) {
            // 传输类失败（超时/网络）自动重试一次；注意重试可能造成服务端重复计费
            log.warn("LLM 调用传输失败，重试一次: {}", first.toString());
            try {
                response = post(body);
            } catch (Exception second) {
                log.error("LLM 调用失败 node={} model={}", request.node(), model, second);
                long id = insertLog(request, model, requestJson, null, null, 0, 0, 0, 0,
                        elapsed(start), "error", truncate(second.toString(), 2000));
                throw new LlmException("LLM 调用失败（llm_call_log id=" + id + "）: " + second.getMessage(), second);
            }
        }

        String content = extractContent(response);
        if (content == null || content.isBlank()) {
            String msg = "响应缺少 content: " + truncate(serialize(response), 1000);
            insertLog(request, model, requestJson, response, null, 0, 0, 0, 0, elapsed(start), "error", msg);
            throw new LlmException(msg);
        }
        String reasoning = extractReasoning(response);

        JsonNode usageNode = response.path("usage");
        Usage usage = new Usage(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0),
                usageNode.path("total_tokens").asInt(0));
        int cachedTokens = usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        long latency = elapsed(start);
        long id = insertLog(request, model, requestJson, response, reasoning,
                usage.promptTokens(), cachedTokens, usage.completionTokens(), usage.totalTokens(),
                latency, "ok", null);

        log.info("LLM 调用完成 node={} model={} tokens={}={}+{} latency={}ms reasoning={}字 logId={}",
                request.node(), model, usage.totalTokens(),
                usage.promptTokens(), usage.completionTokens(), latency,
                reasoning == null ? 0 : reasoning.length(), id);
        return new ChatResult(id, content, reasoning, usage);
    }

    /**
     * 流式调用（2026-09-20 实测协议依据：stream_options.include_usage 终帧返回精确 usage 含
     * cached/reasoning 拆分；无 data:[DONE] 终止帧，以流关闭为准；M3 思考以 <think> 内联标签
     * 随 content 增量下发，标签边界可能劈在两帧之间）。
     * 记账与非流式同精度：完整 request/response（response 为聚合后的等价非流式形态）、精确 usage。
     * 中断契约：worker 线程 Thread.interrupt() 在队列 take() 上即刻解除，订阅 cancel 关流，
     * 落 error 行（部分内容进 response_json）后抛 LlmException——attemptChapter 据取消标记收敛 INTERRUPTED。
     */
    @Override
    public ChatResult chatStream(ChatRequest request, StreamDelta onDelta) {
        long start = System.currentTimeMillis();
        com.zzdzz.novelgen.model.entity.LlmNodeConfigDO cfg = resolveConfig(request.node());
        String model = cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()
                ? cfg.getModel() : props.model();
        Map<String, Object> body = buildBody(request, cfg, model);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        String requestJson = serialize(body);

        StreamAccumulator acc = new StreamAccumulator(mapper, onDelta);
        try {
            streamPost(body, acc);
            acc.finish(); // 冲刷切分器扣住的尾部增量（未闭合思考等）
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); // 语义交还上层（attemptChapter 统一清理）
            insertLog(request, model, requestJson, acc.assembledResponse(), acc.reasoningSoFar(),
                    0, 0, 0, 0, elapsed(start), "error", "流式调用被中断（硬中断，已收内容见 response_json）");
            throw new LlmException("LLM 流式调用被中断（llm_call_log 已留痕）", ie);
        } catch (StreamIoException e) {
            // 与阻塞路径对齐：零帧阶段（连接/响应头）传输失败重试一次；帧已流出则不重试（部分 token 已花）
            if (acc.frames() == 0 && e.retryable) {
                log.warn("LLM 流式调用传输失败，重试一次: {}", e.getMessage());
                return chatStream(request, onDelta);
            }
            long errId = insertLog(request, model, requestJson, acc.assembledResponse(), acc.reasoningSoFar(),
                    0, 0, 0, 0, elapsed(start), "error", truncate(e.getMessage(), 2000));
            throw new LlmException("LLM 流式调用失败（llm_call_log id=" + errId + "）: " + e.getMessage(), e);
        }

        JsonNode response = acc.assembledResponse();
        String content = extractContent(response);
        if (content == null || content.isBlank()) {
            String msg = "响应缺少 content: " + truncate(serialize(response), 1000);
            insertLog(request, model, requestJson, response, null, 0, 0, 0, 0, elapsed(start), "error", msg);
            throw new LlmException(msg);
        }
        String reasoning = extractReasoning(response);

        JsonNode usageNode = response.path("usage");
        Usage usage = new Usage(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0),
                usageNode.path("total_tokens").asInt(0));
        int cachedTokens = usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        long latency = elapsed(start);
        long id = insertLog(request, model, requestJson, response, reasoning,
                usage.promptTokens(), cachedTokens, usage.completionTokens(), usage.totalTokens(),
                latency, "ok", null);

        log.info("LLM 流式调用完成 node={} model={} tokens={}={}+{} latency={}ms frames={} logId={}",
                request.node(), model, usage.totalTokens(), usage.promptTokens(),
                usage.completionTokens(), latency, acc.frames(), id);
        return new ChatResult(id, content, reasoning, usage);
    }

    /** 阻塞至流结束；线程中断即刻解除并取消订阅（关流）。 */
    private void streamPost(Map<String, Object> body, StreamAccumulator acc) throws InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(props.baseUrl() + "/chat/completions"))
                .timeout(props.readTimeout())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(serialize(body), StandardCharsets.UTF_8))
                .build();
        SseSubscriber subscriber = new SseSubscriber();
        CompletableFuture<HttpResponse<Void>> responseFuture =
                streamClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.fromSubscriber(subscriber));
        try {
            while (true) {
                Object item = subscriber.queue().take();
                if (item == SseSubscriber.DONE) break;
                if (item instanceof SseSubscriber.Failed f) {
                    throw new StreamIoException("流中断: " + f.cause(), f.cause(), true);
                }
                String data = (String) item;
                if ("[DONE]".equals(data)) break; // 官方实测不发，防御性接收
                acc.acceptData(data);
            }
            int status = awaitStatus(responseFuture);
            if (status >= 400) {
                throw new StreamIoException("HTTP " + status + " " + subscriber.nonDataHint(), null, true);
            }
        } finally {
            subscriber.cancel();
        }
    }

    /** 帧收齐后取响应状态码；与 body 完成之间的小竞态给 2s 缓冲，超时按已收帧放行。 */
    private int awaitStatus(CompletableFuture<HttpResponse<Void>> future)
            throws InterruptedException, StreamIoException {
        try {
            HttpResponse<Void> resp = future.get(2, TimeUnit.SECONDS);
            return resp.statusCode();
        } catch (ExecutionException e) {
            throw new StreamIoException("响应头阶段失败: " + e.getCause(), e.getCause(), true);
        } catch (TimeoutException e) {
            log.warn("流式响应状态码获取超时（帧已收齐，按成功放行）");
            return 200;
        }
    }

    /** 传输层失败（连接/响应头/流中断/非 200）。retryable 仅在零帧阶段为真。 */
    private static final class StreamIoException extends RuntimeException {
        final boolean retryable;

        StreamIoException(String message, Throwable cause, boolean retryable) {
            super(message, cause);
            this.retryable = retryable;
        }
    }

    /**
     * SSE 帧订阅者：字节→行装配（跨 ByteBuffer 拼行，UTF-8 整行解码）→ data: 载荷入队。
     * 帧与终止信号经队列传回调用线程消费（take() 可被 Thread.interrupt() 解除），
     * onDelta 回调因此始终在调用线程执行，SSE 转发等重活不上 HTTP 选择器线程。
     */
    static final class SseSubscriber implements HttpResponse.BodySubscriber<Void> {
        static final Object DONE = new Object();

        record Failed(Throwable cause) {}

        private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        private volatile String nonDataHint = "";

        LinkedBlockingQueue<Object> queue() {
            return queue;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> body) {
            try {
                for (ByteBuffer bb : body) {
                    while (bb.hasRemaining()) {
                        byte b = bb.get();
                        if (b == '\n') {
                            handleLine(drainLine());
                        } else {
                            lineBuf.write(b);
                        }
                    }
                }
                Flow.Subscription s = subscription;
                if (s != null) s.request(1);
            } catch (RuntimeException e) {
                queue.add(new Failed(e));
            }
        }

        @Override
        public void onError(Throwable t) {
            queue.add(new Failed(t));
        }

        @Override
        public void onComplete() {
            try {
                String tail = drainLine();
                if (!tail.isBlank()) handleLine(tail);
            } finally {
                queue.add(DONE);
            }
        }

        /** 帧与终止信号经队列传回调用线程，body future 无用武之地（规范允许 null）。 */
        @Override
        public java.util.concurrent.CompletionStage<Void> getBody() {
            return null;
        }

        private String drainLine() {
            String line = lineBuf.toString(StandardCharsets.UTF_8);
            lineBuf.reset();
            // SSE 行尾 \r 兼容；data 载荷为 JSON，剥行尾空白无害
            return line.stripTrailing();
        }

        private void handleLine(String line) {
            if (line.startsWith("data:")) {
                queue.add(line.substring(5).stripLeading());
            } else if (!line.isBlank() && nonDataHint.isEmpty()) {
                String hint = line.length() > 500 ? line.substring(0, 500) : line;
                nonDataHint = hint;
            }
        }

        String nonDataHint() {
            return nonDataHint;
        }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) s.cancel();
        }
    }

    /**
     * 流式累积器（调用线程侧）：逐帧解析 delta，<think> 标签状态机切分思考/正文增量回调，
     * 组装与非流式等价的聚合响应（content 保持服务端原样含 <think>，后处理复用
     * extractContent/extractReasoning，保证两条路径产物逐字节一致）。
     */
    static final class StreamAccumulator {
        private final ObjectMapper mapper;
        private final StreamDelta onDelta;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoningContent = new StringBuilder();
        private final ThinkSplitter splitter = new ThinkSplitter();
        private final BiConsumer<Boolean, String> emit;
        private JsonNode usage;
        private String respId;
        private String respModel;
        private String finishReason;
        private int frames;

        StreamAccumulator(ObjectMapper mapper, StreamDelta onDelta) {
            this.mapper = mapper;
            this.onDelta = onDelta;
            this.emit = onDelta == null ? (think, piece) -> { } : onDelta::accept;
        }

        void acceptData(String data) {
            JsonNode obj;
            try {
                obj = mapper.readTree(data);
            } catch (Exception e) {
                log.warn("流式帧解析失败（跳过）: {}", data.length() > 200 ? data.substring(0, 200) : data);
                return;
            }
            frames++;
            if (usage == null || usage.isNull()) usage = obj.get("usage");
            if (respId == null) respId = obj.path("id").asText(null);
            if (respModel == null) respModel = obj.path("model").asText(null);
            JsonNode choice = obj.path("choices").path(0);
            String fr = choice.path("finish_reason").asText(null);
            if (fr != null && !fr.isBlank()) finishReason = fr;
            JsonNode delta = choice.path("delta");
            String piece = delta.path("content").asText("");
            if (!piece.isEmpty()) {
                content.append(piece);
                splitter.feed(piece, emit);
            }
            String thinkPiece = delta.path("reasoning_content").asText("");
            if (!thinkPiece.isEmpty()) {
                reasoningContent.append(thinkPiece);
                if (onDelta != null) onDelta.accept(true, thinkPiece);
            }
        }

        /** 聚合为等价非流式响应形态；中断/失败路径亦可安全调用（usage/finish 缺则空）。 */
        JsonNode assembledResponse() {
            Map<String, Object> root = new LinkedHashMap<>();
            if (respId != null) root.put("id", respId);
            if (respModel != null) root.put("model", respModel);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", content.toString());
            if (reasoningContent.length() > 0) message.put("reasoning_content", reasoningContent.toString());
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            if (finishReason != null) choice.put("finish_reason", finishReason);
            root.put("choices", List.of(choice));
            root.put("usage", usage);
            try {
                return mapper.readTree(mapper.writeValueAsString(root));
            } catch (Exception e) {
                return mapper.createObjectNode();
            }
        }

        /** 中断/失败留痕用：已收思考的近似值（闭合块走正则，未闭合回退全文）。 */
        String reasoningSoFar() {
            java.util.regex.Matcher m = THINK_BLOCK.matcher(content.toString());
            if (m.find()) return m.group(1);
            return reasoningContent.length() > 0 ? reasoningContent.toString() : null;
        }

        int frames() {
            return frames;
        }

        void finish() {
            splitter.finish(emit);
        }
    }

    /**
     * <think> 标签切分状态机：标签开闭都可能劈在增量中间，未决尾部先扣住不吐。
     * 展示用切分允许边界处短暂迟滞；存档侧始终用全文+正则，不受此影响。
     */
    static final class ThinkSplitter {
        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";

        private enum State { DECIDING, IN_THINK, TEXT }

        private State state = State.DECIDING;
        private final StringBuilder pending = new StringBuilder();

        void feed(String piece, BiConsumer<Boolean, String> emit) {
            pending.append(piece);
            switch (state) {
                case DECIDING -> {
                    int idx = pending.indexOf(OPEN);
                    if (idx >= 0) {
                        if (idx > 0) emit.accept(false, pending.substring(0, idx));
                        pending.delete(0, idx + OPEN.length());
                        state = State.IN_THINK;
                        drainThink(emit);
                    } else if (!OPEN.startsWith(pending.toString())) {
                        // 缓冲已长过 OPEN 仍非其前缀：模型没思考，全文正文直通
                        state = State.TEXT;
                        drainText(emit);
                    }
                    // 否则仍可能是 <think> 的前半段，继续攒
                }
                case IN_THINK -> drainThink(emit);
                case TEXT -> drainText(emit);
            }
        }

        private void drainThink(BiConsumer<Boolean, String> emit) {
            int idx = pending.indexOf(CLOSE);
            if (idx >= 0) {
                if (idx > 0) emit.accept(true, pending.substring(0, idx));
                pending.delete(0, idx + CLOSE.length());
                state = State.TEXT;
                drainText(emit);
            } else {
                int hold = partialSuffixLen(pending, CLOSE);
                int emitLen = pending.length() - hold;
                if (emitLen > 0) {
                    emit.accept(true, pending.substring(0, emitLen));
                    pending.delete(0, emitLen);
                }
            }
        }

        private void drainText(BiConsumer<Boolean, String> emit) {
            if (pending.length() > 0) {
                emit.accept(false, pending.toString());
                pending.setLength(0);
            }
        }

        /** 尾部可能是 tag 前缀的最长长度（该段扣住不吐，等下一帧裁决）。 */
        private static int partialSuffixLen(StringBuilder sb, String tag) {
            int max = Math.min(sb.length(), tag.length() - 1);
            for (int len = max; len > 0; len--) {
                if (tag.startsWith(sb.substring(sb.length() - len))) return len;
            }
            return 0;
        }

        void finish(BiConsumer<Boolean, String> emit) {
            switch (state) {
                // 未闭合思考：展示侧归思考流；存档侧保持原文由正则裁决
                case IN_THINK -> {
                    if (pending.length() > 0) emit.accept(true, pending.toString());
                }
                case DECIDING, TEXT -> {
                    if (pending.length() > 0) emit.accept(false, pending.toString());
                }
            }
            pending.setLength(0);
        }
    }


    /** 节点路由配置：查询失败不拦截调用（走全局默认）。 */
    private com.zzdzz.novelgen.model.entity.LlmNodeConfigDO resolveConfig(String node) {
        try {
            return nodeConfigDAO.findEnabled(node);
        } catch (Exception e) {
            log.warn("节点路由配置查询失败，走全局默认：node={}（{}）", node, e.getMessage());
            return null;
        }
    }

    private JsonNode post(Map<String, Object> body) {
        return restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private Map<String, Object> buildBody(ChatRequest request,
                                          com.zzdzz.novelgen.model.entity.LlmNodeConfigDO cfg, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            messages.add(Map.of("role", m.role(), "content", m.content()));
        }
        body.put("messages", messages);
        Double temp = cfg != null && cfg.getTemperature() != null ? cfg.getTemperature() : request.temperature();
        if (temp != null) {
            body.put("temperature", temp);
        }
        if (cfg != null && cfg.getMaxTokens() != null) {
            body.put("max_tokens", cfg.getMaxTokens());
        }
        if (cfg != null && cfg.getExtraJson() != null && !cfg.getExtraJson().isBlank()) {
            try {
                JsonNode extra = mapper.readTree(cfg.getExtraJson());
                extra.fields().forEachRemaining(e ->
                        body.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
            } catch (Exception e) {
                log.warn("节点 extra_json 解析失败，忽略：{}", e.getMessage());
            }
        }
        return body;
    }

    /** 正文：content 剥掉 <think> 块；content 为空时回退 reasoning_content 字段 */
    static String extractContent(JsonNode response) {
        JsonNode choice = response.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank()) {
            content = choice.path("message").path("reasoning_content").asText("");
            return content.strip();
        }
        // MiniMax-M3 是推理模型，思考过程以 <think> 块混在 content 里，正文要剥出来
        return content.replaceAll("(?s)<think>.*?</think>", "").strip();
    }

    /** 思考过程：与正文分开存档展示（llm_call_log.reasoning_text），不参与正文统计；usage 不受影响 */
    static String extractReasoning(JsonNode response) {
        JsonNode choice = response.path("choices").path(0);
        String message = choice.path("message").path("content").asText("");
        java.util.regex.Matcher m = THINK_BLOCK.matcher(message);
        if (m.find()) {
            return m.group(1).strip();
        }
        return choice.path("message").path("reasoning_content").asText(null);
    }

    private static final java.util.regex.Pattern THINK_BLOCK =
            java.util.regex.Pattern.compile("(?s)<think>(.*?)</think>");

    /** 台账先行入库（SQL 在 LlmCallLogDataService），失败也留痕，调用方据此可重放。 */
    private long insertLog(ChatRequest request, String model, String requestJson, JsonNode response, String reasoning,
                           int promptTokens, int cachedTokens, int completionTokens, int totalTokens,
                           long latencyMs, String status, String errorMsg) {
        String responseJson = response == null ? null : serialize(response);
        return callLogDAO.insert(request.node(), request.novelId(), request.chapterId(), model,
                promptTokens, cachedTokens, completionTokens, totalTokens, latencyMs, status, errorMsg,
                reasoning, requestJson, responseJson);
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
