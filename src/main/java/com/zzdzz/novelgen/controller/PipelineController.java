package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.dto.PipelineRunDTO;
import com.zzdzz.novelgen.model.vo.PipelineStatusVO;
import com.zzdzz.novelgen.service.ChapterPipelineService;
import com.zzdzz.novelgen.service.PipelineSseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管线触发与运行状态（M4 升级 SSE 推送，先用轮询）。 */
@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    private final ChapterPipelineService pipelineService;
    private final PipelineSseService sseService;

    public PipelineController(ChapterPipelineService pipelineService, PipelineSseService sseService) {
        this.pipelineService = pipelineService;
        this.sseService = sseService;
    }

    @PostMapping("/run")
    public Result<Void> run(@RequestBody PipelineRunDTO dto) {
        if (dto == null || dto.novel() == null || dto.from() == null || dto.to() == null
                || dto.from() < 1 || dto.to() < dto.from()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "参数不合法：novel/from/to 必填，from ≤ to");
        }
        if (!pipelineService.tryRunAsync(dto.novel(), dto.from(), dto.to())) {
            throw new BizException(ErrorCode.PIPELINE_BUSY, "已有生成任务在运行");
        }
        return Result.ok();
    }

    @GetMapping("/status")
    public Result<PipelineStatusVO> status() {
        var s = pipelineService.status();
        return Result.ok(new PipelineStatusVO(s.running(), s.lastMessage()));
    }

    /** 管线进度 SSE 流：进程事件 + 场景文本块级推送（前端 EventSource 订阅）。 */
    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() {
        return sseService.register();
    }
}
