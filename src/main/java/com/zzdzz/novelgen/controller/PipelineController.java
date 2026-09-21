package com.zzdzz.novelgen.controller;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.AuthInterceptor;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.dto.PipelineRunDTO;
import com.zzdzz.novelgen.model.vo.GenerationTaskVO;
import com.zzdzz.novelgen.model.vo.PipelineStatusVO;
import com.zzdzz.novelgen.service.GenerationQueueService;
import com.zzdzz.novelgen.service.PipelineSseService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 管线触发（异步队列）、队列查询/取消、运行状态与 SSE 流。 */
@RestController
@RequestMapping("/api/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final GenerationQueueService queueService;
    private final PipelineSseService sseService;


    /** 入队异步生成，立即返回任务 id；运行中提交不再 409，排队等待。 */
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody PipelineRunDTO dto, HttpServletRequest request) {
        if (dto == null || dto.novel() == null || dto.from() == null || dto.to() == null
                || dto.from() < 1 || dto.to() < dto.from()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "参数不合法：novel/from/to 必填，from ≤ to");
        }
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long taskId = queueService.submit(dto.novel(), dto.from(), dto.to(), userId);
        return Result.success(Map.of("taskId", taskId));
    }

    /** 生成队列：排队/运行中置顶，其后为近期已完成任务。 */
    @GetMapping("/queue")
    public Result<List<GenerationTaskVO>> queue() {
        return Result.success(queueService.list());
    }

    /** 取消任务：仅排队中。运行中停止请用 /stop（流 0 硬中断）。 */
    @PostMapping("/queue/{id}/cancel")
    public Result<Boolean> cancel(@PathVariable long id) {
        boolean accepted = queueService.cancel(id);
        if (!accepted) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务不在排队状态；运行中请使用 /stop");
        }
        return Result.success(true);
    }

    /** 流 0 停止：运行中任务在下一个场景/步骤边界立即终止（章节 INTERRUPTED，已完成产物保留）。 */
    @PostMapping("/queue/{id}/stop")
    public Result<Boolean> stop(@PathVariable long id) {
        boolean accepted = queueService.stop(id);
        if (!accepted) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务不在运行状态");
        }
        return Result.success(true);
    }

    /** 全局急停：终止所有 RUNNING 任务（跑批失控的最后闸门）。 */
    @PostMapping("/queue/stop-all")
    public Result<Integer> stopAll() {
        return Result.success(queueService.stopAll());
    }

    /** 插队暂停后继续（④）：PAUSED 任务从暂停点下一章接跑。 */
    @PostMapping("/queue/{id}/resume")
    public Result<Boolean> resume(@PathVariable long id) {
        boolean accepted = queueService.resume(id);
        if (!accepted) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务不在暂停状态");
        }
        return Result.success(true);
    }

    @GetMapping("/status")
    public Result<PipelineStatusVO> status() {
        return Result.success(queueService.status());
    }

    /** 管线进度 SSE 流：进程事件 + 场景文本块级推送（前端 EventSource 订阅）。 */
    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() {
        return sseService.register();
    }
}
