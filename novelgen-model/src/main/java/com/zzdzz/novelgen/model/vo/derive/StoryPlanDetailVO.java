package com.zzdzz.novelgen.model.vo.derive;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 故事规划完整读模型。 */
public record StoryPlanDetailVO(
        long runId,
        JsonNode planState,
        JsonNode scope,
        JsonNode storySpine,
        List<JsonNode> volumes,
        List<JsonNode> characters,
        List<JsonNode> threads,
        List<JsonNode> chapterSlots) {
}
