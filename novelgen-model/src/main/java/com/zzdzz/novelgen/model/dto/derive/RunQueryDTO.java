package com.zzdzz.novelgen.model.dto.derive;

/**
 * run 列表查询条件（URL 查询参数绑定，字段全可空 = 不筛）：
 * keyword 命中成品书名与创作思路；status 取 SAMPLING/PLANNING/DRAFTING/QC/PASSED/REWORK/FAILED 或 ALL；
 * from/to 为 yyyy-MM-dd（含当日）；sort 取 ID_DESC（默认）/ID_ASC/TIME_DESC/TIME_ASC。
 */
public record RunQueryDTO(
        String keyword,
        String status,
        Long novelId,
        String from,
        String to,
        String sort) {
}
