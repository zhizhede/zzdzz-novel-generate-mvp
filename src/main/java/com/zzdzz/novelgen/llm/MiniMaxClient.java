package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import com.zzdzz.novelgen.dao.LlmCallLogDAO;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MiniMax OpenAI 兼容协议实现。除返回结果外，把请求/响应全量写入 llm_call_log，
 * 失败也落一行 error——这是断点重放与成本审计的依据。
 */
@Component
public class MiniMaxClient implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxClient.class);

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final LlmCallLogDAO callLogDAO;
    private final RestClient restClient;

    public MiniMaxClient(LlmProperties props, ObjectMapper mapper, LlmCallLogDAO callLogDAO) {
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

    @Override
    public ChatResult chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        Map<String, Object> body = buildBody(request);
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
                log.error("LLM 调用失败 node={} model={}", request.node(), props.model(), second);
                long id = insertLog(request, requestJson, null, null, 0, 0, 0,
                        elapsed(start), "error", truncate(second.toString(), 2000));
                throw new LlmException("LLM 调用失败（llm_call_log id=" + id + "）: " + second.getMessage(), second);
            }
        }

        String content = extractContent(response);
        if (content == null || content.isBlank()) {
            String msg = "响应缺少 content: " + truncate(serialize(response), 1000);
            insertLog(request, requestJson, response, null, 0, 0, 0, elapsed(start), "error", msg);
            throw new LlmException(msg);
        }
        String reasoning = extractReasoning(response);

        JsonNode usageNode = response.path("usage");
        Usage usage = new Usage(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0),
                usageNode.path("total_tokens").asInt(0));
        long latency = elapsed(start);
        long id = insertLog(request, requestJson, response, reasoning,
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                latency, "ok", null);

        log.info("LLM 调用完成 node={} model={} tokens={}={}+{} latency={}ms reasoning={}字 logId={}",
                request.node(), props.model(), usage.totalTokens(),
                usage.promptTokens(), usage.completionTokens(), latency,
                reasoning == null ? 0 : reasoning.length(), id);
        return new ChatResult(id, content, reasoning, usage);
    }

    private JsonNode post(Map<String, Object> body) {
        return restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private Map<String, Object> buildBody(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        List<Map<String, String>> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            messages.add(Map.of("role", m.role(), "content", m.content()));
        }
        body.put("messages", messages);
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
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

    /** 台账先行入库（SQL 在 LlmCallLogDAO），失败也留痕，调用方据此可重放。 */
    private long insertLog(ChatRequest request, String requestJson, JsonNode response, String reasoning,
                           int promptTokens, int completionTokens, int totalTokens,
                           long latencyMs, String status, String errorMsg) {
        String responseJson = response == null ? null : serialize(response);
        return callLogDAO.insert(request.node(), request.novelId(), request.chapterId(), props.model(),
                promptTokens, completionTokens, totalTokens, latencyMs, status, errorMsg,
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
