package com.zzdzz.novelgen.model.dto.derive;

/**
 * 书列表查询条件（URL 查询参数绑定，字段全可空 = 走历史默认）：
 * keyword 命中书名与作者；sourceType 缺省=SOURCE（保持旧 /books 行为）、可传 SOURCE/GENERATED/ALL；
 * from/to 为 yyyy-MM-dd（含当日）；sort 取 ID_DESC（默认）/ID_ASC/TIME_DESC/TIME_ASC/TITLE_ASC。
 */
public record BookQueryDTO(
        String keyword,
        String sourceType,
        String from,
        String to,
        String sort) {
}
