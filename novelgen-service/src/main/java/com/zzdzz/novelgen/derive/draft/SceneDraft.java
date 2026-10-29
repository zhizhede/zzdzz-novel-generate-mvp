package com.zzdzz.novelgen.derive.draft;

import java.util.Objects;

/** SceneWriter 或已完成 beat_instance 行产出的单场纯散文。 */
public record SceneDraft(int sceneNo, String text, int chars) {

    public SceneDraft {
        if (sceneNo <= 0 || chars < 0) {
            throw new IllegalArgumentException("sceneNo/chars must be positive");
        }
        Objects.requireNonNull(text, "text");
    }
}

