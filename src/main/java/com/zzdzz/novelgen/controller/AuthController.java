package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.JwtService;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.dto.LoginDTO;
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

/** 登录 / 登出 / 会话信息。登录态 = HttpOnly JWT Cookie，重启不失效。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto, HttpServletResponse response) {
        AuthService.LoginUser user = authService.authenticate(dto);
        Cookie cookie = new Cookie(JwtService.TOKEN_COOKIE, jwtService.issue(user.id(), user.username(), user.role()));
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) jwtService.ttlSeconds());
        response.addCookie(cookie);
        return Result.ok(new LoginVO(user.username(), user.role()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(JwtService.TOKEN_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<LoginVO> me(HttpServletRequest request) {
        return Result.ok(new LoginVO(
                (String) request.getAttribute("username"),
                (String) request.getAttribute("role")));
    }
}
