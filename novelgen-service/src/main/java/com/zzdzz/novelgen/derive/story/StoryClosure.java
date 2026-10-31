package com.zzdzz.novelgen.derive.story;

import java.util.List;

/**
 * 通用故事闭合：某条线程、关系或世界目标必须在指定章节闭合并留下证明事件。
 */
public record StoryClosure(
        String closureKey,
        String kind,
        String targetKey,
        int requiredChapter,
        boolean required,
        List<String> proofEventKeys) {

    public StoryClosure {
        closureKey = StoryEntity.requireText(closureKey, "closureKey");
        kind = StoryEntity.requireText(kind, "kind");
        targetKey = StoryEntity.requireText(targetKey, "targetKey");
        if (requiredChapter <= 0) {
            throw new IllegalArgumentException("requiredChapter 必须为正数");
        }
        proofEventKeys = proofEventKeys == null ? List.of() : List.copyOf(proofEventKeys);
    }
}
