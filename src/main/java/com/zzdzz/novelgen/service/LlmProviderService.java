package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.llm.LlmProperties;
import com.zzdzz.novelgen.llm.SecretCipher;
import com.zzdzz.novelgen.model.dto.LlmProviderDTO;
import com.zzdzz.novelgen.service.data.LlmProviderDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LLM 接入管理（平台级，素材库·模型接入页签）：增删改查 + 连通性测试。
 * 明文 api key 只在 create/update 入参出现一次，即刻 AES-GCM 加密落库；查询回显只给掩码，
 * 永不回传明文。启用的接入全平台唯一（启用新行自动停用旧行）；运行时经 LlmProviderResolver 消费。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmProviderService {

    /** 列表/详情行：key 永远只回掩码。 */
    public record ProviderVO(Long id, String name, String baseUrl, String model,
                             Integer connectTimeoutMs, Integer readTimeoutMs,
                             boolean enabled, String remark, String keyMasked) {}

    public record TestResult(boolean ok, long latencyMs, String message) {}

    private final LlmProviderDataService providerData;
    private final SecretCipher cipher;
    private final LlmProperties props;

    public List<ProviderVO> list() {
        return providerData.listAll().stream().map(this::toVO).toList();
    }

    public void create(String name, String baseUrl, String apiKey, String model,
                       Integer connectTimeoutMs, Integer readTimeoutMs, boolean enabled, String remark) {
        requireName(name);
        if (providerData.findByName(name.strip()) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "同名接入已存在：" + name.strip());
        }
        requireBaseUrl(baseUrl);
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "API key 必填");
        }
        LlmProviderDTO p = new LlmProviderDTO();
        p.setName(name.strip());
        p.setBaseUrl(baseUrl.strip());
        p.setApiKeyCipher(cipher.encrypt(apiKey.strip()));
        p.setModel(model == null || model.isBlank() ? null : model.strip());
        p.setConnectTimeoutMs(connectTimeoutMs);
        p.setReadTimeoutMs(readTimeoutMs);
        p.setEnabled(enabled);
        p.setRemark(remark == null ? "" : remark.strip());
        providerData.save(p);
        applySingleActive(p);
        log.info("LLM 接入已创建 id={} name={}（key 已加密落库，明文不再留存）", p.getId(), p.getName());
    }

    /** 更新：apiKey 留空 = 保留原密文（掩码口径下前端不回传明文）。 */
    public void update(long id, String name, String baseUrl, String apiKey, String model,
                       Integer connectTimeoutMs, Integer readTimeoutMs, Boolean enabled, String remark) {
        LlmProviderDTO p = require(id);
        if (name != null && !name.isBlank()) {
            String n = name.strip();
            LlmProviderDTO same = providerData.findByName(n);
            if (same != null && same.getId() != id) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "同名接入已存在：" + n);
            }
            p.setName(n);
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            requireBaseUrl(baseUrl);
            p.setBaseUrl(baseUrl.strip());
        }
        if (apiKey != null && !apiKey.isBlank()) {
            p.setApiKeyCipher(cipher.encrypt(apiKey.strip()));
        }
        if (model != null) {
            p.setModel(model.isBlank() ? null : model.strip());
        }
        if (connectTimeoutMs != null) {
            p.setConnectTimeoutMs(connectTimeoutMs);
        }
        if (readTimeoutMs != null) {
            p.setReadTimeoutMs(readTimeoutMs);
        }
        if (enabled != null) {
            p.setEnabled(enabled);
        }
        if (remark != null) {
            p.setRemark(remark.strip());
        }
        providerData.updateById(p);
        if (p.isEnabled()) {
            applySingleActive(p);
        }
        log.info("LLM 接入已更新 id={} enabled={}", id, p.isEnabled());
    }

    public void delete(long id) {
        require(id);
        providerData.softDelete(id);
        log.info("LLM 接入已软删 id={}", id);
    }

    /**
     * 连通性测试：对指定接入发一次 max_tokens=1 的最小 chat 请求，返回延迟与人话结论。
     * 独立 HTTP 不走 MiniMaxClient——被测对象就是它自己的连接要素（含未启用的行）。
     */
    public TestResult test(long id) {
        LlmProviderDTO p = require(id);
        String key;
        try {
            key = cipher.decrypt(p.getApiKeyCipher());
        } catch (Exception e) {
            return new TestResult(false, 0, "解密失败（master-key 不一致或密文损坏）：" + e.getMessage());
        }
        int readMs = p.getReadTimeoutMs() != null ? p.getReadTimeoutMs() : 30_000;
        long start = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(p.getConnectTimeoutMs() != null ? p.getConnectTimeoutMs() : 10_000))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(p.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofMillis(readMs))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(testBody(p), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            if (resp.statusCode() >= 400) {
                return new TestResult(false, latency, "HTTP " + resp.statusCode() + ": " + snippet(resp.body()));
            }
            return new TestResult(true, latency, "连通正常（HTTP " + resp.statusCode() + "）");
        } catch (Exception e) {
            return new TestResult(false, System.currentTimeMillis() - start, String.valueOf(e.getMessage()));
        }
    }

    /** 测试请求体：接入行没配默认模型时回退 yaml 全局默认（MiniMax 必须带 model）。 */
    private String testBody(LlmProviderDTO p) {
        String model = p.getModel() == null || p.getModel().isBlank() ? props.model() : p.getModel();
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}";
    }

    /** 单活约束：启用行之外全部停用（平台同一时刻只消费一条接入）。 */
    private void applySingleActive(LlmProviderDTO active) {
        if (active.isEnabled()) {
            providerData.disableAllOthers(active.getId());
        }
    }

    private ProviderVO toVO(LlmProviderDTO p) {
        String masked;
        try {
            masked = mask(cipher.decrypt(p.getApiKeyCipher()));
        } catch (Exception e) {
            masked = "（解密失败）";
        }
        return new ProviderVO(p.getId(), p.getName(), p.getBaseUrl(), p.getModel(),
                p.getConnectTimeoutMs(), p.getReadTimeoutMs(), p.isEnabled(), p.getRemark(), masked);
    }

    private static String mask(String plain) {
        if (plain.length() <= 8) {
            return "******";
        }
        return plain.substring(0, 3) + "******" + plain.substring(plain.length() - 4);
    }

    private LlmProviderDTO require(long id) {
        LlmProviderDTO p = providerData.findById(id);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "接入不存在: " + id);
        }
        return p;
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "名称必填");
        }
        if (name.strip().length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "名称过长（≤64 字）");
        }
    }

    private void requireBaseUrl(String baseUrl) {
        String u = baseUrl.strip();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "baseUrl 必须以 http(s):// 开头");
        }
        if (u.endsWith("/")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "baseUrl 不要以 / 结尾（代码会自动拼 /chat/completions）");
        }
    }

    private static String snippet(String body) {
        if (body == null) return "";
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
