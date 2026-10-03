package com.zzdzz.novelgen.model.dto;

/**
 * 书籍管理列表查询条件（URL 查询参数绑定，字段全可空 = 不筛）：
 * sourceType 取 IMPORTED/DERIVED/ORIGINAL/ALL；autoContinue 取 ON/OFF/ALL；
 * sort 取 TIME_DESC（默认）/TIME_ASC/CHAPTERS_DESC/TITLE_ASC；from/to 为 yyyy-MM-dd（含当日）。
 */
public record NovelQueryDTO(
        String keyword,
        String sourceType,
        String status,
        String approvalMode,
        String autoContinue,
        Integer minChapters,
        Integer maxChapters,
        String from,
        String to,
        String sort) {
}
