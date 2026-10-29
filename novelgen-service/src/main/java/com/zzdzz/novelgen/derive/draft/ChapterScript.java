package com.zzdzz.novelgen.derive.draft;

import java.util.List;
import java.util.Objects;

/** 一章的不可变写作脚本；标题和预算来自 ADMITTED snapshot。 */
public record ChapterScript(int chapterNo, String title, int budget, List<SceneScript> scenes) {

    public ChapterScript {
        if (chapterNo <= 0 || budget <= 0) {
            throw new IllegalArgumentException("chapterNo/budget must be positive");
        }
        title = title == null || title.isBlank() ? "第" + chapterNo + "章" : title;
        scenes = scenes == null ? List.of() : List.copyOf(scenes);
        if (scenes.isEmpty()) {
            throw new IllegalArgumentException("chapter scenes must not be empty");
        }
        for (int index = 0; index < scenes.size(); index++) {
            if (scenes.get(index).sceneNo() != index + 1) {
                throw new IllegalArgumentException("sceneNo must be continuous from 1");
            }
        }
    }
}
