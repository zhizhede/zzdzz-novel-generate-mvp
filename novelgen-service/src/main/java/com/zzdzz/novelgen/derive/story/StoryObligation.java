package com.zzdzz.novelgen.derive.story;

import java.util.Map;

/**
 * 可执行叙事义务：模型必须或不得在指定章节兑现的确定性合同投影。
 */
public record StoryObligation(
        String obligationKey,
        int chapterNo,
        String kind,
        String targetKey,
        boolean required,
        boolean forbidden,
        String severity,
        String sourceContract,
        Map<String, Object> payload) {

    public StoryObligation {
        obligationKey = StoryEntity.requireText(obligationKey, "obligationKey");
        kind = StoryEntity.requireText(kind, "kind");
        targetKey = StoryEntity.requireText(targetKey, "targetKey");
        if (chapterNo <= 0) {
            throw new IllegalArgumentException("chapterNo 必须为正数");
        }
        if (severity == null || severity.isBlank()) {
            severity = "HARD";
        }
        if (required && forbidden) {
            throw new IllegalArgumentException("required 与 forbidden 不能同时为真");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** 统一义务键：章号、类型和目标共同定位一次可执行义务。 */
    public static String obligationKey(int chapterNo, String kind, String targetKey) {
        return "CH" + chapterNo + ":" + StoryEntity.requireText(kind, "kind")
                + ":" + StoryEntity.requireText(targetKey, "targetKey");
    }
}
