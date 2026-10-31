package com.zzdzz.novelgen.derive.story;

import java.util.List;

/**
 * 一次叙事义务的结构化证据；义务是否兑现由该记录判定，不使用正文字符串包含判断。
 */
public record StoryObligationEvidence(
        String obligationKey,
        int chapterNo,
        String evidenceKind,
        String eventKey,
        Integer beatSeq,
        List<String> entityKeys,
        List<String> threadKeys,
        String fact,
        String source,
        double confidence) {

    public StoryObligationEvidence {
        obligationKey = StoryEntity.requireText(obligationKey, "obligationKey");
        evidenceKind = StoryEntity.requireText(evidenceKind, "evidenceKind");
        eventKey = StoryEntity.requireText(eventKey, "eventKey");
        if (chapterNo <= 0) {
            throw new IllegalArgumentException("chapterNo 必须为正数");
        }
        entityKeys = entityKeys == null ? List.of() : List.copyOf(entityKeys);
        threadKeys = threadKeys == null ? List.of() : List.copyOf(threadKeys);
        source = source == null || source.isBlank() ? "BATCH" : source;
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence 必须在 0..1");
        }
    }

    /** 证据唯一键；同一义务、章、类型和来源事件只允许登记一次。 */
    public String evidenceKey() {
        return obligationKey + "|" + evidenceKind + "|" + chapterNo + "|" + eventKey
                + "|" + (beatSeq == null ? "" : beatSeq);
    }
}
