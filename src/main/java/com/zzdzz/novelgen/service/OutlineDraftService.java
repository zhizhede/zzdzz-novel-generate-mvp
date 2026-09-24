package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.model.dto.OutlineDraftTaskDTO;
import com.zzdzz.novelgen.model.vo.NovelCreateVO;
import com.zzdzz.novelgen.service.data.OutlineDraftTaskDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 大纲草稿异步执行器：向导点击秒回任务 id，后台按并发度（tuning outline_draft_parallel）消费生成。
 * 入参快照落库，重启时 RUNNING 复位 QUEUED 重放——业务方批量开书时逐本排队、互不阻塞。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutlineDraftService {

    private final OutlineDraftTaskDataService taskData;
    private final NovelService novelService;
    private final TuningService tuning;
    private final ObjectMapper mapper;

    private final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(1, TuningDefaults.OUTLINE_DRAFT_PARALLEL), r -> {
                Thread t = new Thread(r, "outline-draft-worker");
                t.setDaemon(true);
                return t;
            });

    /** 提交生成任务：秒回任务 id（novelId=已落库的草稿书，可为 null 兼容直连 API）。 */
    public long submit(NovelCreateVO vo, Long novelId) {
        String request;
        try {
            request = mapper.writeValueAsString(vo);
        } catch (Exception e) {
            throw new IllegalStateException("大纲任务入参序列化失败", e);
        }
        long taskId = taskData.insertTask(vo.title() == null ? "" : vo.title().strip(), request, novelId);
        pool.submit(() -> safeRun(taskId, vo));
        log.info("大纲草稿任务 #{} 已提交：{}", taskId, vo.title());
        return taskId;
    }

    /** 任务状态与结果（前端轮询）。 */
    public OutlineDraftTaskDTO status(long taskId) {
        return taskData.getById(taskId);
    }

    /** 某书最新一份大纲任务（草稿恢复）。 */
    public OutlineDraftTaskDTO latestByNovel(long novelId) {
        return taskData.latestByNovel(novelId);
    }

    private void safeRun(long taskId, NovelCreateVO vo) {
        if (taskData.casStatus(taskId, "QUEUED", "RUNNING") == 0) {
            log.warn("大纲任务 #{} 抢占失败（非 QUEUED），跳过", taskId);
            return;
        }
        try {
            String outline = novelService.draftOutline(vo);
            taskData.finishDone(taskId, outline);
            log.info("大纲草稿任务 #{} 完成（{} 字）", taskId, outline.length());
        } catch (Exception e) {
            log.error("大纲草稿任务 #{} 失败：{}", taskId, e.getMessage(), e);
            taskData.finishFailed(taskId, e.getMessage() == null ? "生成失败" : e.getMessage());
        }
    }

    /** 重启善后：RUNNING 任务复位 QUEUED 并重放（入参快照在库，结果幂等覆盖）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverRunning() {
        List<OutlineDraftTaskDTO> running = taskData.list(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OutlineDraftTaskDTO>()
                        .eq("status", "RUNNING")
                        .eq("is_deleted", false));
        for (OutlineDraftTaskDTO t : running) {
            if (taskData.casStatus(t.getId(), "RUNNING", "QUEUED") > 0) {
                try {
                    NovelCreateVO vo = mapper.readValue(t.getRequest(), NovelCreateVO.class);
                    pool.submit(() -> safeRun(t.getId(), vo));
                    log.warn("大纲草稿任务 {} 重启复位重放", t.getId());
                } catch (Exception e) {
                    taskData.finishFailed(t.getId(), "重启后入参快照损坏：" + e.getMessage());
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
