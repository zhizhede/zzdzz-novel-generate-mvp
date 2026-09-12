package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.GenerationTaskDAO;
import com.zzdzz.novelgen.dao.NovelDAO;
import com.zzdzz.novelgen.model.vo.GenerationTaskVO;
import com.zzdzz.novelgen.model.vo.PipelineStatusVO;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 生成队列：DB 承载的异步任务。提交立即返回，后台单 worker 每 2s 认领队首 QUEUED 任务依次执行，
 * 逐章回写进度；RUNNING 任务支持章间协作取消；应用重启时 RUNNING 任务自动重新排队。
 */
@Service
public class GenerationQueueService {

    private static final Logger log = LoggerFactory.getLogger(GenerationQueueService.class);

    private final GenerationTaskDAO taskDAO;
    private final NovelDAO novelDAO;
    private final ChapterPipelineService pipeline;
    private final StageLog stageLog;

    /** 运行中取消请求（taskId 集合），worker 在章间检查。 */
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "queue-worker");
        t.setDaemon(true);
        return t;
    });

    public GenerationQueueService(GenerationTaskDAO taskDAO, NovelDAO novelDAO,
                                  ChapterPipelineService pipeline, StageLog stageLog) {
        this.taskDAO = taskDAO;
        this.novelDAO = novelDAO;
        this.pipeline = pipeline;
        this.stageLog = stageLog;
        worker.scheduleWithFixedDelay(this::pump, 2, 2, TimeUnit.SECONDS);
    }

    /** 重启恢复：上次 RUNNING 的任务随进程消亡，重新排队接续。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        int n = taskDAO.resetInterrupted();
        if (n > 0) {
            log.warn("重启恢复：{} 个运行中任务已重新排队", n);
        }
    }

    /** 入队：立即返回任务 id（异步执行）。 */
    public long submit(String novelTitle, int from, int to, Long userId) {
        Long novelId = novelDAO.findIdByTitle(novelTitle);
        if (novelId == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在: " + novelTitle);
        }
        long id = taskDAO.insert(novelId, from, to, userId);
        emitTask(novelId, id, novelTitle, from, to, StageLog.Phase.QUEUED, null);
        log.info("任务 #{} 入队：{} 第 {}-{} 章", id, novelTitle, from, to);
        return id;
    }

    /** 取消：排队中直接 CANCELED；运行中置协作取消标记，worker 章间生效。返回是否受理。 */
    public boolean cancel(long taskId) {
        String status = taskDAO.findStatus(taskId);
        if ("QUEUED".equals(status)) {
            return taskDAO.cancelQueued(taskId) > 0;
        }
        if ("RUNNING".equals(status)) {
            cancelRequested.add(taskId);
            return true;
        }
        return false;
    }

    public List<GenerationTaskVO> list() {
        return taskDAO.list(30).stream()
                .map(t -> new GenerationTaskVO(t.id(), t.novelTitle(), t.fromChapter(), t.toChapter(),
                        t.status(), t.doneChapters(), t.toChapter() - t.fromChapter() + 1,
                        t.currentChapter(), t.lastMessage(), t.createTime()))
                .toList();
    }

    /** /status 派生：有 RUNNING 任务即运行中。 */
    public PipelineStatusVO status() {
        GenerationTaskDAO.TaskRow running = taskDAO.findRunning();
        if (running == null) {
            return new PipelineStatusVO(false, "空闲");
        }
        String msg = "运行中：" + running.novelTitle() + " 第 " + running.fromChapter() + "–" + running.toChapter()
                + " 章（已完成 " + running.doneChapters() + "）";
        return new PipelineStatusVO(true, msg);
    }

    private void pump() {
        try {
            GenerationTaskDAO.TaskRow task = taskDAO.claimNextQueued();
            if (task != null) {
                runTask(task);
            }
        } catch (Exception e) {
            log.error("队列调度异常：{}", e.getMessage(), e);
        }
    }

    private void runTask(GenerationTaskDAO.TaskRow task) {
        log.info("任务 #{} 开始执行：{} 第 {}-{} 章", task.id(), task.novelTitle(),
                task.fromChapter(), task.toChapter());
        emitTask(task.novelId(), task.id(), task.novelTitle(), task.fromChapter(), task.toChapter(),
                StageLog.Phase.START, null);
        int total = task.toChapter() - task.fromChapter() + 1;
        int passed = pipeline.runChapters(task.novelId(), task.novelTitle(),
                task.fromChapter(), task.toChapter(), new ChapterPipelineService.ProgressSink() {
                    @Override
                    public void onProgress(int doneChapters, int nextChapter, String message) {
                        taskDAO.updateProgress(task.id(), doneChapters, nextChapter, message);
                    }

                    @Override
                    public boolean shouldStop() {
                        return cancelRequested.contains(task.id());
                    }
                });
        StageLog.Phase endPhase;
        if (cancelRequested.remove(task.id())) {
            taskDAO.updateStatus(task.id(), "CANCELED", "运行中取消（已完成 " + passed + " 章）");
            endPhase = StageLog.Phase.CANCELED;
        } else if (passed >= total) {
            taskDAO.updateStatus(task.id(), "DONE", "全部完成（" + passed + " 章）");
            endPhase = StageLog.Phase.DONE;
        } else {
            taskDAO.updateStatus(task.id(), "STOPPED", "第 " + (task.fromChapter() + passed)
                    + " 章失败停止，可断点重跑");
            endPhase = StageLog.Phase.STOPPED;
        }
        emitTask(task.novelId(), task.id(), task.novelTitle(), task.fromChapter(), task.toChapter(),
                endPhase, "完成 " + passed + "/" + total + " 章");
        log.info("任务 #{} 结束：{}（{}/{} 章通过）", task.id(), endPhase.wire(), passed, total);
    }

    /** 队列级事件：入事件流水并推 SSE（工作台日志面板直接可见）。 */
    private void emitTask(Long novelId, long taskId, String novelTitle, int from, int to,
                          StageLog.Phase phase, String message) {
        Map<String, Object> data = message == null
                ? Map.of("taskId", taskId, "novel", novelTitle, "from", from, "to", to)
                : Map.of("taskId", taskId, "novel", novelTitle, "from", from, "to", to,
                        "message", message);
        stageLog.emit(novelId, StageLog.Stage.RUN, phase, data);
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }
}
