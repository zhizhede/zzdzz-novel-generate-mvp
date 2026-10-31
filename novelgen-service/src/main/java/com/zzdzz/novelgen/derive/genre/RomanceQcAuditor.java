package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 言情 QC 审计：检查关系阶段证据，不使用正文字符串包含。 */
@Component
public class RomanceQcAuditor {

    public List<PlanContracts.PlanIssue> auditQc(RomanceContract contract, StoryKernel kernel,
                                                  StoryEvidenceIndex evidence,
                                                  GenreQcAuditor.CompiledDraft draft) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (contract == null || kernel == null || evidence == null) {
            return issues;
        }
        for (var obligation : new RomanceProfile().projectObligations(contract, List.of())) {
            if (obligation.required() && !evidence.hasEvidence(obligation.obligationKey())) {
                issues.add(new PlanContracts.PlanIssue("HARD", "ROMANCE_QC_MISSING_EVIDENCE",
                        PlanContracts.RepairScope.DRAFT, null, obligation.chapterNo(), List.of(),
                        "言情义务缺少证据：" + obligation.obligationKey()));
            }
        }
        return List.copyOf(issues);
    }
}
