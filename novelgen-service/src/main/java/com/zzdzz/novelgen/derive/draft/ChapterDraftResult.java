package com.zzdzz.novelgen.derive.draft;

import java.util.List;
import java.util.Objects;

/** 章节组装结果；text 只由非空场景散文组成。 */
public record ChapterDraftResult(
        int chapterNo,
        String text,
        int chars,
        List<SceneDraft> scenes,
        boolean degraded) {

    public ChapterDraftResult {
        if (chapterNo <= 0 || chars <= 0) {
            throw new IllegalArgumentException("chapterNo/chars must be positive");
        }
        Objects.requireNonNull(text, "text");
        scenes = scenes == null ? List.of() : List.copyOf(scenes);
    }
}
