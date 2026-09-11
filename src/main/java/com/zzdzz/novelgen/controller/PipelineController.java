package com.zzdzz.novelgen.controller;

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
public class PipelineController {

    private final GenerationQueueService queueService;
    private final PipelineSseService sseService;

    public PipelineController(GenerationQueueService queueService, PipelineSseService sseService) {
        this.queueService = queueService;
        this.sseService = sseService;
    }

    /** 入队异步生成，立即返回任务 id；运行中提交不再 409，排队等待。 */
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody PipelineRunDTO dto, HttpServletRequest request) {
        if (dto == null || dto.novel() == null || dto.from() == null || dto.to() == null
                || dto.from() < 1 || dto.to() < dto.from()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "参数不合法：novel/from/to 必填，from ≤ to");
        }
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long taskId = queueService.submit(dto.novel(), dto.from(), dto.to(), userId);
        return Result.ok(Map.of("taskId", taskId));
    }

    /** 生成队列：排队/运行中置顶，其后为近期已完成任务。 */
    @GetMapping("/queue")
    public Result<List<GenerationTaskVO>> queue() {
        return Result.ok(queueService.list());
    }

    /** 取消任务：排队中直接取消；运行中在下一章边界生效。 */
    @PostMapping("/queue/{id}/cancel")
    public Result<Boolean> cancel(@PathVariable long id) {
        boolean accepted = queueService.cancel(id);
        if (!accepted) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务不在可取消状态（QUEUED/RUNNING）");
        }
        return Result.ok(true);
    }

    @GetMapping("/status")
    public Result<PipelineStatusVO> status() {
        return Result.ok(queueService.status());
    }

    /** 管线进度 SSE 流：进程事件 + 场景文本块级推送（前端 EventSource 订阅）。 */
    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() {
        return sseService.register();
    }
}
