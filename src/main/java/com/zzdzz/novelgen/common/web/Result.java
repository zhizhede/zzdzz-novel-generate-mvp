package com.zzdzz.novelgen.common.web;

/** 统一响应包装：code="00000" 成功；错误码体系见 {@link ErrorCode}。 */
public record Result<T>(String code, String message, T data) {

    /** 手册规约：全部正常时返回 00000。 */
    public static final String OK_CODE = "00000";

    public static <T> Result<T> ok(T data) {
        return new Result<>(OK_CODE, "ok", data);
    }

    public static Result<Void> ok() {
        return new Result<>(OK_CODE, "ok", null);
    }

    public static Result<Void> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null);
    }
}
