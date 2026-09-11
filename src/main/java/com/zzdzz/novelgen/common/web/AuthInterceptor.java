package com.zzdzz.novelgen.common.web;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截：/api/** 除登录接口外要求有效 JWT（HttpOnly Cookie 优先，兼容 Authorization: Bearer）。
 * 无服务端会话状态，后端重启不影响已登录浏览器。
 */
public class AuthInterceptor implements HandlerInterceptor {

    /** 请求属性：拦截器解析通过后写入，供 /api/auth/me 等读取。 */
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_ROLE = "role";

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Claims claims = jwtService.parse(extractToken(request));
        if (claims == null) {
            throw new BizException(ErrorCode.NOT_LOGIN, "未登录或登录已过期");
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
