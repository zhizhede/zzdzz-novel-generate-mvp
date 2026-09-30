package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.ImportAnalyzeTaskDTO;

/** import_analyze_tasks 数据服务接口。 */
public interface ImportAnalyzeTaskDataService extends IService<ImportAnalyzeTaskDTO> {

    /** 本书的活跃解析任务（无则 null）。 */
    ImportAnalyzeTaskDTO findAliveByNovel(long novelId);

    /**
     * 提交一次解析：存在活跃行则重置它（清空逐步结果），否则新建。返回任务 id。
     * 并发双击时由「撞唯一键后重查复用」兜住（见实现）。
     */
    long resetForRun(long novelId, String stepsJson);

    /** 记录正在跑的步骤键。 */
    void markCurrent(long taskId, String step);

    /** 追加一步的结果并落库。 */
    void appendStep(long taskId, String doneStepsJson);

    /** 终态收口（DONE/FAILED/INTERRUPTED + 汇总消息）。 */
    void finish(long taskId, String status, String message);
}
