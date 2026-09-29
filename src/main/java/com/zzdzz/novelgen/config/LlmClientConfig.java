package com.zzdzz.novelgen.config;

import com.zzdzz.novelgen.llm.LlmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * LLM HTTP 客户端装配：共享 HttpClient（阻塞 JSON 调用与流式 SSE 共用）。
 * 连接要素（baseUrl/apiKey/超时）每次调用时由 LlmProviderResolver 解析（llm_providers 启用行优先，
 * yaml 兜底）——不再预绑定到 RestClient，接入改库即生效。
 */
@Configuration
public class LlmClientConfig {

    @Bean
    public HttpClient llmHttpClient(LlmProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .build();
    }
}
