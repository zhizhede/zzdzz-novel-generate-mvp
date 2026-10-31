package com.zzdzz.novelgen.derive.story;

import java.util.Map;

/**
 * 通用故事线：可开启、推进、兑现并关闭的跨章线索、关系、物件或世界线。
 */
public record StoryThread(
        String threadKey,
        String kind,
        String title,
        Integer openedChapter,
        String status,
        String ownerKey,
        Integer targetChapter,
        Map<String, Object> contract) {

    public StoryThread {
        threadKey = StoryEntity.requireText(threadKey, "threadKey");
        kind = StoryEntity.requireText(kind, "kind");
        title = StoryEntity.requireText(title, "title");
        status = status == null || status.isBlank() ? "OPEN" : status.strip();
        contract = contract == null ? Map.of() : Map.copyOf(contract);
    }
}
