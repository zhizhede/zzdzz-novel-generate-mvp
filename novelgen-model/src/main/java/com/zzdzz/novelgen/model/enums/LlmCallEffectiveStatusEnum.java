package com.zzdzz.novelgen.model.enums;

/**
 * LLM 调用账本的“有效状态”投影。
 *
 * <p>传输层 status=ok 只代表 HTTP 成功；error_msg 非空时内容仍可能被契约拒绝。
 */
public enum LlmCallEffectiveStatusEnum {
    ACCEPTED,
    CONTENT_REJECTED,
    CALL_FAILED,
    INTERRUPTED;

    /**
     * 根据原始传输状态与错误信息推导有效状态。
     *
     * @param transportStatus llm_call_log.status（ok/error）
     * @param error llm_call_log.error_msg
     * @return 有效状态
     */
    public static LlmCallEffectiveStatusEnum effectiveStatus(String transportStatus, String error) {
        boolean hasError = error != null && !error.isBlank();
        if (hasError && error.toLowerCase(java.util.Locale.ROOT).contains("interrupt")) {
            return INTERRUPTED;
        }
        if ("error".equalsIgnoreCase(transportStatus == null ? "" : transportStatus.strip())) {
            return CALL_FAILED;
        }
        if (hasError) {
            return CONTENT_REJECTED;
        }
        return ACCEPTED;
    }

    /**
     * 前端展示标签。
     *
     * @return 中文标签
     */
    public String label() {
        return switch (this) {
            case ACCEPTED -> "成功";
            case CONTENT_REJECTED -> "内容拒绝";
            case CALL_FAILED -> "调用失败";
            case INTERRUPTED -> "已中断";
        };
    }
}
