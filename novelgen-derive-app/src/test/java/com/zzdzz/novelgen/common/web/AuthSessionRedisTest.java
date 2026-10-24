package com.zzdzz.novelgen.common.web;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录态 Redis 会话（SessionStore）：JWT 过了还必须在 Redis 查到会话；
 * 吊销后同一 JWT 立即 401；会话键是 token 的 SHA-256（不落原文）。
 */
class AuthSessionRedisTest {

    private final JwtService jwt = new JwtService("test-secret-please-override-0123456789abcdef", 30);
    private final SessionStore store = mock(SessionStore.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwt, store);

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new jakarta.servlet.http.Cookie(JwtService.TOKEN_COOKIE, token));
        return req;
    }

    @Test
    void validJwtWithoutRedisSession_rejected() {
        String token = jwt.issue(1L, "admin", "admin");
        when(store.exists(token)).thenReturn(false);
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(requestWithToken(token), new MockHttpServletResponse(), new Object()));
        assertEquals(ErrorCode.NOT_LOGIN, e.errorCode());
    }

    @Test
    void validJwtWithRedisSession_passes() {
        String token = jwt.issue(1L, "admin", "admin");
        when(store.exists(token)).thenReturn(true);
        MockHttpServletRequest req = requestWithToken(token);
        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
        assertEquals("admin", req.getAttribute(AuthInterceptor.ATTR_USERNAME));
    }

    @Test
    void storeUsesHashedKeyAndDistinctPerToken() {
        String k1 = SessionStore.key("token-a");
        String k2 = SessionStore.key("token-b");
        assertTrue(k1.startsWith(SessionStore.KEY_PREFIX));
        assertFalse(k1.equals(k2), "不同 token 不同键");
        assertFalse(k1.contains("token-a"), "键不落 token 原文");
    }

    @Test
    void expiredJwt_rejectedBeforeSessionCheck() {
        JwtService shortJwt = new JwtService("test-secret-please-override-0123456789abcdef", 0);
        String token = shortJwt.issue(1L, "admin", "admin");
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(requestWithToken(token), new MockHttpServletResponse(), new Object()));
        assertEquals(ErrorCode.NOT_LOGIN, e.errorCode());
        verify(store, never()).exists(org.mockito.ArgumentMatchers.anyString());
    }
}
