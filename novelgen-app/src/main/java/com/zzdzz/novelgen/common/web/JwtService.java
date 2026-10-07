package com.zzdzz.novelgen.common.web;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）：登录态无状态化，服务重启不失效。
 * 密钥从 novelgen.auth.jwt-secret 读取（application-local.yaml 覆盖，不入库），TTL 默认 7 天。
 */
@Component
public class JwtService {

    /** 登录态 Cookie 名（HttpOnly，前端无需感知）。 */
    public static final String TOKEN_COOKIE = "novelgen_token";

    private final SecretKey key;
    private final long ttlMs;

    public JwtService(@Value("${novelgen.auth.jwt-secret}") String secret,
                      @Value("${novelgen.auth.ttl-days:7}") int ttlDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMs = ttlDays * 24L * 3600L * 1000L;
    }

    public String issue(long userId, String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMs))
                .signWith(key)
                .compact();
    }

    /** 校验并解析；过期/伪造/格式错误一律返回 null，由调用方统一按未登录处理。 */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    public long ttlSeconds() {
        return ttlMs / 1000;
    }
}
