package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.UserDAO;
import com.zzdzz.novelgen.model.dto.LoginDTO;
import com.zzdzz.novelgen.model.entity.UserDO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 登录鉴权：无状态 JWT（签发在 AuthController），密码哈希 = sha256(username:password)，与种子 SQL 口径一致。 */
@Service
public class AuthService {

    private final UserDAO userDAO;

    public AuthService(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /** 认证通过的用户（id/username/role），供签发 JWT。 */
    public record LoginUser(long id, String username, String role) {
    }

    public LoginUser authenticate(LoginDTO dto) {
        if (dto == null || isBlank(dto.username()) || isBlank(dto.password())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户名和密码不能为空");
        }
        UserDO user = userDAO.findAliveByUsername(dto.username().trim());
        String hash = sha256(dto.username().trim() + ":" + dto.password());
        if (user == null || !hash.equals(user.passwordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }
        return new LoginUser(user.id(), user.username(), user.role());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
