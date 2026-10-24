package com.zzdzz.novelgen.model.vo.derive;

/**
 * 全流程无人值守状态（2026-10-18：一个按钮从当前状态自动推进 ⑥a→⑥b→闸→⑦→⑧ 直到终态；
 * 重试沿用各步默认机制——回炉/闸轮次/质检轮熔断全按既有口径，主控只负责顺序推进与急停）。
 * direct=true（直通免审）：P6 立项闸与 ⑧ 质检的 AI 检测全部跳过直接放行，生成环节照常。
 */
public record AutoVO(
        long runId,
        boolean running,
        boolean stopRequested,
        boolean done,
        String stage,
        int steps,
        String lastError,
        long elapsedMs,
        boolean direct
) {
}
