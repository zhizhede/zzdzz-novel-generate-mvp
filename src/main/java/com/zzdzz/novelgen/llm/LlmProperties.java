package com.zzdzz.novelgen.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "novelgen.llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration connectTimeout,
        Duration readTimeout
) {}
