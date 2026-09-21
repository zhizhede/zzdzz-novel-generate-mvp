package com.zzdzz.novelgen.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.GenerationTaskDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.model.enums.TaskKind;
import com.zzdzz.novelgen.model.enums.TaskStatus;
import com.zzdzz.novelgen.model.vo.GenerationTaskVO;
import com.zzdzz.novelgen.model.vo.PipelineStatusVO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ChapterStepDataService;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 生成队列：DB 承载的异步任务。提交立即返回，后台单 worker 每 2s 认领队首 QUEUED 任务依次执行，
 * 逐章回写进度；RUNNING 任务支持章间协作取消；应用重启时 RUNNING 任务自动重新排队。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationQueueService {

    /** 队列列表最多返回行数 */
    private static final int QUEUE_ROW_LIMIT = 30;
    /** 分道调度器扫描间隔（秒） */
    private static final long DISPATCH_INTERVAL_SECONDS = 2;


    private final GenerationTaskDataService taskDAO;
    private final NovelDataService novelData;
    private final ChapterDataService chapterData;
    private final ChapterPipelineService pipeline;
    private final ChapterStepDataService stepData;
    private final StageLog stageLog;
    private final PlanningService planningService;
    private final TuningService tuning;
    private final LlmCallLogDataService llmCallLogData;

    /** 运行中取消请求（taskId 集合），worker 在步骤/场景边界检查。 */
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();

    /** 契约③：每本书一个单线程 worker（书内串行保因果），调度器跨书分道（并行度=max_parallel_novels）。 */
    private final Map<Long, ExecutorService> novelWorkers = new ConcurrentHashMap<>();

    /** 流 0 v2：运行中任务的 worker 线程句柄（停止时中断在飞 LLM 调用，JDK HttpClient 响应中断）。 */
    private final Map<Long, Thread> taskThreads = new ConcurrentHashMap<>();

    private final ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "queue-dispatcher");
        t.setDaemon(true);
        return t;
    });

    /** 分道调度器随 Bean 启动（从构造器迁出，保持 @RequiredArgsConstructor 纯注入）。 */
    @PostConstruct
    void startDispatcher() {
        dispatcher.scheduleWithFixedDelay(this::pump, DISPATCH_INTERVAL_SECONDS, DISPATCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 重启恢复：带取消标记的 RUNNING 任务直接 CANCELED（流 0 取消落库不丢），其余重新排队接续。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        int canceled = taskDAO.cancelFlaggedOnRestart();
        int requeued = taskDAO.resetInterrupted();
        if (canceled > 0 || requeued > 0) {
            log.warn("重启恢复：{} 个任务因已请求停止被取消，{} 个运行中任务重新排队", canceled, requeued);
        }
    }

    /** 入队：立即返回任务 id（异步执行）。 */
    public long submit(String novelTitle, int from, int to, Long userId) {
        Long novelId = novelData.findIdByTitle(novelTitle);
        if (novelId == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在: " + novelTitle);
        }
        String title = novelData.getById(novelId).getTitle();
        return submitById(novelId, title, from, to, userId);
    }

    /** 队列动作受理结果：已受理 / 任务不存在 / 状态不符（附实际状态，供如实报错与 detail 透出）。 */
    public record ActionOutcome(boolean accepted, Denial denial, String actualStatus) {

        public enum Denial { NONE, NOT_FOUND, WRONG_STATE }

        static ActionOutcome accept() {
            return new ActionOutcome(true, Denial.NONE, null);
        }

        static ActionOutcome notFound() {
            return new ActionOutcome(false, Denial.NOT_FOUND, null);
        }

        static ActionOutcome wrongState(String actual) {
            return new ActionOutcome(false, Denial.WRONG_STATE, actual);
        }
    }

    /** 取消：仅排队中；运行中请用 {@link #stop}（流 0 硬停止）。 */
    public ActionOutcome cancel(long taskId) {
        String status = taskDAO.findStatus(taskId);
        if (status == null) {
            return ActionOutcome.notFound();
        }
        if (!TaskStatus.QUEUED.is(status)) {
            return ActionOutcome.wrongState(status);
        }
        return taskDAO.cancelQueued(taskId) > 0 ? ActionOutcome.accept() : ActionOutcome.wrongState(status);
    }

    /** 流 0 v2 硬停止：落库取消标记 + 中断在飞调用（JDK HttpClient 响应中断，毫秒级生效）。
     * 在飞调用的 token 已花、结果丢弃；已完成场景/正文保留。 */
    public ActionOutcome stop(long taskId) {
        String status = taskDAO.findStatus(taskId);
        if (status == null) {
            return ActionOutcome.notFound();
        }
        if (!TaskStatus.RUNNING.is(status)) {
            return ActionOutcome.wrongState(status);
        }
        taskDAO.requestCancel(taskId);
        cancelRequested.add(taskId);
        Thread worker = taskThreads.get(taskId);
        if (worker != null) {
            worker.interrupt();
        }
        return ActionOutcome.accept();
    }

    /** 全局急停：终止所有 RUNNING 任务（跑批失控的最后闸门）。 */
    public int stopAll() {
        int n = 0;
        for (GenerationTaskDataService.TaskRow t : taskDAO.listRunning()) {
            if (stop(t.id()).accepted()) {
                n++;
            }
        }
        return n;
    }

    /** 按作品 id 入队（打回/否决重排队用，避免按标题反查）。 */
    public long submitById(long novelId, String novelTitle, int from, int to, Long userId) {
        return enqueue(novelId, novelTitle, from, to, userId, TaskKind.CHAPTERS.wire(), null);
    }

    /** ⑤ 任务化：卷纲自动规划入队（原同步 2-10 分钟 HTTP）。 */
    public long submitPlan(long novelId, String novelTitle, int volNo, int from, Integer to,
                           String seedOutline, Long userId) {
        String payload;
        try {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("volNo", volNo);
            m.put("from", from);
            if (to != null) m.put("to", to);
            if (seedOutline != null) m.put("seedOutline", seedOutline);
            payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            payload = null;
        }
        return enqueue(novelId, novelTitle, from, to == null ? from : to, userId, TaskKind.PLAN.wire(), payload);
    }

    private long enqueue(long novelId, String novelTitle, int from, int to, Long userId, String kind, String payload) {
        long id = taskDAO.insert(novelId, from, to, userId, kind, payload);
        emitTask(novelId, id, novelTitle, from, to, StageLog.Phase.QUEUED, null);
        log.info("任务 #{} 入队（{}）：{} 第 {}-{} 章", id, kind, novelTitle, from, to);
        return id;
    }

    public List<GenerationTaskVO> list() {
        return taskDAO.list(QUEUE_ROW_LIMIT).stream()
                .map(t -> {
                    String currentStep = null;
                    Long chapterTokens = null;
                    if (TaskStatus.RUNNING.is(t.status())) {
                        currentStep = TaskKind.PLAN.is(t.kind()) ? "卷纲规划中"
                                : currentStepLabel(t.novelId(), t.currentChapter());
                        if (t.currentChapter() != null) {
                            chapterTokens = chapterTokens(t.novelId(), t.currentChapter());
                        }
                    }
                    return new GenerationTaskVO(t.id(), t.novelTitle(),
                            t.kind() == null ? TaskKind.CHAPTERS.wire() : t.kind(),
                            t.fromChapter(), t.toChapter(),
                            t.status(), t.doneChapters(), t.toChapter() - t.fromChapter() + 1,
                            t.currentChapter(), t.lastMessage(), t.createTime(), currentStep, chapterTokens);
                })
                .toList();
    }

    /** 流 B：当前步骤中文标签（读 chapter_steps 最新 RUNNING 行）。 */
    private String currentStepLabel(long novelId, Integer currentChapter) {
        if (currentChapter == null) {
            return "准备中";
        }
        return stepData.latestRunningByNovelAndChapterNo(novelId, currentChapter)
                .map(r -> {
                    String sub = r.getSubKey() == null ? "" : r.getSubKey();
                    return switch (r.getStep()) {
                        case "OUTLINE" -> "章纲生成中";
                        case "SCENE" -> sub.isEmpty() ? "场景生成中（检索/生成）" : "场景 " + sub + "（检索/生成）";
                        case "ASSEMBLE" -> "拼章+章级门禁";
                        case "READER" -> "读者评审中";
                        case "AI_REVIEW" -> "AI 审校中";
                        case "APPROVE" -> "等待审批";
                        case "DIGEST" -> "总结（digest）中";
                        default -> r.getStep();
                    };
                })
                .orElse("准备中");
    }

    private Long chapterTokens(long novelId, int chapterNo) {
        try {
            return chapterData.find(novelId, chapterNo)
                    .map(ch -> llmCallLogData.totalsBy(null, ch.getId()).totalTokens())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前 RUNNING 任务上用户请求的终止（流 0 读模型，工作台「终止中…」提示用）。 */
    public boolean isStopRequested(long taskId) {
        return taskDAO.isCancelRequested(taskId);
    }

    /** /status 派生：有 RUNNING 任务即运行中。 */
    public PipelineStatusVO status() {
        GenerationTaskDataService.TaskRow running = taskDAO.findRunning();
        if (running == null) {
            return new PipelineStatusVO(false, "空闲");
        }
        String msg = "运行中：" + running.novelTitle() + " 第 " + running.fromChapter() + "–" + running.toChapter()
                + " 章（已完成 " + running.doneChapters() + "）";
        return new PipelineStatusVO(true, msg);
    }

    private void pump() {
        try {
            int maxParallel = Math.max(1, tuning.i("max_parallel_novels", TuningDefaults.MAX_PARALLEL_NOVELS));
            if (taskDAO.countRunningNovels() >= maxParallel) {
                return; // 并行度已满，等下一轮
            }
            GenerationTaskDataService.TaskRow task = taskDAO.claimNextQueuedForDispatch();
            if (task == null) {
                return;
            }
            ExecutorService lane = novelWorkers.computeIfAbsent(task.novelId(), k ->
                    Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "novel-worker-" + k);
                        t.setDaemon(true);
                        return t;
                    }));
            lane.submit(() -> {
                try {
                    runTask(task);
                } catch (Exception e) {
                    log.error("任务 #{} 执行异常：{}", task.id(), e.getMessage(), e);
                    taskDAO.updateStatus(task.id(), TaskStatus.STOPPED.wire(), "执行异常：" + e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("队列调度异常：{}", e.getMessage(), e);
        }
    }

    private void runTask(GenerationTaskDataService.TaskRow task) {
        log.info("任务 #{} 开始执行：{} 第 {}-{} 章", task.id(), task.novelTitle(),
                task.fromChapter(), task.toChapter());
        emitTask(task.novelId(), task.id(), task.novelTitle(), task.fromChapter(), task.toChapter(),
                StageLog.Phase.START, null);
        taskThreads.put(task.id(), Thread.currentThread());

        // ⑤ 任务化：卷纲自动规划走队列（原同步 2-10 分钟 HTTP）
        if (TaskKind.PLAN.is(task.kind())) {
            try {
                runPlanTask(task);
            } finally {
                taskThreads.remove(task.id());
                Thread.interrupted();
            }
            return;
        }

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

                    @Override
                    public boolean pauseRequested() {
                        return taskDAO.isPauseRequested(task.id());
                    }

                    @Override
                    public void onPauseHit() {
                        if (task.currentChapter() != null && task.currentChapter() >= task.toChapter()) {
                            taskDAO.updateStatus(task.id(), TaskStatus.DONE.wire(), "全部完成（末章后暂停点，直接收尾）");
                        } else {
                            taskDAO.updateStatus(task.id(), TaskStatus.PAUSED.wire(),
                                    "已暂停（第 " + task.currentChapter() + " 章后，点继续接跑）");
                        }
                    }
                });
        StageLog.Phase endPhase;
        if (taskDAO.isCancelRequested(task.id())) {
            // 流 0：用户硬停——章节已标 INTERRUPTED，任务如实记终止
            taskDAO.updateStatus(task.id(), TaskStatus.INTERRUPTED.wire(), "用户终止（已完成 " + passed + " 章）");
            endPhase = StageLog.Phase.STOPPED;
        } else if (TaskStatus.PAUSED.is(taskDAO.findStatus(task.id()))) {
            // 插队暂停：保留任务行等 [继续]
            endPhase = StageLog.Phase.NONE;
        } else if (cancelRequested.remove(task.id())) {
            taskDAO.updateStatus(task.id(), TaskStatus.CANCELED.wire(), "运行中取消（已完成 " + passed + " 章）");
            endPhase = StageLog.Phase.CANCELED;
        } else if (passed >= total) {
            taskDAO.updateStatus(task.id(), TaskStatus.DONE.wire(), "全部完成（" + passed + " 章）");
            endPhase = StageLog.Phase.DONE;
        } else {
            taskDAO.updateStatus(task.id(), TaskStatus.STOPPED.wire(), "第 " + (task.fromChapter() + passed)
                    + " 章失败停止，可断点重跑");
            endPhase = StageLog.Phase.STOPPED;
        }
        taskThreads.remove(task.id());
        Thread.interrupted();
        emitTask(task.novelId(), task.id(), task.novelTitle(), task.fromChapter(), task.toChapter(),
                endPhase, "完成 " + passed + "/" + total + " 章");
        log.info("任务 #{} 结束：{}（{}/{} 章通过）", task.id(), endPhase.wire(), passed, total);
    }

    /** ⑤ 任务化：卷纲自动规划在 worker 内执行（同步 2-10 分钟的 HTTP 调用迁入队列）。 */
    private void runPlanTask(GenerationTaskDataService.TaskRow task) {
        Integer volNo = null, from = null, to = null;
        String seed = null;
        try {
            if (task.payload() != null && !task.payload().isBlank()) {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(task.payload());
                volNo = node.path("volNo").isNumber() ? node.path("volNo").asInt() : null;
                from = node.path("from").isNumber() ? node.path("from").asInt() : null;
                to = node.path("to").isNumber() ? node.path("to").asInt() : null;
                seed = node.path("seedOutline").isTextual() ? node.path("seedOutline").asText() : null;
            }
        } catch (Exception ignore) {
            // payload 缺失由下方兜底校验报错
        }
        try {
            if (volNo == null || from == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "PLAN 任务缺 volNo/from");
            }
            planningService.autoPlan(task.novelId(), volNo, from, to, seed);
            taskDAO.updateStatus(task.id(), TaskStatus.DONE.wire(), "卷纲规划完成并落库");
            emitTask(task.novelId(), task.id(), task.novelTitle(), task.fromChapter(), task.toChapter(),
                    StageLog.Phase.DONE, "卷纲规划完成");
        } catch (Exception e) {
            taskDAO.updateStatus(task.id(), TaskStatus.STOPPED.wire(), "卷纲规划失败：" + e.getMessage());
            emitTask(task.novelId(), task.id(), task.novelTitle(), task.fromChapter(), task.toChapter(),
                    StageLog.Phase.STOPPED, "卷纲规划失败：" + e.getMessage());
        }
    }

    /** 插队暂停后继续（④）：PAUSED → QUEUED，从暂停点下一章接跑。 */
    public ActionOutcome resume(long taskId) {
        String status = taskDAO.findStatus(taskId);
        if (status == null) {
            return ActionOutcome.notFound();
        }
        if (!TaskStatus.PAUSED.is(status)) {
            return ActionOutcome.wrongState(status);
        }
        return taskDAO.resumePaused(taskId) > 0 ? ActionOutcome.accept() : ActionOutcome.wrongState(status);
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
        dispatcher.shutdownNow();
        novelWorkers.values().forEach(ExecutorService::shutdownNow);
    }
}
