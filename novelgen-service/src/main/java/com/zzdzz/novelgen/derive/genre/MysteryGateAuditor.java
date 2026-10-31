package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬疑 P6 审计：在通用审计之上检查线索 plant/payoff 与真相闭合。
 */
@Component
public class MysteryGateAuditor extends GenreGateAuditor {

    @Override
    public List<PlanContracts.PlanIssue> audit(StoryKernel kernel, GenreProfile profile) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>(super.audit(kernel, profile));
        if (kernel == null) {
            return issues;
        }
        for (var obligation : kernel.obligations()) {
            if ("PAYOFF_THREAD".equals(obligation.kind()) && obligation.required()
                    && kernel.threads().stream().noneMatch(thread ->
                    thread.threadKey().equals(obligation.targetKey())
                            && ("PAYOFF".equalsIgnoreCase(thread.status())
                            || "CLOSED".equalsIgnoreCase(thread.status())))) {
                issues.add(issue("OBLIGATION_UNFULFILLED", obligation.chapterNo(),
                        "悬疑线索 " + obligation.targetKey() + " 未兑现"));
            }
        }
        return List.copyOf(issues);
    }
}
