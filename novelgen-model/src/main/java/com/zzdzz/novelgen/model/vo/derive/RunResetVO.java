package com.zzdzz.novelgen.model.vo.derive;

/** 清空 run 的⑥及之后的规划/正文数据结果；⑤采样与焊接配置保留。 */
public record RunResetVO(
        long runId,
        String status,
        int deletedChapters,
        int deletedBeats,
        int deletedReports,
        String message
) {
}
