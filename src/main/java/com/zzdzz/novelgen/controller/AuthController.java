package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.AuthInterceptor;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.dto.LoginDTO;
import com.zzdzz.novelgen.model.vo.LoginVO;
import com.zzdzz.novelgen.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录 / 登出 / 会话信息。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto, HttpSession session) {
        return Result.ok(authService.login(dto, session));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpSession session) {
        session.invalidate();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<LoginVO> me(HttpSession session) {
        return Result.ok(new LoginVO(
                (String) session.getAttribute(AuthInterceptor.SESSION_USERNAME),
                (String) session.getAttribute(AuthInterceptor.SESSION_ROLE)));
    }
}
