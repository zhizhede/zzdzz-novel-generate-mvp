package com.zzdzz.novelgen.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应包装：code="00000" 成功；错误码体系见 {@link ErrorCode}。
 * 通道分工：data 只装成功业务结果；detail 只装失败结构化明细（成功恒 null 且不序列化）。
 * fail 泛型化：失败值可出现在任何 Result&lt;T&gt; 返回位置，T 由上下文推导。
 */
public record Result<T>(String code, String message, T data,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Object detail) {

    /** 手册规约：全部正常时返回 00000。 */
    public static final String OK_CODE = "00000";

    public static <T> Result<T> success(T data) {
        return new Result<>(OK_CODE, "ok", data, null);
    }

    public static Result<Void> success() {
        return new Result<>(OK_CODE, "ok", null, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    /** 失败明细：实际状态、字段名、未过条目等机器可读上下文，message 仍是给人看的人话。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message, Object detail) {
        return new Result<>(errorCode.code(), message, null, detail);
    }
}
