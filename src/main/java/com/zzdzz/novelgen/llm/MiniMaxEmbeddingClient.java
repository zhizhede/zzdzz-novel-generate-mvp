package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.dao.LlmCallLogDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MiniMax 向量化客户端（embo-01）。协议为 MiniMax 私有格式（非 OpenAI 兼容）：
 * POST /embeddings {"model","texts","type":"db|query"} → {"vectors":[[...]], "base_resp":{...}}。
 * type 必须与用途一致：入库 "db"、检索 "query"（非对称检索，两类向量不可混用）。
 * 官方不返回 usage，tokens 按字符数近似记账（中文约 1 字 ≈ 1 token，略高估可接受）。
 * 每次调用落 llm_call_log（node=embedding）；调用方负责 fail-open。
 */
@Component
public class MiniMaxEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxEmbeddingClient.class);

    /** 输入按字符粗裁（embo-01 上下文内足够）。 */
    private static final int MAX_INPUT_CHARS = 800;

    /** 入库向量（写侧）。 */
    public static final String TYPE_DB = "db";
    /** 检索向量（读侧）。 */
    public static final String TYPE_QUERY = "query";

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final LlmCallLogDAO callLogDAO;
    private final RestClient restClient;

    public MiniMaxEmbeddingClient(LlmProperties props, ObjectMapper mapper, LlmCallLogDAO callLogDAO) {
        this.props = props;
        this.mapper = mapper;
        this.callLogDAO = callLogDAO;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }

    /** 批量向量化：与入参顺序一致；业务失败/传输失败抛 LlmException，由调用方决定降级。 */
    public List<float[]> embed(Long novelId, String type, List<String> inputs) {
        if (inputs.isEmpty()) return List.of();
        List<String> clipped = inputs.stream().map(s ->
                s == null ? "" : (s.length() <= MAX_INPUT_CHARS ? s : s.substring(0, MAX_INPUT_CHARS))).toList();
        long start = System.currentTimeMillis();
        Map<String, Object> body = Map.of("model", "embo-01", "texts", clipped, "type", type);
        int approxTokens = clipped.stream().mapToInt(String::length).sum();
        String requestJson = serialize(body);
        JsonNode resp;
        try {
            resp = restClient.post()
                    .uri("/embeddings")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            callLogDAO.insert(LlmNode.EMBEDDING, novelId, null, "embo-01",
                    approxTokens, 0, 0, 0, elapsed(start), "error", truncate(e.toString()),
                    null, requestJson, null);
            throw new LlmException("向量化调用失败（llm_call_log 已留档）: " + e.getMessage(), e);
        }
        if (resp.path("base_resp").path("status_code").asInt(0) != 0) {
            String msg = "向量化业务失败: " + resp.path("base_resp").path("status_msg").asText();
            callLogDAO.insert(LlmNode.EMBEDDING, novelId, null, "embo-01",
                    approxTokens, 0, 0, 0, elapsed(start), "error", truncate(msg),
                    null, requestJson, serialize(resp));
            throw new LlmException(msg);
        }
        JsonNode vectors = resp.path("vectors");
        if (!vectors.isArray() || vectors.size() != clipped.size()) {
            String msg = "向量化返回条数不匹配: " + vectors.size() + "/" + clipped.size();
            callLogDAO.insert(LlmNode.EMBEDDING, novelId, null, "embo-01",
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
        callLogDAO.insert(LlmNode.EMBEDDING, novelId, null, "embo-01",
                approxTokens, 0, 0, 0, elapsed(start), "ok", null,
                null, requestJson, serialize(Map.of(
                        "count", out.size(),
                        "dims", out.get(0).length,
                        "base_resp", resp.path("base_resp"))));
        log.info("向量化完成 type={} {} 条 / ~{} tokens / {}ms", type, clipped.size(), approxTokens, elapsed(start));
        return out;
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
