package com.zzdzz.novelgen.llm;

import com.zzdzz.novelgen.model.dto.LlmProviderDTO;
import com.zzdzz.novelgen.service.data.LlmProviderDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 接入解析：DB 启用行优先（含超时覆盖），无行/坏行回退 yaml 静态配置（fail-open）。 */
class LlmProviderResolverTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private LlmProviderDataService providerData;
    private SecretCipher cipher;
    private LlmProperties props;
    private LlmProviderResolver resolver;

    @BeforeEach
    void setUp() {
        providerData = mock(LlmProviderDataService.class);
        cipher = new SecretCipher(props = new LlmProperties(
                "https://yaml.example.com", "sk-yaml", "yaml-model",
                Duration.ofSeconds(5), Duration.ofSeconds(30), MASTER_KEY));
        resolver = new LlmProviderResolver(providerData, cipher, props);
    }

    private LlmProviderDTO row(String baseUrl, String key, String model, Integer readMs) {
        LlmProviderDTO p = new LlmProviderDTO();
        p.setId(1L);
        p.setName("主接入");
        p.setBaseUrl(baseUrl);
        p.setApiKeyCipher(cipher.encrypt(key));
        p.setModel(model);
        p.setReadTimeoutMs(readMs);
        p.setEnabled(true);
        return p;
    }

    @Test
    void enabledRowWinsOverYaml() {
        when(providerData.listEnabled()).thenReturn(List.of(
                row("https://db.example.com", "sk-db", "db-model", 90_000)));
        LlmProviderResolver.Resolved r = resolver.resolve();
        assertThat(r.baseUrl()).isEqualTo("https://db.example.com");
        assertThat(r.apiKey()).isEqualTo("sk-db");
        assertThat(r.model()).isEqualTo("db-model");
        assertThat(r.readTimeout()).hasMillis(90_000);
        assertThat(r.connectTimeout()).hasSeconds(5); // 行未配 → yaml 兜底
        assertThat(r.providerId()).isEqualTo(1L);
    }

    @Test
    void emptyTableFallsBackToYaml() {
        when(providerData.listEnabled()).thenReturn(List.of());
        LlmProviderResolver.Resolved r = resolver.resolve();
        assertThat(r.baseUrl()).isEqualTo("https://yaml.example.com");
        assertThat(r.apiKey()).isEqualTo("sk-yaml");
        assertThat(r.model()).isEqualTo("yaml-model");
        assertThat(r.providerId()).isNull();
    }

    @Test
    void brokenCipherRowFallsBackToYaml() {
        LlmProviderDTO bad = row("https://db.example.com", "sk-db", "db-model", null);
        bad.setApiKeyCipher("not-a-valid-cipher");
        when(providerData.listEnabled()).thenReturn(List.of(bad));
        LlmProviderResolver.Resolved r = resolver.resolve();
        assertThat(r.baseUrl()).isEqualTo("https://yaml.example.com");
        assertThat(r.apiKey()).isEqualTo("sk-yaml");
    }
}
