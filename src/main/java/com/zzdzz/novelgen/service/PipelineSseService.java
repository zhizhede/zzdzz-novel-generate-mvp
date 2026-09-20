package com.zzdzz.novelgen.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 管线进度 SSE：把章/场景级事件实时推给前端（进程面板 + 场景文本块级流式）。
 * 无订阅者时静默丢弃；发送失败即摘除该订阅者。
 * 每 20s 推一行注释帧心跳：EventSource 忽略注释行，但代理/网关看到流量不会掐空闲连接。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineSseService {

    /** 心跳间隔：EventSource 忽略注释行，但代理/网关看到流量不掐空闲连接。 */
    private static final long HEARTBEAT_SECONDS = 20;


    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeater =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 心跳调度随 Bean 启动（从构造器迁出，保持 @RequiredArgsConstructor 纯注入）。 */
    @PostConstruct
    void startHeartbeat() {
        heartbeater.scheduleWithFixedDelay(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void beat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                emitters.remove(emitter);
                log.debug("SSE 心跳摘除订阅者：{}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeater.shutdownNow();
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /** stage：outline/scene/gate/assemble/revise/digest/chapter/run，data 为事件负载。 */
    public void send(String stage, Map<String, Object> data) {
        if (emitters.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(data);
        } catch (Exception e) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(stage).data(json));
            } catch (Exception e) {
                emitters.remove(emitter);
                log.debug("SSE 订阅者移除：{}", e.getMessage());
            }
        }
    }
}
