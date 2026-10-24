package com.zzdzz.novelgen.model.vo.derive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * run 详情（进度/结果展示）：状态机读数 + ⑤参数快照 + 章/拍进度聚合 + 最新质检判定。
 * 参数列给原文 json（tags/stepStatus），由前端解析；聚合口径见 DeriveQueryService。
 */
public record RunDetailVO(
        long runId,
        String status,
        Integer qcRound,
        Long seed,
        Integer wordsMin,
        Integer wordsMax,
        String tags,
        BigDecimal pace,
        String form,
        String outcome,
        String intent,
        String style,
        Boolean antiRepro,
        Boolean lenCheck,
        Integer b0,
        Integer pMin,
        Integer pMax,
        String stepStatus,
        Long novelId,
        String novelTitle,
        Progress progress,
        QcSummary qc,
        OffsetDateTime createTime,
        OffsetDateTime updateTime
) {
    /** 章/拍进度聚合（章按成品书、拍按 run 统计）。 */
    public record Progress(
            int chapterTotal,
            int chapterWritten,
            int chapterQcPass,
            int beatTotal,
            int beatWritten,
            int beatVerified,
            int beatSkipped
    ) {
    }

    /** 质检汇总：报告轮数 + 最新判定 + 标定读数（calibration 可未回填）。 */
    public record QcSummary(
            int reportCount,
            String latestVerdict,
            Integer latestRound,
            Integer wordsPred,
            Integer wordsActual
    ) {
    }
}
