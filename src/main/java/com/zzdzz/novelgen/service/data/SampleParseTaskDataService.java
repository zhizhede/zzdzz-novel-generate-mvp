package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.SampleParseTaskDTO;

/** 导入小说深度解析任务数据服务。 */
public interface SampleParseTaskDataService extends IService<SampleParseTaskDTO> {

    /** 该样本的活跃任务行（每样本唯一，无则 null）。 */
    SampleParseTaskDTO findAliveBySample(long sampleId);

    /** 复位任务行进入新一轮（QUEUED、清进度、更新 mode）；行不存在则新建。 */
    long resetForRun(long sampleId, String mode);

    /** 状态机抢占：fromStatus → toStatus，0 行=并发冲突由调用方处理。 */
    int casStatus(long taskId, String fromStatus, String toStatus);

    /** 进度回写（done_units/stage，随行 update_time）。 */
    void updateProgress(long taskId, int doneUnits, String stage);

    /** 任务总量落库（进度条分母，runParse 切分完成后立即写）。 */
    void updateTotal(long taskId, int totalUnits);

    /** 终态落盘（status/message/stage 一并写入）。 */
    void finish(long taskId, String status, String stage, String message);
}
