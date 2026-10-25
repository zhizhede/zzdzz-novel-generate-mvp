package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按章节/seq 模拟角色知识与线程状态；供规划校验和局部修复共用。 */
@Service
public class DeriveStateLedgerService {

    public StateLedger simulate(List<PlanContracts.BeatAssignment> assignments,
                                List<PlanContracts.SyntheticAssignment> synthetic,
                                List<PlanContracts.CharacterContract> characters) {
        List<Step> steps = new ArrayList<>();
        for (PlanContracts.BeatAssignment assignment : assignments) {
            steps.add(new Step(assignment.chapterNo(), assignment.seq(),
                    assignment.focusCharacters(), assignment.requiresKnowledge(),
                    assignment.writesKnowledge(), assignment.threadRefs(),
                    assignment.beatRole().name()));
        }
        for (PlanContracts.SyntheticAssignment item : synthetic) {
            steps.add(new Step(item.chapterNo(), Integer.MAX_VALUE,
                    item.focusCharacters(), item.requiresKnowledge(),
                    item.writesKnowledge(), item.threadRefs(), item.beatRole().name()));
        }
        steps.sort((a, b) -> {
            int chapter = Integer.compare(a.chapterNo(), b.chapterNo());
            return chapter != 0 ? chapter : Integer.compare(a.seq(), b.seq());
        });
        Map<String, Set<String>> known = new HashMap<>();
        for (PlanContracts.CharacterContract character : characters) {
            known.put(character.characterKey(), new LinkedHashSet<>());
        }
        Set<String> paidThreads = new HashSet<>();
        List<String> errors = new ArrayList<>();
        for (Step step : steps) {
            for (String fact : step.requires()) {
                Set<String> readers = known.computeIfAbsent(step.focus().isEmpty()
                        ? "C1" : step.focus().get(0), key -> new LinkedHashSet<>());
                if (!readers.contains(fact)) {
                    errors.add("第" + step.chapterNo() + "拍" + step.seq()
                            + "要求知识未获得：" + fact);
                }
            }
            for (String character : step.focus()) {
                known.computeIfAbsent(character, key -> new LinkedHashSet<>())
                        .addAll(step.writes());
            }
            if (isPayoffRole(step.role())) {
                paidThreads.addAll(step.threads());
            }
        }
        return new StateLedger(known, paidThreads, errors);
    }

    private static boolean isPayoffRole(String role) {
        return "CLUE_PAYOFF".equals(role)
                || "LOCAL_CLOSURE".equals(role)
                || "FINAL_REVEAL".equals(role)
                || "FINAL_CLOSURE".equals(role);
    }

    private record Step(int chapterNo, int seq, List<String> focus, List<String> requires,
                        List<String> writes, List<String> threads, String role) {
    }

    public record StateLedger(Map<String, Set<String>> knownByCharacter,
                              Set<String> paidThreads,
                              List<String> errors) {
    }
}