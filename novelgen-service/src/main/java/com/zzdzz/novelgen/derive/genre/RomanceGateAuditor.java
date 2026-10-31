package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 言情 P6 审计：检查阶段证据和关系线闭合，不调用悬疑审计。 */
@Component
public class RomanceGateAuditor {

    public List<PlanContracts.PlanIssue> audit(RomanceContract contract, StoryKernel kernel,
                                               StoryEvidenceIndex evidence) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (contract == null || kernel == null || evidence == null) {
            return issues;
        }
        RomanceProfile profile = new RomanceProfile();
        for (StoryObligationEvidenceReference reference :
                obligations(contract, profile)) {
            if (!evidence.hasEvidence(reference.obligationKey())) {
                issues.add(issue("ROMANCE_GATE_MISSING_EVIDENCE", reference.chapterNo(),
                        reference.obligationKey()));
            }
        }
        boolean closed = kernel.threads().stream().anyMatch(thread ->
                contract.primaryRelationship().threadKey().equals(thread.threadKey())
                        && ("PAYOFF".equalsIgnoreCase(thread.status())
                        || "CLOSED".equalsIgnoreCase(thread.status())));
        String closureKey = StoryObligationKey.forStage(
                contract.stages().get(RomanceStageKind.RELATIONSHIP_CLOSURE),
                "FINAL_CLOSURE", contract.primaryRelationship().threadKey());
        if (!closed && !evidence.hasEvidence(closureKey)) {
            issues.add(issue("ROMANCE_RELATIONSHIP_OPEN", null,
                    contract.primaryRelationship().threadKey()));
        }
        return List.copyOf(issues);
    }

    private List<StoryObligationEvidenceReference> obligations(RomanceContract contract,
                                                               RomanceProfile profile) {
        List<StoryObligationEvidenceReference> out = new ArrayList<>();
        for (var obligation : profile.projectObligations(contract, List.of())) {
            out.add(new StoryObligationEvidenceReference(obligation.obligationKey(),
                    obligation.chapterNo()));
        }
        return out;
    }

    private static PlanContracts.PlanIssue issue(String code, Integer chapterNo, String message) {
        return new PlanContracts.PlanIssue("HARD", code, PlanContracts.RepairScope.CONTRACT,
                null, chapterNo, List.of(), message);
    }

    private record StoryObligationEvidenceReference(String obligationKey, int chapterNo) {
    }

    private static final class StoryObligationKey {
        private static String forStage(RomanceStage stage, String kind, String targetKey) {
            return com.zzdzz.novelgen.derive.story.StoryObligation.obligationKey(
                    stage.chapterNo(), kind, targetKey);
        }
    }
}
