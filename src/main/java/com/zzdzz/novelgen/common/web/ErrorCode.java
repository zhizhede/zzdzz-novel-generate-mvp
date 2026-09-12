package com.zzdzz.novelgen.common.web;

import org.springframework.http.HttpStatus;

/**
 * 错误码（阿里巴巴 Java 开发手册·黄山版附录）：
 * 字符串类型 5 位 = 错误来源字母 + 4 位编号；全部正常返回 00000；错误码不直接输出给用户（提示由 message 承载）；
 * 错误码编号与 HTTP 状态码无关——HTTP 语义由本枚举 httpStatus 独立承载。
 * 来源：A 用户端 / B 当前系统 / C 第三方服务 / D 中间件 / E 其他；一级宏观 A0001/B0001/C0001/D0001/E0001，
 * 其下细分编号为本项目自定义（大类间步长预留 100）。新场景优先复用语义相近的码，不随意新增。
 */
public enum ErrorCode {

    // ===== A 用户端（参数、登录态、资源、状态冲突）=====
    PARAM_ERROR("A0001", HttpStatus.BAD_REQUEST, "请求参数不合法"),
    LOGIN_FAILED("A0002", HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    NOT_LOGIN("A0003", HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN("A0004", HttpStatus.FORBIDDEN, "无权限执行此操作"),
    NOT_FOUND("A0005", HttpStatus.NOT_FOUND, "资源不存在"),
    STATE_CONFLICT("A0006", HttpStatus.CONFLICT, "当前状态不允许此操作"),
    PIPELINE_BUSY("A0007", HttpStatus.CONFLICT, "已有生成任务在运行"),
    RATE_LIMITED("A0008", HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),

    // ===== B 当前系统 =====
    SYSTEM_ERROR("B0001", HttpStatus.INTERNAL_SERVER_ERROR, "系统执行出错"),
    CONFIG_MISSING("B0002", HttpStatus.INTERNAL_SERVER_ERROR, "服务端配置缺失"),

    // ===== C 第三方服务 =====
    THIRD_PARTY_ERROR("C0001", HttpStatus.BAD_GATEWAY, "第三方服务调用出错"),
    LLM_CALL_FAILED("C0002", HttpStatus.BAD_GATEWAY, "LLM 服务调用失败"),
    LLM_OUTPUT_INVALID("C0003", HttpStatus.BAD_GATEWAY, "LLM 输出反复未通过校验"),

    // ===== D 中间件 =====
    DB_ERROR("D0001", HttpStatus.INTERNAL_SERVER_ERROR, "数据库出错"),

    // ===== E 其他 =====
    OTHER_ERROR("E0001", HttpStatus.INTERNAL_SERVER_ERROR, "其他错误");

    private final String code;
    private final HttpStatus httpStatus;
    private final String description;

    ErrorCode(String code, HttpStatus httpStatus, String description) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.description = description;
    }

    public String code() { return code; }
    public HttpStatus httpStatus() { return httpStatus; }
    public String description() { return description; }
}
