package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.UserDataService;
import com.zzdzz.novelgen.model.dto.LoginDTO;
import com.zzdzz.novelgen.model.entity.UserDO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 登录鉴权：无状态 JWT（签发在 AuthController），密码哈希 = sha256(username:password)，与种子 SQL 口径一致。 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserDataService userData;


    /** 认证通过的用户（id/username/role），供签发 JWT。 */
    public record LoginUser(long id, String username, String role) {
    }

    public LoginUser authenticate(LoginDTO dto) {
        if (dto == null || isBlank(dto.username()) || isBlank(dto.password())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户名和密码不能为空");
        }
        UserDO user = userData.findAliveByUsername(dto.username().trim());
        String hash = sha256(dto.username().trim() + ":" + dto.password());
        if (user == null || !hash.equals(user.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }
        return new LoginUser(user.getId(), user.getUsername(), user.getRole());
    }

    /** 改当前用户密码：旧密按登录口径校验，新密 ≥6 位且不与旧密相同；哈希仍走 sha256(username:password)。 */
    public void changePassword(long userId, com.zzdzz.novelgen.model.dto.PasswordChangeDTO dto) {
        if (dto == null || isBlank(dto.oldPassword()) || isBlank(dto.newPassword())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "旧密码与新密码均不能为空");
        }
        String neu = dto.newPassword();
        if (neu.length() < 6) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新密码至少 6 位");
        }
        if (neu.equals(dto.oldPassword())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新密码不能与旧密码相同");
        }
        UserDO user = userData.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + userId);
        }
        // 旧密校验复用登录口径（用户名+旧密全匹配）
        if (!sha256(user.getUsername() + ":" + dto.oldPassword()).equals(user.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "旧密码不正确");
        }
        userData.updatePasswordHash(userId, sha256(user.getUsername() + ":" + neu));
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
