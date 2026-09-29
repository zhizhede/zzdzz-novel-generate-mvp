package com.zzdzz.novelgen.model.vo;

/**
 * 文风指纹库查询条件（URL 查询参数绑定，字段全可空 = 不筛）：
 * source 取 SAMPLE/PRESET/BOOK/ALL；confidence 取 LOW/HIGH/ALL；
 * sort 取 TIME_DESC（默认）/TIME_ASC/METRICS_DESC/CHARS_DESC/NAME_ASC；from/to 为 yyyy-MM-dd（含当日）。
 */
public record StyleFingerprintQueryVO(
        String source,
        String keyword,
        String genre,
        String confidence,
        Integer minMetrics,
        Long minChars,
        Long maxChars,
        String from,
        String to,
        String sort) {
}
