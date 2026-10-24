package com.zzdzz.novelgen.common.web;

import lombok.RequiredArgsConstructor;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截：/api/** 除登录接口外要求有效 JWT（HttpOnly Cookie 优先，兼容 Authorization: Bearer）
 * **且 Redis 会话存在**（2026-10-16：服务端可吊销，过期=登录起 30 天，见 SessionStore）。
 */
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 请求属性：拦截器解析通过后写入，供 /api/auth/me 等读取。 */
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_ROLE = "role";

    private final JwtService jwtService;
    private final SessionStore sessionStore;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = extractToken(request);
        Claims claims = jwtService.parse(token);
        if (claims == null) {
            throw new BizException(ErrorCode.NOT_LOGIN, "未登录或登录已过期");
        }
        if (!sessionStore.exists(token)) {
            throw new BizException(ErrorCode.NOT_LOGIN, "登录会话已过期或已登出，请重新登录");
        }
        request.setAttribute(ATTR_USER_ID, Long.valueOf(claims.getSubject()));
        request.setAttribute(ATTR_USERNAME, claims.get("username", String.class));
        request.setAttribute(ATTR_ROLE, claims.get("role", String.class));
        return true;
    }

    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtService.TOKEN_COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
