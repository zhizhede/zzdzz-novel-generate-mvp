package com.zzdzz.novelgen.common.web;

/**
 * 错误码分段：1xxx 参数错误；2xxx 业务规则；3xxx 管线冲突；5xxx 系统错误。
 */
public final class ErrorCode {

    public static final int PARAM_ERROR = 1000;
    public static final int LOGIN_FAILED = 2001;
    public static final int NOT_LOGIN = 2002;
    public static final int NOT_FOUND = 2004;
    public static final int PIPELINE_BUSY = 3001;
    public static final int SYSTEM_ERROR = 5000;

    private ErrorCode() {
    }
}
