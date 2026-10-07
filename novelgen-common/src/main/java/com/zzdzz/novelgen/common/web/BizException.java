package com.zzdzz.novelgen.common.web;

/**
 * 业务异常：携带 {@link ErrorCode}（含 HTTP 语义），由 GlobalExceptionHandler 统一转为 Result。
 * message 是面向用户的人话提示；错误码本身不直接给用户看。
 * detail 是失败结构化明细（实际状态/字段名/未过条目），随异常透传到 Result.detail 供前端程序化使用。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object detail;

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object detail) {
        super(message);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object detail() {
        return detail;
    }
}
