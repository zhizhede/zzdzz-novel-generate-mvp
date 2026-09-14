package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.GenerationTaskDO;

import java.util.List;

/** generation_tasks 数据服务接口（原 GenerationTaskDAO）。 */
public interface GenerationTaskDataService extends IService<GenerationTaskDO> {

    /** 列表行：联作品表带标题，active（排队/运行中）置顶。 */
    record TaskRow(long id, long novelId, String novelTitle, int fromChapter, int toChapter,
                   String status, int doneChapters, Integer currentChapter, String lastMessage,
                   String createTime) {
    }

    long insert(long novelId, int fromChapter, int toChapter, Long submittedBy);

    List<TaskRow> list(int limit);

    /** 取队首排队任务并占位为 RUNNING（单 worker 无竞争，条件更新兜底）。 */
    TaskRow claimNextQueued();

    TaskRow findRunning();

    void updateProgress(long id, int doneChapters, Integer currentChapter, String message);

    void updateStatus(long id, String status, String message);

    /** 取消排队中任务；返回 0 表示已不是 QUEUED（可能已被占位执行）。 */
    int cancelQueued(long id);

    String findStatus(long id);

    int resetInterrupted();
}
