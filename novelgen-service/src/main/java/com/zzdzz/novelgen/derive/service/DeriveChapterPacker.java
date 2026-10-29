package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 全局拍池到章节的确定性机械装箱：不信任模型给出的章节归属。 */
public final class DeriveChapterPacker {

    private DeriveChapterPacker() {
    }

    public static PlanContracts.RoutePlan pack(PlanContracts.RoutePlan plan) {
        if (plan == null || plan.chapters().size() < 2) {
            return plan;
        }
        List<PlanContracts.BeatAssignment> assignments = new ArrayList<>(plan.assignments());
        List<PlanContracts.SyntheticAssignment> synthetic = new ArrayList<>(plan.synthetic());
        int total = assignments.size() + synthetic.size();
        int max = Math.max(2, (int) Math.ceil(total * 1.5 / plan.chapters().size()));
        Map<Integer, Integer> load = new HashMap<>();
        for (PlanContracts.ChapterSlotContract chapter : plan.chapters()) {
            load.put(chapter.chapterNo(), 0);
        }
        for (PlanContracts.BeatAssignment item : assignments) {
            if (item.chapterNo() != null) {
                load.merge(item.chapterNo(), 1, Integer::sum);
            }
        }
        for (PlanContracts.SyntheticAssignment item : synthetic) {
            if (item.chapterNo() != null) {
                load.merge(item.chapterNo(), 1, Integer::sum);
            }
        }
        Map<Integer, Set<String>> required = requiredRoles(plan);
        // 先保证每章至少一拍：从最载荷章搬普通拍到空章。
        for (PlanContracts.ChapterSlotContract chapter : plan.chapters()) {
            if (load.getOrDefault(chapter.chapterNo(), 0) > 0) {
                continue;
            }
            int source = load.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey).orElse(-1);
            if (source < 0) {
                break;
            }
            Integer moved = movableAssignment(assignments, source, chapter.chapterNo(), required);
            if (moved != null) {
                PlanContracts.BeatAssignment beat = assignments.get(moved);
                assignments.set(moved, withChapter(beat, chapter.chapterNo()));
                load.merge(source, -1, Integer::sum);
                load.merge(chapter.chapterNo(), 1, Integer::sum);
            }
        }
        // 再压超载章：只搬普通推进拍，seq 不变。
        while (true) {
            Integer source = load.entrySet().stream()
                    .filter(e -> e.getValue() > max)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (source == null) {
                break;
            }
            Integer target = plan.chapters().stream()
                    .map(PlanContracts.ChapterSlotContract::chapterNo)
                    .filter(no -> !no.equals(source) && load.getOrDefault(no, 0) < max)
                    .min(Comparator.comparingInt(no -> load.getOrDefault(no, 0)))
                    .orElse(null);
            if (target == null) {
                break;
            }
            Integer moved = movableAssignment(assignments, source, target, required);
            if (moved == null) {
                break;
            }
            PlanContracts.BeatAssignment beat = assignments.get(moved);
            assignments.set(moved, withChapter(beat, target));
            load.merge(source, -1, Integer::sum);
            load.merge(target, 1, Integer::sum);
        }
        return new PlanContracts.RoutePlan(plan.volumeNo(), plan.chapters(), assignments,
                synthetic, plan.skipped());
    }

    private static Integer movableAssignment(List<PlanContracts.BeatAssignment> assignments,
                                             int source, int target,
                                             Map<Integer, Set<String>> required) {
        if (target < source) {
            return null;
        }
        for (int i = 0; i < assignments.size(); i++) {
            PlanContracts.BeatAssignment beat = assignments.get(i);
            if (beat.chapterNo() == null || beat.chapterNo() != source) {
                continue;
            }
            String role = beat.beatRole().name();
            if (protectedRole(role) || carriesClueRef(beat.threadRefs())
                    || carriesDependencyOrKnowledge(beat)) {
                continue;
            }
            if (required.getOrDefault(source, Set.of()).contains(role)) {
                continue;
            }
            return i;
        }
        return null;
    }

    /** 带线索引用的拍不可搬：搬走等于把合同章号改掉。 */
    private static boolean carriesClueRef(List<String> threadRefs) {
        return threadRefs != null && threadRefs.stream()
                .anyMatch(ref -> ref != null && ref.trim().startsWith("GM-CLUE-"));
    }

    /** 带知识/状态依赖的拍不可搬：搬动会让前置知识或状态跨越章序。 */
    private static boolean carriesDependencyOrKnowledge(PlanContracts.BeatAssignment beat) {
        return nonEmpty(beat.threadRefs()) || nonEmpty(beat.dependsOn())
                || nonEmpty(beat.requiresKnowledge()) || nonEmpty(beat.writesKnowledge())
                || nonEmpty(beat.requiresState()) || nonEmpty(beat.changesState());
    }

    private static boolean nonEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean nonEmpty(Map<?, ?> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean protectedRole(String role) {
        return Set.of("HOOK", "HIGHLIGHT", "CHARACTER_CHOICE", "COST",
                "LOCAL_CLOSURE", "FINAL_CLOSURE", "NEXT_HOOK", "FINAL_REVEAL",
                "CLUE_PLANT", "CLUE_PAYOFF", "RELATION_CONFLICT").contains(role);
    }

    private static PlanContracts.BeatAssignment withChapter(
            PlanContracts.BeatAssignment beat, int chapterNo) {
        return new PlanContracts.BeatAssignment(beat.seq(), chapterNo, beat.storyLayer(),
                beat.beatRole(), beat.focusCharacters(), beat.threadRefs(), beat.dependsOn(),
                beat.payoffTarget(), beat.appearanceMode(), beat.requiresKnowledge(),
                beat.writesKnowledge(), beat.requiresState(), beat.changesState());
    }

    private static Map<Integer, Set<String>> requiredRoles(PlanContracts.RoutePlan plan) {
        Map<Integer, Set<String>> out = new LinkedHashMap<>();
        for (PlanContracts.ChapterSlotContract chapter : plan.chapters()) {
            Set<String> roles = new HashSet<>();
            chapter.requiredBeatRoles().forEach(role -> roles.add(role.name()));
            out.put(chapter.chapterNo(), roles);
        }
        return out;
    }
}
