package com.zzdzz.novelgen.derive.story;

import java.util.List;
import java.util.Map;

/**
 * 通用故事事件：某章内发生的动作及其知识、状态和线程效果。
 */
public record StoryEvent(
        String eventKey,
        int chapterNo,
        String kind,
        List<String> actorKeys,
        List<String> targetKeys,
        String goal,
        String result,
        List<Map<String, Object>> knowledgeEffects,
        List<Map<String, Object>> stateEffects,
        List<Map<String, Object>> threadEffects,
        String source) {

    public StoryEvent {
        eventKey = StoryEntity.requireText(eventKey, "eventKey");
        kind = StoryEntity.requireText(kind, "kind");
        if (chapterNo <= 0) {
            throw new IllegalArgumentException("chapterNo 必须为正数");
        }
        actorKeys = actorKeys == null ? List.of() : List.copyOf(actorKeys);
        targetKeys = targetKeys == null ? List.of() : List.copyOf(targetKeys);
        knowledgeEffects = knowledgeEffects == null ? List.of() : List.copyOf(knowledgeEffects);
        stateEffects = stateEffects == null ? List.of() : List.copyOf(stateEffects);
        threadEffects = threadEffects == null ? List.of() : List.copyOf(threadEffects);
    }
}
