package com.zzdzz.novelgen.common.web;

/**
 * 业务异常：携带 {@link ErrorCode}（含 HTTP 语义），由 GlobalExceptionHandler 统一转为 Result。
 * message 是面向用户的人话提示；错误码本身不直接给用户看。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
