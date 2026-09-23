package com.zzdzz.novelgen.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.dto.SampleCardDTO;

/** 导入样本结构化资产卡（角色/物品/地点/组织/现象/世界观；relations=[{target,kind,note}]）。 */
public record SampleCardVO(Long id, String kind, String name, JsonNode aliases, String summary,
                           String contentMd, JsonNode relations, Integer importance,
                           Integer firstSeq, Integer mentions) {

    public static SampleCardVO from(SampleCardDTO card, ObjectMapper mapper) {
        return new SampleCardVO(card.getId(), card.getKind(), card.getName(),
                parse(mapper, card.getAliases()), card.getSummary(), card.getContentMd(),
                parse(mapper, card.getRelations()), card.getImportance(),
                card.getFirstSeq(), card.getMentions());
    }

    private static JsonNode parse(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "[]" : json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }
}
