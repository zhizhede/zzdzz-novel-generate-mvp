package com.zzdzz.novelgen.model.vo;

/**
 * 登录态出参（web 层出参，只读、不含口令）。
 *
 * <p>2026-10-03 从「既收入又出」的原 LoginVO 拆出：原类字段是 `(username, password)`，
 * 当响应体用时第二个位置塞的其实是 **role**——字段名与内容不符，还把口令字段带进了出参契约。
 * 入参侧改用 {@link com.zzdzz.novelgen.model.dto.LoginDTO}；前端请求体字段名不变（username/password）。
 */
public record LoginVO(String username, String role) {
}
