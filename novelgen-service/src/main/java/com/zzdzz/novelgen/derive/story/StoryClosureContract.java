package com.zzdzz.novelgen.derive.story;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 闭合合同：指定章节必须用结构化动作与标签闭合一个目标；不约束正文逐字出现标签。
 */
public record StoryClosureContract(
        String closureKey,
        int chapterNo,
        String targetKey,
        String kind,
        List<String> requiredActs,
        List<String> requiredTags,
        List<String> proofEventKeys,
        String sourceContract,
        Map<String, Object> payload) {

    private static final Set<String> ALLOWED_ACTS = Set.of(
            "CULPRIT", "MOTIVE", "METHOD", "CHOICE", "STATE",
            "RELATIONSHIP_DECISION");

    public StoryClosureContract {
        closureKey = StoryEntity.requireText(closureKey, "closureKey");
        targetKey = StoryEntity.requireText(targetKey, "targetKey");
        kind = StoryEntity.requireText(kind, "kind");
        if (chapterNo <= 0) {
            throw new IllegalArgumentException("chapterNo 必须为正数");
        }
        requiredActs = requiredActs == null ? List.of() : List.copyOf(requiredActs);
        requiredTags = requiredTags == null ? List.of() : List.copyOf(requiredTags);
        proofEventKeys = proofEventKeys == null ? List.of() : List.copyOf(proofEventKeys);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        if (requiredActs.isEmpty()) {
            throw new IllegalArgumentException("requiredActs 不能为空");
        }
        for (String act : requiredActs) {
            if (!ALLOWED_ACTS.contains(act)) {
                throw new IllegalArgumentException("未知闭合动作：" + act);
            }
        }
        if (requiredTags.isEmpty()) {
            throw new IllegalArgumentException("requiredTags 不能为空");
        }
    }

    public StoryClosureContract(String closureKey, int chapterNo, String targetKey, String kind,
                                List<String> requiredActs, List<String> requiredTags,
                                List<String> proofEventKeys, String sourceContract) {
        this(closureKey, chapterNo, targetKey, kind, requiredActs, requiredTags,
                proofEventKeys, sourceContract, Map.of());
    }

    /** 允许的结构化闭合动作。 */
    public static Set<String> allowedActs() {
        return ALLOWED_ACTS;
    }
}
