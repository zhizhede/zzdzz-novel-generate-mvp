package com.zzdzz.novelgen.model.vo;

import java.time.OffsetDateTime;

/** 素材库·导入小说行：一本导入的小说 + 其完整分析快照（analysisJson 由前端解析展示）。 */
public record ImportedSampleVO(
        long id,
        String title,
        String genre,
        int chunks,
        long totalChars,
        String source,
        Long presetId,
        String analysisJson,
        OffsetDateTime createTime) {

    public static ImportedSampleVO from(com.zzdzz.novelgen.model.dto.ImportedSampleDTO d) {
        return new ImportedSampleVO(d.getId(), d.getTitle(), d.getGenre(), d.getChunks(), d.getTotalChars(),
                d.getSource(), d.getPresetId(), d.getAnalysis(), d.getCreateTime());
    }
}
