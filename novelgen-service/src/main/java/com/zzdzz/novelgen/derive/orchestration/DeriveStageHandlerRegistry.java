package com.zzdzz.novelgen.derive.orchestration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 正文阶段 handler 注册表；重复注册同一 stage 立即失败。 */
@Component
public class DeriveStageHandlerRegistry {

    private final Map<DeriveStage, DeriveStageHandler> handlers =
            new EnumMap<>(DeriveStage.class);

    /** Spring 启动时收集全部 handler。 */
    @Autowired(required = false)
    public void registerHandlers(List<DeriveStageHandler> values) {
        if (values == null) {
            return;
        }
        values.forEach(this::register);
    }

    /** 注册唯一 stage handler；重复注册立即失败。 */
    public synchronized void register(DeriveStageHandler handler) {
        if (handlers.putIfAbsent(handler.supports(), handler) != null) {
            throw new IllegalStateException("重复注册 stage handler: " + handler.supports());
        }
    }

    /** 读取 stage 唯一 handler；缺失即稳定失败。 */
    public synchronized DeriveStageHandler require(DeriveStage stage) {
        DeriveStageHandler handler = handlers.get(stage);
        if (handler == null) {
            throw new IllegalStateException(StageOutcome.DRAFT_FAILED
                    + "：STAGE_HANDLER_MISSING:" + stage);
        }
        return handler;
    }
}

