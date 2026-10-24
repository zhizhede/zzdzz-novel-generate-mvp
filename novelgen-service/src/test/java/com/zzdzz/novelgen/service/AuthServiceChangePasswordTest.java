package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.model.dto.PasswordChangeDTO;
import com.zzdzz.novelgen.model.entity.UserDO;
import com.zzdzz.novelgen.service.data.UserDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 改密（POST /api/auth/password）：旧密按登录口径 sha256(username:password) 校验，
 * 新密 ≥6 位且不与旧密相同。金标哈希 admin:admin123 与线上 users 表实测一致（2026-10-17）。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceChangePasswordTest {

    /** sha256("admin:admin123")——derive_gen 线上库同值实测。 */
    private static final String ADMIN_OLD_HASH =
            "bf6b5bdb74c79ece9fc0ad0ac9fb0359f9555d4f35a83b2e6ec69ae99e09603d";

    @Mock
    private UserDataService userData;

    @InjectMocks
    private AuthService authService;

    private static UserDO admin() {
        UserDO u = new UserDO();
        u.setId(7L);
        u.setUsername("admin");
        u.setPasswordHash(ADMIN_OLD_HASH);
        return u;
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void successUpdatesHashWithUsernameSalt() {
        when(userData.getById(7L)).thenReturn(admin());
        authService.changePassword(7L, new PasswordChangeDTO("admin123", "new-pass-1"));
        verify(userData).updatePasswordHash(7L, sha256Hex("admin:new-pass-1"));
    }

    @Test
    void wrongOldPasswordRejected() {
        when(userData.getById(7L)).thenReturn(admin());
        assertThatThrownBy(() -> authService.changePassword(7L, new PasswordChangeDTO("wrong-old", "new-pass-1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("旧密码不正确");
    }

    @Test
    void blankAndShortAndSameRejected() {
        assertThatThrownBy(() -> authService.changePassword(7L, new PasswordChangeDTO("", "new-pass-1")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> authService.changePassword(7L, new PasswordChangeDTO("admin123", "12345")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少 6 位");
        assertThatThrownBy(() -> authService.changePassword(7L, new PasswordChangeDTO("admin123", "admin123")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能与旧密码相同");
    }

    @Test
    void unknownUserRejected() {
        when(userData.getById(99L)).thenReturn(null);
        assertThatThrownBy(() -> authService.changePassword(99L, new PasswordChangeDTO("admin123", "new-pass-1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户不存在");
    }
}
