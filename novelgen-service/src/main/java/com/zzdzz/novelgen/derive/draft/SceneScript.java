package com.zzdzz.novelgen.derive.draft;

import java.util.List;
import java.util.Objects;

/** 已冻结计划中的一场戏；正文层只消费，不修改。 */
public record SceneScript(
        int sceneNo,
        String beatKey,
        String goal,
        int budget,
        List<String> carryKnowledge,
        List<String> revealKnowledge) {

    public SceneScript {
        if (sceneNo <= 0 || budget <= 0) {
            throw new IllegalArgumentException("sceneNo/budget must be positive");
        }
        Objects.requireNonNull(beatKey, "beatKey");
        Objects.requireNonNull(goal, "goal");
        carryKnowledge = carryKnowledge == null ? List.of() : List.copyOf(carryKnowledge);
        revealKnowledge = revealKnowledge == null ? List.of() : List.copyOf(revealKnowledge);
    }
}
