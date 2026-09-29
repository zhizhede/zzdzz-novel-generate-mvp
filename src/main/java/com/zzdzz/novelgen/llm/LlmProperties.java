package com.zzdzz.novelgen.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LLM 静态配置（application-local.yaml，gitignore）。落库启用接入后 baseUrl/apiKey/model 被
 * llm_providers 行覆盖（LlmProviderResolver），本配置退居兜底；masterKey 是库内密文的解密主钥，
 * 与库分离存放——只泄露数据库不等于泄露 API key。
 */
@ConfigurationProperties(prefix = "novelgen.llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration connectTimeout,
        Duration readTimeout,
        String masterKey
) {}
