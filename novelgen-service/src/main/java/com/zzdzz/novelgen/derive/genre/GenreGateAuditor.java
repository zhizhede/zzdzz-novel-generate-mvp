package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryClosure;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import com.zzdzz.novelgen.derive.story.StoryThread;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用 P6 审计：检查必需线程、闭合和义务是否在全书合同层完成。
 */
@Component
public class GenreGateAuditor {

    /** 审计通用故事核心；返回 HARD/FIXABLE 问题。 */
    public List<PlanContracts.PlanIssue> audit(StoryKernel kernel, GenreProfile profile) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (kernel == null) {
            return issues;
        }
        Set<String> threadKeys = new HashSet<>();
        for (StoryThread thread : kernel.threads()) {
            threadKeys.add(thread.threadKey());
            if (!"PAYOFF".equalsIgnoreCase(thread.status())
                    && !"CLOSED".equalsIgnoreCase(thread.status())) {
                issues.add(issue("THREAD_OPEN", null,
                        "线程 " + thread.threadKey() + " 尚未闭合"));
            }
        }
        boolean finalClosure = kernel.closure().stream()
                .anyMatch(closure -> "FINAL_CLOSURE".equals(closure.kind()) && closure.required())
                || kernel.obligations().stream()
                .anyMatch(obligation -> "FINAL_CLOSURE".equals(obligation.kind())
                        && obligation.required());
        if (!finalClosure) {
            issues.add(issue("FINAL_CLOSURE_MISSING", null, "缺少必需的 FINAL_CLOSURE"));
        }
        for (StoryObligation obligation : kernel.obligations()) {
            if (!obligation.required() || "FINAL_CLOSURE".equals(obligation.kind())) {
                continue;
            }
            if (("PLANT_THREAD".equals(obligation.kind())
                    || "PAYOFF_THREAD".equals(obligation.kind()))
                    && !threadKeys.contains(obligation.targetKey())) {
                issues.add(issue("OBLIGATION_UNFULFILLED", obligation.chapterNo(),
                        "义务 " + obligation.obligationKey() + " 没有对应线程"));
            }
        }
        return List.copyOf(issues);
    }

    protected static PlanContracts.PlanIssue issue(String code, Integer chapterNo, String message) {
        return new PlanContracts.PlanIssue("HARD", code, PlanContracts.RepairScope.CONTRACT,
                null, chapterNo, List.of(), message);
    }
}
