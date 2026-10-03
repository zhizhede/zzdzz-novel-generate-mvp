package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmProperties;
import com.zzdzz.novelgen.llm.LlmRole;
import com.zzdzz.novelgen.llm.SecretCipher;
import com.zzdzz.novelgen.model.entity.LlmProviderDO;
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
 * 永不回传明文。同一用途（role=chat/embedding）内只留一条启用行（启用新行自动停用同用途旧行）；
 * 运行时经 LlmProviderResolver.resolve(role) 消费。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmProviderService {

    /** 列表/详情行：key 永远只回掩码。 */
    public record ProviderVO(Long id, String name, String baseUrl, String model, String role,
                             Integer connectTimeoutMs, Integer readTimeoutMs,
                             boolean enabled, String remark, String keyMasked) {}

    public record TestResult(boolean ok, long latencyMs, String message) {}

    private final LlmProviderDataService providerData;
    private final SecretCipher cipher;
    private final LlmProperties props;

    public List<ProviderVO> list() {
        return providerData.listAll().stream().map(this::toVO).toList();
    }

    public void create(String name, String baseUrl, String apiKey, String model, String role,
                       Integer connectTimeoutMs, Integer readTimeoutMs, boolean enabled, String remark) {
        requireName(name);
        if (providerData.findByName(name.strip()) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "同名接入已存在：" + name.strip());
        }
        requireBaseUrl(baseUrl);
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "API key 必填");
        }
        LlmRole roleEnum = requireRole(role);
        LlmProviderDO p = new LlmProviderDO();
        p.setName(name.strip());
        p.setBaseUrl(baseUrl.strip());
        p.setApiKeyCipher(cipher.encrypt(apiKey.strip()));
        p.setModel(model == null || model.isBlank() ? null : model.strip());
        p.setRole(roleEnum.wire());
        p.setConnectTimeoutMs(connectTimeoutMs);
        p.setReadTimeoutMs(readTimeoutMs);
        p.setEnabled(enabled);
        p.setRemark(remark == null ? "" : remark.strip());
        providerData.save(p);
        applySingleActive(p);
        log.info("LLM 接入已创建 id={} name={} role={}（key 已加密落库，明文不再留存）",
                p.getId(), p.getName(), p.getRole());
    }

    /** 更新：apiKey 留空 = 保留原密文（掩码口径下前端不回传明文）。 */
    public void update(long id, String name, String baseUrl, String apiKey, String model, String role,
                       Integer connectTimeoutMs, Integer readTimeoutMs, Boolean enabled, String remark) {
        LlmProviderDO p = require(id);
        if (name != null && !name.isBlank()) {
            String n = name.strip();
            LlmProviderDO same = providerData.findByName(n);
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
        String oldRole = p.getRole();
        if (role != null && !role.isBlank()) {
            p.setRole(requireRole(role).wire());
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
        if (oldRole != null && !oldRole.equals(p.getRole())) {
            // 改用途可能让原用途失去启用行（该用途随后回退 yaml 兜底），必须留痕便于排查
            log.warn("LLM 接入 id={} 用途变更 {}→{}（原用途若无其它启用行将回退 yaml 兜底）",
                    id, oldRole, p.getRole());
        }
        log.info("LLM 接入已更新 id={} enabled={} role={}", id, p.isEnabled(), p.getRole());
    }

    public void delete(long id) {
        require(id);
        providerData.delete(id);
        log.info("LLM 接入已删除 id={}", id);
    }

    /**
     * 连通性测试：按用途打最小请求——会话行走 OpenAI 兼容 /chat/completions（max_tokens=1），
     * 向量化行走 MiniMax 私有 /embeddings（texts=["ping"], type=query，判 base_resp.status_code==0）。
     * 独立 HTTP 不走 MiniMaxClient——被测对象就是它自己的连接要素（含未启用的行）。
     */
    public TestResult test(long id) {
        LlmProviderDO p = require(id);
        String key;
        try {
            key = cipher.decrypt(p.getApiKeyCipher());
        } catch (Exception e) {
            return new TestResult(false, 0, "解密失败（master-key 不一致或密文损坏）：" + e.getMessage());
        }
        int readMs = p.getReadTimeoutMs() != null ? p.getReadTimeoutMs() : 30_000;
        boolean embedding = LlmRole.EMBEDDING.wire().equals(p.getRole());
        long start = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(p.getConnectTimeoutMs() != null ? p.getConnectTimeoutMs() : 10_000))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(p.getBaseUrl() + (embedding ? "/embeddings" : "/chat/completions")))
                    .timeout(Duration.ofMillis(readMs))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(testBody(p, embedding), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            if (resp.statusCode() >= 400) {
                return new TestResult(false, latency, "HTTP " + resp.statusCode() + ": " + snippet(resp.body()));
            }
            if (embedding) {
                // MiniMax 业务失败是 HTTP 200 + base_resp.status_code≠0：不判这一层会把坏 key 当连通。
                // 必须真解析——压测坑：按子串找 "status_code": 0（带空格）会漏掉紧凑 JSON 的成功响应。
                try {
                    JsonNode root = new ObjectMapper().readTree(resp.body());
                    if (root.path("base_resp").path("status_code").asInt(0) != 0) {
                        return new TestResult(false, latency, "向量化业务失败: " + snippet(resp.body()));
                    }
                } catch (Exception parseError) {
                    return new TestResult(false, latency, "向量化响应解析失败: " + snippet(resp.body()));
                }
            }
            return new TestResult(true, latency, "连通正常（HTTP " + resp.statusCode() + "）");
        } catch (Exception e) {
            return new TestResult(false, System.currentTimeMillis() - start, String.valueOf(e.getMessage()));
        }
    }

    /** 测试请求体：会话带 model（行内没配则回退 yaml 全局默认）；向量化用 MiniMax 私有协议形状。 */
    private String testBody(LlmProviderDO p, boolean embedding) {
        String model = p.getModel() == null || p.getModel().isBlank()
                ? (embedding ? "embo-01" : props.model())
                : p.getModel();
        if (embedding) {
            return "{\"model\":\"" + model + "\",\"texts\":[\"ping\"],\"type\":\"query\"}";
        }
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}";
    }

    /** 单活约束（按用途）：同 role 内只留一条启用行——会话与向量化互不影响。 */
    private void applySingleActive(LlmProviderDO active) {
        if (active.isEnabled()) {
            providerData.disableAllOthersInRole(active.getId(), active.getRole());
        }
    }

    private ProviderVO toVO(LlmProviderDO p) {
        String masked;
        try {
            masked = mask(cipher.decrypt(p.getApiKeyCipher()));
        } catch (Exception e) {
            masked = "（解密失败）";
        }
        return new ProviderVO(p.getId(), p.getName(), p.getBaseUrl(), p.getModel(), p.getRole(),
                p.getConnectTimeoutMs(), p.getReadTimeoutMs(), p.isEnabled(), p.getRemark(), masked);
    }

    private static String mask(String plain) {
        if (plain.length() <= 8) {
            return "******";
        }
        return plain.substring(0, 3) + "******" + plain.substring(plain.length() - 4);
    }

    private LlmProviderDO require(long id) {
        LlmProviderDO p = providerData.findById(id);
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
            throw new BizException(ErrorCode.PARAM_ERROR, "baseUrl 不要以 / 结尾（代码会自动拼 /chat/completions 或 /embeddings）");
        }
        // 粘整条端点进来的话会拼成 /v1/chat/completions/chat/completions → 404，且错误只在调用时才暴露
        if (u.contains("/chat/completions") || u.contains("/embeddings") || u.contains("/completions")) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "baseUrl 只填服务根路径（如 https://api.deepseek.com/v1），不要带 /chat/completions 或 /embeddings");
        }
    }

    /** 用途解析：非法值明确报错（不静默当 chat，避免选错用途把向量化行踢下线）。 */
    private static LlmRole requireRole(String role) {
        LlmRole parsed = LlmRole.of(role);
        if (parsed == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用途非法（只能是 chat 或 embedding）：" + role);
        }
        return parsed;
    }

    private static String snippet(String body) {
        if (body == null) return "";
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
