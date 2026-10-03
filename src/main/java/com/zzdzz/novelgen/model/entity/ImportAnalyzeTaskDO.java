package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** ImportAnalyzeTaskDO（导入书籍解析链任务，一本活书一行活跃任务）。 */
@Data
@TableName(value = "import_analyze_tasks")
public class ImportAnalyzeTaskDO extends BaseDO {
    private long novelId;
    /** 勾选并已排序的步骤键 JSON 数组文本。 */
    private String steps;
    /** QUEUED / RUNNING / DONE / FAILED / INTERRUPTED。 */
    private String status;
    private String currentStep;
    /** 逐步结果 JSON 数组文本：[{step,status,message,elapsedMs,counts}]。 */
    private String doneSteps;
    private String message;
}
