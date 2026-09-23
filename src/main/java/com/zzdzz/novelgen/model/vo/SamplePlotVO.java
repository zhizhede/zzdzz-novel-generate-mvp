package com.zzdzz.novelgen.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.dto.SamplePlotNodeDTO;

/** 导入样本剧情结构节点（书/卷/章；beats=场景拆解数组，meta=扩展元）。 */
public record SamplePlotVO(Long id, String level, Integer seq, Integer parentSeq, String title,
                           String summary, JsonNode beats, JsonNode meta) {

    public static SamplePlotVO from(SamplePlotNodeDTO node, ObjectMapper mapper) {
        return new SamplePlotVO(node.getId(), node.getLevel(), node.getSeq(), node.getParentSeq(),
                node.getTitle(), node.getSummary(), parse(mapper, node.getBeats()), parse(mapper, node.getMeta()));
    }

    private static JsonNode parse(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "[]" : json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }
}
