package com.zzdzz.novelgen.model.vo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 导入书籍解析链的状态与逐步结果（前端进度面板直接渲染）。 */
public record ImportAnalyzeStatusVO(
        long taskId,
        long novelId,
        /** QUEUED / RUNNING / DONE / FAILED / INTERRUPTED。 */
        String status,
        /** 正在跑的步骤键（RUNNING 时有值）。 */
        String currentStep,
        /** 本次提交的步骤键（按执行顺序）。 */
        List<String> plannedSteps,
        /** plannedSteps 里对「已有内容」选择**跳过**的步骤键（不在其中的＝覆盖重做）。 */
        List<String> skipExistingSteps,
        /** 已完成（含失败/跳过）的步骤结果。 */
        List<StepResultVO> results,
        /** 终态汇总（人话）。 */
        String message,
        OffsetDateTime updateTime) {

    /** 单步结果：status 取 SUCCESS / SKIPPED / FAILED。 */
    public record StepResultVO(String step, String label, String status, String message, long elapsedMs,
                               Map<String, Object> counts) {
    }
}
