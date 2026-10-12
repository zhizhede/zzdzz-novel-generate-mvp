package com.zzdzz.novelgen.common.web;

import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.llm.LlmException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口。HTTP 语义一律取自 ErrorCode 枚举（无魔法数字）：
 * BizException 按码；LLM 调用失败归 C 类第三方；参数类异常归 A 类；其余兜底 B0001。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {


    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletResponse resp) {
        if (e.errorCode().httpStatus().is5xxServerError()) {
            log.error("业务异常 code={}：{}", e.errorCode().code(), e.getMessage());
        } else {
            log.warn("业务异常 code={}：{}", e.errorCode().code(), e.getMessage());
        }
        resp.setStatus(e.errorCode().httpStatus().value());
        return Result.fail(e.errorCode(), e.getMessage(), e.detail());
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

    /**
     * 数据库约束冲突（唯一键/外键/非空）：原始异常文本是整段 SQL + 表结构 + 文件路径，
     * 直接塞给前端横幅既看不懂也泄露库结构——完整堆栈只进服务端日志，界面只给一句人话。
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public Result<Void> handleIntegrity(org.springframework.dao.DataIntegrityViolationException e,
                                        HttpServletResponse resp) {
        log.error("数据约束冲突（原始明细见下）", e);
        resp.setStatus(ErrorCode.STATE_CONFLICT.httpStatus().value());
        return Result.fail(ErrorCode.STATE_CONFLICT,
                "同名记录已存在或数据违反约束——换个名字再试；反复出现请把服务端日志交给管理员");
    }

    /**
     * 其余数据库类异常（连不上库、SQL 写错、锁超时…）：同样只给一句人话。
     * 这类异常原文包含 JDBC URL/主机端口/驱动栈，比约束冲突更不该外泄；D 类码专指中间件。
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public Result<Void> handleDataAccess(org.springframework.dao.DataAccessException e,
                                         HttpServletResponse resp) {
        log.error("数据库访问失败（原始明细见下）", e);
        resp.setStatus(ErrorCode.DB_ERROR.httpStatus().value());
        return Result.fail(ErrorCode.DB_ERROR, "数据库暂时不可用或执行失败——请稍后重试；明细已记入服务端日志");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletResponse resp) {
        // SSE/长连接客户端断开后，异步响应体的迟到写失败——响应已提交，无事可做，静默即可
        if (e instanceof java.net.SocketTimeoutException
                || e instanceof AsyncRequestNotUsableException
                || "ClientAbortException".equals(e.getClass().getSimpleName())) {
            log.debug("客户端连接已断开，响应写失败忽略：{}", e.getMessage());
            resp.setStatus(200);
            return null;
        }
        log.error("未捕获异常", e);
        resp.setStatus(ErrorCode.SYSTEM_ERROR.httpStatus().value());
        return Result.fail(ErrorCode.SYSTEM_ERROR, "系统错误：" + e.getMessage());
    }
}
