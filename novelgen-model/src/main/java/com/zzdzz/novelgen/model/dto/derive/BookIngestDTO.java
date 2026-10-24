package com.zzdzz.novelgen.model.dto.derive;

/**
 * ①取样入库入参（源书）。categoryTags 为 jsonb 字面量（如 {"言情":1}），可空。
 * 走 JSON 绑定（Jackson record）。
 */
public record BookIngestDTO(
        String title,
        String text,
        String author,
        String platform,
        String categoryTags
) {
}
