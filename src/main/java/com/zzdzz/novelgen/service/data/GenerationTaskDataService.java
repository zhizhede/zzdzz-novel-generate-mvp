package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.GenerationTaskDTO;

import java.util.List;

/** generation_tasks 数据服务接口（原 GenerationTaskDAO）。 */
public interface GenerationTaskDataService extends IService<GenerationTaskDTO> {

    /** 列表行：联作品表带标题，active（排队/运行中）置顶。 */
    record TaskRow(long id, long novelId, String novelTitle, int fromChapter, int toChapter,
                   String status, int doneChapters, Integer currentChapter, String lastMessage,
                   String createTime, String kind, String payload, int retryCount) {
    }

    long insert(long novelId, int fromChapter, int toChapter, Long submittedBy, String kind, String payload);

    /** 分道认领（契约③）：跳过已有 RUNNING 任务的作品，避免同书并行；并行度由调度器控制。 */
    TaskRow claimNextQueuedForDispatch();

    /** 当前 RUNNING 任务的 DISTINCT 书数（并行度上限判断）。 */
    int countRunningNovels();

    List<TaskRow> list(int limit);

    /** 取队首排队任务并占位为 RUNNING（单 worker 无竞争，条件更新兜底）。 */
    TaskRow claimNextQueued();

    TaskRow findRunning();

    /** 全部 RUNNING 任务（全局急停/分道调度用）。 */
    List<TaskRow> listRunning();

    void updateProgress(long id, int doneChapters, Integer currentChapter, String message);

    void updateStatus(long id, String status, String message);

    /** 取消排队中任务；返回 0 表示已不是 QUEUED（可能已被占位执行）。 */
    int cancelQueued(long id);

    String findStatus(long id);

    int resetInterrupted();

    /** 重启恢复第二段：带取消标记的 RUNNING 任务直接 CANCELED（流 0：取消落库不丢）。 */
    int cancelFlaggedOnRestart();

    /** 用户停止（流 0）：cancel_requested 落库，pipeline 在步骤/场景边界消费。 */
    int requestCancel(long id);

    boolean isCancelRequested(long id);

    boolean isPauseRequested(long id);

    /** 插队暂停后继续：PAUSED→QUEUED，从暂停点下一章续跑。 */
    int resumePaused(long id);

    /** 失败自动重试：RUNNING→QUEUED，从失败章断点续跑；返回 0 表示竞态未生效（已被停/取消）。 */
    int requeueForRetry(long id, int fromChapter, int retryCount, String message);
}
