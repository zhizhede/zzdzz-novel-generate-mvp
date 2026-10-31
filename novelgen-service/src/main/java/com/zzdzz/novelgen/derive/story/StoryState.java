package com.zzdzz.novelgen.derive.story;

/**
 * 通用故事状态：实体从指定章节起生效的状态快照。
 */
public record StoryState(
        String entityKey,
        String kind,
        String value,
        int effectiveChapter,
        String sourceEventKey) {

    public StoryState {
        entityKey = StoryEntity.requireText(entityKey, "entityKey");
        kind = StoryEntity.requireText(kind, "kind");
        value = StoryEntity.requireText(value, "value");
        if (effectiveChapter <= 0) {
            throw new IllegalArgumentException("effectiveChapter 必须为正数");
        }
    }
}
