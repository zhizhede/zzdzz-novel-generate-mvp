package com.zzdzz.novelgen.derive.story;

import java.util.List;

/**
 * 通用故事关系：两个实体之间可随章节变化的连接。
 */
public record StoryRelation(
        String key,
        String leftKey,
        String rightKey,
        String kind,
        Integer openedChapter,
        List<Integer> changedChapters,
        Integer closedChapter,
        String status) {

    public StoryRelation {
        key = StoryEntity.requireText(key, "key");
        leftKey = StoryEntity.requireText(leftKey, "leftKey");
        rightKey = StoryEntity.requireText(rightKey, "rightKey");
        kind = StoryEntity.requireText(kind, "kind");
        changedChapters = changedChapters == null ? List.of() : List.copyOf(changedChapters);
        status = status == null ? "OPEN" : status.strip();
    }
}
