package com.zzdzz.novelgen.derive.genre;

import java.util.List;
import java.util.Map;

/** 言情合同阶段；只表达关系推进，不包含悬疑线索字段。 */
public record RomanceStage(
        RomanceStageKind kind,
        int chapterNo,
        String targetKey,
        List<String> actorKeys,
        List<String> threadRefs,
        boolean required,
        String proof,
        Map<String, Object> payload) {

    public RomanceStage {
        if (chapterNo <= 0) {
            throw new IllegalArgumentException("chapterNo 必须为正数");
        }
        targetKey = targetKey == null ? "" : targetKey.strip();
        actorKeys = actorKeys == null ? List.of() : List.copyOf(actorKeys);
        threadRefs = threadRefs == null ? List.of() : List.copyOf(threadRefs);
        proof = proof == null ? "" : proof;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
