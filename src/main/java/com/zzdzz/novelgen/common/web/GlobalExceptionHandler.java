package com.zzdzz.novelgen.common.web;

import com.zzdzz.novelgen.llm.LlmException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口。HTTP 语义一律取自 ErrorCode 枚举（无魔法数字）：
 * BizException 按码；LLM 调用失败归 C 类第三方；参数类异常归 A 类；其余兜底 B0001。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletResponse resp) {
        if (e.errorCode().httpStatus().is5xxServerError()) {
            log.error("业务异常 code={}：{}", e.errorCode().code(), e.getMessage());
        } else {
            log.warn("业务异常 code={}：{}", e.errorCode().code(), e.getMessage());
        }
        resp.setStatus(e.errorCode().httpStatus().value());
        return Result.fail(e.errorCode(), e.getMessage());
    }

    /** LLM（第三方）调用失败：C 类，保留原始错误信息供溯源。 */
    @ExceptionHandler(LlmException.class)
    public Result<Void> handleLlm(LlmException e, HttpServletResponse resp) {
        log.warn("LLM 调用失败：{}", e.getMessage());
        resp.setStatus(ErrorCode.LLM_CALL_FAILED.httpStatus().value());
        return Result.fail(ErrorCode.LLM_CALL_FAILED, e.getMessage());
    }

    /** 请求体不可读 / 参数类型不匹配 / 静态资源未找到：A 类兜底（防漏网异常被当 500 报）。 */
    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, NoResourceFoundException.class})
    public Result<Void> handleBadRequest(Exception e, HttpServletResponse resp) {
        log.warn("请求不合法：{}", e.getMessage());
        resp.setStatus(ErrorCode.PARAM_ERROR.httpStatus().value());
        return Result.fail(ErrorCode.PARAM_ERROR, "请求不合法：" + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletResponse resp) {
        log.error("未捕获异常", e);
        resp.setStatus(ErrorCode.SYSTEM_ERROR.httpStatus().value());
        return Result.fail(ErrorCode.SYSTEM_ERROR, "系统错误：" + e.getMessage());
    }
}
