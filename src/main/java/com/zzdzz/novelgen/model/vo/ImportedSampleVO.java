package com.zzdzz.novelgen.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

/** 素材库·导入小说行：一本导入的小说 + 其完整分析快照（analysisJson 由前端解析展示）+ 类型/特征标签。 */
public record ImportedSampleVO(
        long id,
        String title,
        String genre,
        int chunks,
        long totalChars,
        String source,
        Long presetId,
        String analysisJson,
        JsonNode tags,
        OffsetDateTime createTime) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ImportedSampleVO from(com.zzdzz.novelgen.model.dto.ImportedSampleDTO d) {
        return new ImportedSampleVO(d.getId(), d.getTitle(), d.getGenre(), d.getChunks(), d.getTotalChars(),
                d.getSource(), d.getPresetId(), d.getAnalysis(), parseTags(d.getTags()), d.getCreateTime());
    }

    private static JsonNode parseTags(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "[]" : json);
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }
}
