package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.dao.PipelineEventDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管线事件唯一出口：SSE 实时推送 + pipeline_events 落库 + 统一格式调试日志，一处收口。
 * Stage/Phase 枚举锁定事件名口径（wire 值即前端 SSE 事件名与流水 stage/phase 字段，改动需同步前端）；
 * 各服务不再自持 emit 帮手、不再手拼事件名字符串。
 */
@Service
public class StageLog {

    private static final Logger log = LoggerFactory.getLogger(StageLog.class);

    /** 管线阶段（事件名）：wire 值与前端 SSE 事件名、pipeline_events.stage 逐字一致。 */
    public enum Stage {
        RUN("run", "队列任务"),
        CHAPTER("chapter", "章"),
        OUTLINE("outline", "章纲"),
        SCENE("scene", "场景"),
        SCENE_GATE("gate", "场景门禁"),
        ASSEMBLE("assemble", "拼章"),
        CHAPTER_GATE("chapter_gate", "章级门禁"),
        REVISE("revise", "修订"),
        READER("reader", "读者评审"),
        REVIEW("review", "审校"),
        APPROVE("approve", "审批"),
        DIGEST("digest", "事实账"),
        HEAL("heal", "自愈"),
        VOLUME_PLAN("volume_plan", "卷纲规划"),
        VOLUME_PLAN_REVIEW("volume_plan_review", "卷纲审校"),
        VOLUME_RETRO("volume_retro", "卷级复盘");

        private final String wire;
        private final String label;

        Stage(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        public String wire() { return wire; }
        public String label() { return label; }
    }

    /** 阶段内相位；NONE 不写入 payload（历史无 phase 的事件保持原样）。 */
    public enum Phase {
        START("start"), DONE("done"), DRAFT("draft"), REUSED("reused"), FAILED("failed"),
        ERROR("error"), RETRY("retry"), REPLAN("replan"), PENDING("pending"), REUSE("reuse"),
        REJECTED("rejected"), READER_FIX("reader_fix"), REVIEW_FIX("review_fix"),
        CHAPTER_REPLAN("chapter_replan"), ADOPTED("adopted"),
        CANCELED("canceled"), STOPPED("stopped"), QUEUED("queued"), NONE("");

        private final String wire;

        Phase(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    private final PipelineSseService sse;
    private final PipelineEventDAO eventDAO;

    public StageLog(PipelineSseService sse, PipelineEventDAO eventDAO) {
        this.sse = sse;
        this.eventDAO = eventDAO;
    }

    /** 卷级事件（无章号）。 */
    public void emit(Long novelId, Stage stage, Phase phase, Map<String, Object> extra) {
        emit(novelId, null, stage, phase, extra);
    }

    /** 章级事件：chapterNo 同时进 payload（SSE/流水口径与旧 emit 一致）。 */
    public void emit(Long novelId, Integer chapterNo, Stage stage, Phase phase, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (chapterNo != null) payload.put("chapterNo", chapterNo);
        if (phase != Phase.NONE) payload.put("phase", phase.wire());
        if (extra != null) payload.putAll(extra);
        sse.send(stage.wire(), payload);
        eventDAO.insert(novelId, chapterNo, stage.wire(), phase.wire(), payload);
        log.debug("[novel={}][chapter={}][{}/{}] {}",
                novelId, chapterNo, stage.wire(), phase.wire(), stage.label());
    }
}
