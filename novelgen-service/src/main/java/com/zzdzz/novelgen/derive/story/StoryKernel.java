package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通用故事核心：跨题材共享的实体、关系、状态、线程、事件、义务、约束与闭合集合。
 *
 * <p>类型专属字段必须留在对应 {@code GenreProfile} 投影中，不得加入本核心。
 */
public record StoryKernel(
        long runId,
        int version,
        String status,
        PlanContracts.ScopeContract scope,
        List<StoryEntity> entities,
        List<StoryRelation> relations,
        List<StoryState> states,
        List<StoryThread> threads,
        List<StoryEvent> events,
        List<StoryObligation> obligations,
        List<StoryConstraint> constraints,
        List<StoryClosure> closure,
        StoryEvidenceIndex evidenceIndex) {

    public StoryKernel {
        status = StoryEntity.requireText(status, "status");
        entities = copyList(entities);
        relations = copyList(relations);
        states = copyList(states);
        threads = copyList(threads);
        events = copyList(events);
        obligations = copyList(obligations);
        constraints = copyList(constraints);
        closure = copyList(closure);
        evidenceIndex = evidenceIndex == null ? StoryEvidenceIndex.builder().build() : evidenceIndex;
        Set<String> entityKeys = entities.stream().map(StoryEntity::key)
                .collect(Collectors.toSet());
        if (entityKeys.size() != entities.size()) {
            String duplicated = entities.stream()
                    .collect(Collectors.groupingBy(StoryEntity::key, LinkedHashMap::new,
                            Collectors.counting()))
                    .entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining("、"));
            throw new IllegalArgumentException("实体 key 重复：" + duplicated);
        }
        Set<String> obligationKeys = obligations.stream().map(StoryObligation::obligationKey)
                .collect(Collectors.toSet());
        if (obligationKeys.size() != obligations.size()) {
            throw new IllegalArgumentException("义务 key 重复");
        }
    }

    private static <T> List<T> copyList(List<T> values) {
        return values == null ? List.of() : List.copyOf(Objects.requireNonNull(values));
    }

    public StoryKernel(long runId, int version, String status, PlanContracts.ScopeContract scope,
                       List<StoryEntity> entities, List<StoryRelation> relations,
                       List<StoryState> states, List<StoryThread> threads,
                       List<StoryEvent> events, List<StoryObligation> obligations,
                       List<StoryConstraint> constraints, List<StoryClosure> closure) {
        this(runId, version, status, scope, entities, relations, states, threads, events,
                obligations, constraints, closure, null);
    }
}
