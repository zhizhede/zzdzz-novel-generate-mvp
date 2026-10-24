package com.zzdzz.novelgen.common.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录会话 Redis 存储（2026-10-16 用户定调：登录态进 Redis，过期 30 天）。
 *
 * <p>机制：JWT 保留（身份+绝对过期），Redis 登记服务端会话——登录时
 * {@code SET novelgen:auth:session:<sha256(token)> → "id|username|role" EX ttl}；
 * 拦截器 JWT 校验通过后必须能在 Redis 查到会话，否则按未登录（可服务端吊销）；
 * 登出 DEL。key 存 token 的 SHA-256（不落原文），value 只存最小身份三元组。
 * Redis 不可用时：登录报中间件错、请求按未登录（fail-closed），由调用方重登。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionStore {

    static final String KEY_PREFIX = "novelgen:auth:session:";

    private final StringRedisTemplate redis;

    /** 登录登记会话；ttlSeconds 与 JWT TTL 同源（novelgen.auth.ttl-days）。 */
    public void save(String token, long userId, String username, String role, long ttlSeconds) {
        String value = userId + "|" + username + "|" + role;
        redis.opsForValue().set(key(token), value, Duration.ofSeconds(ttlSeconds));
    }

    /** 会话是否存在（拦截器在 JWT 校验通过后调用）。Redis 异常按不存在处理（fail-closed）。 */
    public boolean exists(String token) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(token)));
        } catch (Exception e) {
            log.warn("会话查询失败（按未登录处理）：{}", e.getMessage());
            return false;
        }
    }

    /** 登出吊销；不存在/异常静默。 */
    public void delete(String token) {
        try {
            redis.delete(key(token));
        } catch (Exception e) {
            log.warn("会话删除失败：{}", e.getMessage());
        }
    }

    static String key(String token) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(KEY_PREFIX.length() + 64);
            sb.append(KEY_PREFIX);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
