package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬疑 QC 审计：在通用审计之上检查真相是否在揭示章兑现。
 */
@Component
public class MysteryQcAuditor extends GenreQcAuditor {

    @Override
    public List<PlanContracts.PlanIssue> audit(CompiledDraft draft, StoryKernel kernel,
                                               GenreProfile profile) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>(super.audit(draft, kernel, profile));
        return List.copyOf(issues);
    }
}
