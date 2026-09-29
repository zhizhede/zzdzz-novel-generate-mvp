package com.zzdzz.novelgen.llm;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MiniMax 向量化客户端（embo-01）。协议为 MiniMax 私有格式（非 OpenAI 兼容）：
 * POST /embeddings {"model","texts","type":"db|query"} → {"vectors":[[...]], "base_resp":{...}}。
 * type 必须与用途一致：入库 "db"、检索 "query"（非对称检索，两类向量不可混用）。
 * 官方不返回 usage，tokens 按字符数近似记账（中文约 1 字 ≈ 1 token，略高估可接受）。
 * 连接要素走 LlmProviderResolver.resolve(EMBEDDING)（llm_providers 里 role=embedding 的启用行优先，
 * yaml 兜底）——与会话接入（role=chat，可能已是别家）彻底分开；模型取行内 model，空则 embo-01。
 * 超时双保险与 MiniMaxClient.post 同款（sendAsync + future.get，防响应体断供永久阻塞）。
 * 每次调用落 llm_call_log（node=embedding）；调用方负责 fail-open。
 */
@Component
@Slf4j
public class MiniMaxEmbeddingClient {


    /** 输入按字符粗裁（embo-01 上下文内足够）。 */
    private static final int MAX_INPUT_CHARS = 800;

    /** 行内 model 留空时的兜底向量模型。 */
    private static final String DEFAULT_MODEL = "embo-01";

    /** 入库向量（写侧）。 */
    public static final String TYPE_DB = "db";
    /** 检索向量（读侧）。 */
    public static final String TYPE_QUERY = "query";

    private final ObjectMapper mapper;
    private final LlmCallLogDataService callLogDAO;
    private final LlmProviderResolver providerResolver;
    private final HttpClient httpClient;

    public MiniMaxEmbeddingClient(ObjectMapper mapper, LlmCallLogDataService callLogDAO,
                                  LlmProviderResolver providerResolver, HttpClient httpClient) {
        this.mapper = mapper;
        this.callLogDAO = callLogDAO;
        this.providerResolver = providerResolver;
        this.httpClient = httpClient;
    }

    /** 批量向量化：与入参顺序一致；业务失败/传输失败抛 LlmException，由调用方决定降级。 */
    public List<float[]> embed(Long novelId, String type, List<String> inputs) {
        if (inputs.isEmpty()) return List.of();
        List<String> clipped = inputs.stream().map(s ->
                s == null ? "" : (s.length() <= MAX_INPUT_CHARS ? s : s.substring(0, MAX_INPUT_CHARS))).toList();
        long start = System.currentTimeMillis();
        // 用途固定为 embedding：会话换提供方（如 DeepSeek）不会把 MiniMax 私有协议请求发错地方
        LlmProviderResolver.Resolved provider = providerResolver.resolve(LlmRole.EMBEDDING);
        String model = provider.model() != null && !provider.model().isBlank() ? provider.model() : DEFAULT_MODEL;
        Map<String, Object> body = Map.of("model", model, "texts", clipped, "type", type);
        int approxTokens = clipped.stream().mapToInt(String::length).sum();
        String requestJson = serialize(body);
        JsonNode resp;
        try {
            resp = post(provider, body);
        } catch (Exception e) {
            callLogDAO.insert(LlmNode.EMBEDDING, novelId, null, model,
                    approxTokens, 0, 0, 0, elapsed(start), "error", truncate(e.toString()),
                    null, requestJson, null);
            throw new LlmException("向量化调用失败（llm_call_log 已留档）: " + e.getMessage(), e);
        }
        if (resp.path("base_resp").path("status_code").asInt(0) != 0) {
            String msg = "向量化业务失败: " + resp.path("base_resp").path("status_msg").asText();
            callLogDAO.insert(LlmNode.EMBEDDING, novelId, null, model,
                    approxTokens, 0, 0, 0, elapsed(start), "error", truncate(msg),
                    null, requestJson, serialize(resp));
            throw new LlmException(msg);
        }
        JsonNode vectors = resp.path("vectors");
        if (!vectors.isArray() || vectors.size() != clipped.size()) {
            String msg = "向量化返回条数不匹配: " + vectors.size() + "/" + clipped.size();
            callLogDAO.insert(LlmNode.EMBEDDING, novelId, null, model,
                    approxTokens, 0, 0, 0, elapsed(start), "error", truncate(msg),
                    null, requestJson, serialize(resp));
            throw new LlmException(msg);
        }
        List<float[]> out = new ArrayList<>(clipped.size());
        for (JsonNode vec : vectors) {
            float[] v = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) v[i] = (float) vec.get(i).asDouble();
            out.add(v);
        }
        callLogDAO.insert(LlmNode.EMBEDDING, novelId, null, model,
                approxTokens, 0, 0, 0, elapsed(start), "ok", null,
                null, requestJson, serialize(Map.of(
                        "count", out.size(),
                        "dims", out.get(0).length,
                        "base_resp", resp.path("base_resp"))));
        log.info("向量化完成 type={} {} 条 / ~{} tokens / {}ms", type, clipped.size(), approxTokens, elapsed(start));
        return out;
    }

    /** 与 MiniMaxClient.post 同款：sendAsync + future.get 超时双保险，超时即 cancel(true)。 */
    private JsonNode post(LlmProviderResolver.Resolved provider, Map<String, Object> body) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(provider.baseUrl() + "/embeddings"))
                .timeout(provider.readTimeout())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(serialize(body), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> resp = future.get(provider.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            return mapper.readTree(resp.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("向量化响应超时（>" + provider.readTimeout().toSeconds() + "s）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("向量化调用被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("向量化传输失败: " + String.valueOf(e.getCause()), e.getCause());
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String text) {
        return text.length() <= 1500 ? text : text.substring(0, 1500) + "...(truncated)";
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
