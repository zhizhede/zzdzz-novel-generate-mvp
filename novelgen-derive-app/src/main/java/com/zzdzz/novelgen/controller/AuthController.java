package com.zzdzz.novelgen.controller;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.AuthInterceptor;
import com.zzdzz.novelgen.common.web.JwtService;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.common.web.SessionStore;
import com.zzdzz.novelgen.model.dto.LoginDTO;
import com.zzdzz.novelgen.model.dto.PasswordChangeDTO;
import com.zzdzz.novelgen.model.vo.LoginVO;
import com.zzdzz.novelgen.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录 / 登出 / 会话信息。登录态 = HttpOnly JWT Cookie + Redis 会话登记（30 天，服务端可吊销）。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final SessionStore sessionStore;


    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto, HttpServletResponse response) {
        AuthService.LoginUser user = authService.authenticate(dto);
        String token = jwtService.issue(user.id(), user.username(), user.role());
        sessionStore.save(token, user.id(), user.username(), user.role(), jwtService.ttlSeconds());
        Cookie cookie = new Cookie(JwtService.TOKEN_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) jwtService.ttlSeconds());
        response.addCookie(cookie);
        return Result.success(new LoginVO(user.username(), user.role()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = extractToken(request);
        if (token != null && !token.isBlank()) {
            sessionStore.delete(token);
        }
        Cookie cookie = new Cookie(JwtService.TOKEN_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return Result.success();
    }

    @GetMapping("/me")
    public Result<LoginVO> me(HttpServletRequest request) {
        return Result.success(new LoginVO(
                (String) request.getAttribute("username"),
                (String) request.getAttribute("role")));
    }

    /** 修改当前登录用户密码（旧密按登录口径校验；当前会话保持，其余会话靠 Redis 30 天自然过期）。 */
    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody PasswordChangeDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        authService.changePassword(userId, dto);
        return Result.success();
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
