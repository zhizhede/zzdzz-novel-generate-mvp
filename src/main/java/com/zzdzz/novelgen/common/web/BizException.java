package com.zzdzz.novelgen.common.web;

/** 业务异常：携带错误码，由 GlobalExceptionHandler 统一转为 Result。 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
