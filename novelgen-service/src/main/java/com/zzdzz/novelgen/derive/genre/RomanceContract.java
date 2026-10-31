package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 独立言情合同；不得继承或伪装成悬疑 StoryContract。 */
public record RomanceContract(
        int version,
        PlanContracts.ScopeContract scope,
        List<RomanceCharacterSlot> cast,
        RomanceRelationship primaryRelationship,
        Map<RomanceStageKind, RomanceStage> stages,
        List<String> forbidden) implements GenreContract {

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PROTAGONIST", "LOVE_INTEREST", "RIVAL", "FRIEND", "FAMILY", "SUPPORT", "OPPOSITION");
    private static final Set<String> ALLOWED_RELATIONSHIP_KINDS =
            Set.of("ROMANTIC", "FRIENDSHIP", "FAMILY", "RIVALRY");

    public RomanceContract {
        version = version <= 0 ? 1 : version;
        cast = cast == null ? List.of() : List.copyOf(cast);
        forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
        Map<RomanceStageKind, RomanceStage> normalizedStages = new LinkedHashMap<>();
        if (stages != null) {
            stages.forEach((kind, stage) -> {
                if (stage != null && stage.kind() == null) {
                    normalizedStages.put(kind, new RomanceStage(kind, stage.chapterNo(),
                            stage.targetKey(), stage.actorKeys(), stage.threadRefs(),
                            stage.required(), stage.proof(), stage.payload()));
                } else if (stage != null) {
                    normalizedStages.put(kind, stage);
                }
            });
        }
        stages = java.util.Collections.unmodifiableMap(normalizedStages);
        Set<String> keys = new LinkedHashSet<>();
        for (RomanceCharacterSlot slot : cast) {
            if (!keys.add(slot.key())) {
                throw new IllegalArgumentException("cast key 重复：" + slot.key());
            }
            if (!ALLOWED_ROLES.contains(slot.role())) {
                throw new IllegalArgumentException("未知角色 role：" + slot.role());
            }
        }
        if (cast.isEmpty()) {
            throw new IllegalArgumentException("cast 不能为空");
        }
        if (primaryRelationship == null) {
            throw new IllegalArgumentException("primaryRelationship 不能为空");
        }
        if (!ALLOWED_RELATIONSHIP_KINDS.contains(primaryRelationship.kind())) {
            throw new IllegalArgumentException("未知关系 kind：" + primaryRelationship.kind());
        }
        if (!keys.contains(primaryRelationship.leftKey())
                || !keys.contains(primaryRelationship.rightKey())) {
            throw new IllegalArgumentException("关系线角色必须属于 cast");
        }
        String expectedThreadKey = "REL-" + primaryRelationship.leftKey()
                + "-" + primaryRelationship.rightKey();
        if (!expectedThreadKey.equals(primaryRelationship.threadKey())) {
            throw new IllegalArgumentException("threadKey 必须为 " + expectedThreadKey);
        }
        for (RomanceStageKind required : RomanceStageKind.values()) {
            RomanceStage stage = stages.get(required);
            if (stage == null) {
                throw new IllegalArgumentException("missingStage=" + required);
            }
            if (stage.kind() != required) {
                throw new IllegalArgumentException("stage.kind 与 map key 不一致：" + required);
            }
            if (scope != null && scope.chapterCount() > 0
                    && (stage.chapterNo() < 1 || stage.chapterNo() > scope.chapterCount())) {
                throw new IllegalArgumentException("stage.chapterNo 越界：" + stage.chapterNo());
            }
            for (String actor : stage.actorKeys()) {
                if (!keys.contains(actor)) {
                    throw new IllegalArgumentException("actorKeys 必须属于 cast：" + actor);
                }
            }
            if (!stage.threadRefs().contains(primaryRelationship.threadKey())) {
                throw new IllegalArgumentException("threadRefs 必须包含 "
                        + primaryRelationship.threadKey());
            }
            if ((required == RomanceStageKind.MISUNDERSTANDING
                    || required == RomanceStageKind.CONFLICT_PEAK)
                    && !keys.contains(stage.targetKey())) {
                throw new IllegalArgumentException(required + " targetKey 必须属于 cast："
                        + stage.targetKey());
            }
            if (targetRequiresThreadKey(required)
                    && !primaryRelationship.threadKey().equals(stage.targetKey())) {
                throw new IllegalArgumentException(required + " targetKey 必须为 "
                        + primaryRelationship.threadKey());
            }
        }
        for (String forbiddenTerm : forbidden) {
            if (forbiddenTerm != null && (forbiddenTerm.contains("culprit")
                    || forbiddenTerm.contains("motive") || forbiddenTerm.contains("method")
                    || forbiddenTerm.contains("revealChapter") || forbiddenTerm.contains("clue"))) {
                throw new IllegalArgumentException("言情 forbidden 不得包含悬疑字段："
                        + forbiddenTerm);
            }
        }
        if (stages.get(RomanceStageKind.CONFESSION).chapterNo()
                < stages.get(RomanceStageKind.ATTACHMENT_ESCALATION).chapterNo()) {
            throw new IllegalArgumentException("CONFESSION 不得早于 ATTACHMENT_ESCALATION");
        }
        if (stages.get(RomanceStageKind.RECONCILIATION).chapterNo()
                < stages.get(RomanceStageKind.CONFLICT_PEAK).chapterNo()) {
            throw new IllegalArgumentException("RECONCILIATION 不得早于 CONFLICT_PEAK");
        }
        if (stages.get(RomanceStageKind.RELATIONSHIP_START).chapterNo()
                != previousMin(stages)) {
            throw new IllegalArgumentException("RELATIONSHIP_START 必须最早");
        }
        if (stages.get(RomanceStageKind.RELATIONSHIP_CLOSURE).chapterNo()
                != previousMax(stages)) {
            throw new IllegalArgumentException("RELATIONSHIP_CLOSURE 必须最后");
        }
        int previous = 0;
        for (RomanceStageKind kind : orderedKinds()) {
            RomanceStage stage = stages.get(kind);
            if (stage.chapterNo() < previous) {
                throw new IllegalArgumentException("stage 章号必须单调不减");
            }
            previous = stage.chapterNo();
        }
    }

    public static boolean targetRequiresThreadKey(RomanceStageKind kind) {
        return kind != RomanceStageKind.MISUNDERSTANDING
                && kind != RomanceStageKind.CONFLICT_PEAK;
    }

    private static List<RomanceStageKind> orderedKinds() {
        return List.of(RomanceStageKind.values());
    }

    private static int previousMin(Map<RomanceStageKind, RomanceStage> stages) {
        return stages.values().stream().mapToInt(RomanceStage::chapterNo).min().orElse(0);
    }

    private static int previousMax(Map<RomanceStageKind, RomanceStage> stages) {
        return stages.values().stream().mapToInt(RomanceStage::chapterNo).max().orElse(0);
    }
}
