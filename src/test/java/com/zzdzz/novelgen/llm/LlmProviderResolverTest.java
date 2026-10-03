package com.zzdzz.novelgen.llm;

import com.zzdzz.novelgen.model.entity.LlmProviderDO;
import com.zzdzz.novelgen.service.data.LlmProviderDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 接入解析：按用途（role）取启用行优先（含超时覆盖），无行/坏行/坏角色回退 yaml 静态配置（fail-open）。 */
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

    private LlmProviderDO row(long id, String baseUrl, String key, String model, Integer readMs, String role) {
        LlmProviderDO p = new LlmProviderDO();
        p.setId(id);
        p.setName("接入-" + id);
        p.setBaseUrl(baseUrl);
        p.setApiKeyCipher(cipher.encrypt(key));
        p.setModel(model);
        p.setReadTimeoutMs(readMs);
        p.setRole(role);
        p.setEnabled(true);
        return p;
    }

    @Test
    void enabledRowWinsOverYaml() {
        when(providerData.listEnabled("chat")).thenReturn(List.of(
                row(1L, "https://db.example.com", "sk-db", "db-model", 90_000, "chat")));
        LlmProviderResolver.Resolved r = resolver.resolve(LlmRole.CHAT);
        assertThat(r.baseUrl()).isEqualTo("https://db.example.com");
        assertThat(r.apiKey()).isEqualTo("sk-db");
        assertThat(r.model()).isEqualTo("db-model");
        assertThat(r.readTimeout()).hasMillis(90_000);
        assertThat(r.connectTimeout()).hasSeconds(5); // 行未配 → yaml 兜底
        assertThat(r.providerId()).isEqualTo(1L);
    }

    /** 用途分流：会话与向量化各查各的 role，互不串门（换会话提供方不会把向量化请求带错地址）。 */
    @Test
    void 会话与向量化各取各自用途的行() {
        when(providerData.listEnabled("chat")).thenReturn(List.of(
                row(7L, "https://api.deepseek.com/v1", "sk-ds", "deepseek-v4-flash", null, "chat")));
        when(providerData.listEnabled("embedding")).thenReturn(List.of(
                row(1L, "https://api.minimaxi.com/v1", "sk-mm", "embo-01", null, "embedding")));

        LlmProviderResolver.Resolved chat = resolver.resolve(LlmRole.CHAT);
        LlmProviderResolver.Resolved emb = resolver.resolve(LlmRole.EMBEDDING);

        assertThat(chat.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(chat.model()).isEqualTo("deepseek-v4-flash");
        assertThat(emb.baseUrl()).isEqualTo("https://api.minimaxi.com/v1");
        assertThat(emb.model()).isEqualTo("embo-01");
    }

    @Test
    void emptyTableFallsBackToYaml() {
        when(providerData.listEnabled(anyString())).thenReturn(List.of());
        LlmProviderResolver.Resolved r = resolver.resolve(LlmRole.CHAT);
        assertThat(r.baseUrl()).isEqualTo("https://yaml.example.com");
        assertThat(r.apiKey()).isEqualTo("sk-yaml");
        assertThat(r.model()).isEqualTo("yaml-model");
        assertThat(r.providerId()).isNull();
    }

    /** 严格按用途：本用途无启用行时**不**借用别用途的行（避免向量化请求发去不支持该协议的服务）。 */
    @Test
    void 本用途无行时不借用别用途的行() {
        when(providerData.listEnabled(eq("embedding"))).thenReturn(List.of());
        when(providerData.listEnabled(eq("chat"))).thenReturn(List.of(
                row(7L, "https://api.deepseek.com/v1", "sk-ds", "deepseek-v4-flash", null, "chat")));

        LlmProviderResolver.Resolved emb = resolver.resolve(LlmRole.EMBEDDING);

        assertThat(emb.baseUrl()).isEqualTo("https://yaml.example.com");
        assertThat(emb.providerId()).isNull();
    }

    @Test
    void brokenCipherRowFallsBackToYaml() {
        LlmProviderDO bad = row(1L, "https://db.example.com", "sk-db", "db-model", null, "chat");
        bad.setApiKeyCipher("not-a-valid-cipher");
        when(providerData.listEnabled("chat")).thenReturn(List.of(bad));
        LlmProviderResolver.Resolved r = resolver.resolve(LlmRole.CHAT);
        assertThat(r.baseUrl()).isEqualTo("https://yaml.example.com");
        assertThat(r.apiKey()).isEqualTo("sk-yaml");
    }

    /** 解析必须按 role 过滤（SQL 层），不能只取"启用行"再在内存里筛——否则 id 小的别用途行会顶掉本用途。 */
    @Test
    void 解析按role查询而不是全量取启用行() {
        when(providerData.listEnabled(anyString())).thenReturn(List.of());
        resolver.resolve(LlmRole.EMBEDDING);
        verify(providerData).listEnabled("embedding");
    }
}
