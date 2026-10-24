package com.zzdzz.novelgen.model.dto;

/** 修改当前登录用户密码入参（入口层收 DTO）。 */
public record PasswordChangeDTO(String oldPassword, String newPassword) {
}
