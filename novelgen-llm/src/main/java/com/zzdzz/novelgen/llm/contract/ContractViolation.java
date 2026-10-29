package com.zzdzz.novelgen.llm.contract;

/**
 * 跨节点共享的结构化契约违规信息。
 *
 * @param code 稳定错误码
 * @param path 出错字段路径
 * @param message 人类可读说明
 * @param expected 期望值或约束
 * @param actual 实际值
 */
public record ContractViolation(
        String code,
        String path,
        String message,
        String expected,
        String actual) {

    /**
     * 生成可直接反馈给模型的单行修订说明。
     *
     * @return 固定格式的反馈行
     */
    public String feedbackLine() {
        return "[" + code + "] path=" + path
                + " expected=" + expected
                + " actual=" + actual
                + "：" + message;
    }
}
