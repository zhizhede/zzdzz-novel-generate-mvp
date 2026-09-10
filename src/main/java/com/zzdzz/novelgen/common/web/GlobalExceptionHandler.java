package com.zzdzz.novelgen.common.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常出口：BizException 按码映射 HTTP 状态，其余一律 5000 兜底。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletResponse resp) {
        resp.setStatus(switch (e.code()) {
            case ErrorCode.NOT_LOGIN -> 401;
            case ErrorCode.PIPELINE_BUSY -> 409;
            default -> e.code() >= 5000 ? 500 : 400;
        });
        return Result.fail(e.code(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletResponse resp) {
        log.error("未捕获异常", e);
        resp.setStatus(500);
        return Result.fail(ErrorCode.SYSTEM_ERROR, "系统错误：" + e.getMessage());
    }
}
