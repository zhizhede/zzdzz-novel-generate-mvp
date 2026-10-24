package com.zzdzz.novelgen.model.vo.derive;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/** 质检报告行（⑧每轮一行；jsonb 四列解析为 JsonNode，解析失败置 null）。 */
public record QcReportVO(
        long id,
        int roundNo,
        String verdict,
        JsonNode scriptResult,
        JsonNode llmResult,
        JsonNode issues,
        JsonNode repairLog,
        OffsetDateTime createTime
) {
}
