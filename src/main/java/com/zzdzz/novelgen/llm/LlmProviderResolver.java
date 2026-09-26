package com.zzdzz.novelgen.llm;

import com.zzdzz.novelgen.model.dto.LlmProviderDTO;
import com.zzdzz.novelgen.service.data.LlmProviderDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * LLM 接入解析：llm_providers 启用行优先（单活，取 id 最小），无行/查询失败回退 yaml 静态配置
 * （fail-open，老环境与本地开发零变化）。每次调用解析一次——与节点路由 resolveConfig 同口径，
 * 改库即生效，无需重启。
 */
@Component
@Slf4j
public class LlmProviderResolver {

    /** 一次 LLM 调用的连接四要素。 */
    public record Resolved(String baseUrl, String apiKey, String model,
                           Duration connectTimeout, Duration readTimeout, Long providerId) {}

    private final LlmProviderDataService providerData;
    private final SecretCipher cipher;
    private final LlmProperties props;

    public LlmProviderResolver(LlmProviderDataService providerData, SecretCipher cipher, LlmProperties props) {
        this.providerData = providerData;
        this.cipher = cipher;
        this.props = props;
    }

    public Resolved resolve() {
        List<LlmProviderDTO> enabled = providerData.listEnabled();
        if (!enabled.isEmpty()) {
            LlmProviderDTO p = enabled.get(0);
            try {
                return new Resolved(p.getBaseUrl(), cipher.decrypt(p.getApiKeyCipher()), p.getModel(),
                        Duration.ofMillis(p.getConnectTimeoutMs() != null ? p.getConnectTimeoutMs() : props.connectTimeout().toMillis()),
                        Duration.ofMillis(p.getReadTimeoutMs() != null ? p.getReadTimeoutMs() : props.readTimeout().toMillis()),
                        p.getId());
            } catch (Exception e) {
                log.error("LLM 接入行 id={} 解析失败（解密/配置损坏），回退 yaml 静态配置：{}", p.getId(), e.getMessage());
            }
        }
        return new Resolved(props.baseUrl(), props.apiKey(), props.model(),
                props.connectTimeout(), props.readTimeout(), null);
    }
}
