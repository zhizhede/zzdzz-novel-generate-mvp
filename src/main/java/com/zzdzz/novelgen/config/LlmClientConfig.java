package com.zzdzz.novelgen.config;

import com.zzdzz.novelgen.llm.LlmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * LLM HTTP 客户端装配：阻塞 RestClient 与流式 streamClient 共用同一 HttpClient，
 * 从 MiniMaxClient 构造器里的手工装配抽为配置类——客户端类只留协议实现。
 */
@Configuration
public class LlmClientConfig {

    @Bean
    public HttpClient llmHttpClient(LlmProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .build();
    }

    @Bean
    public RestClient llmRestClient(LlmProperties props, HttpClient llmHttpClient) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(llmHttpClient);
        requestFactory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }
}
