package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ⑥b 闭合窗口校验器：按结构化 closingActs/closureEvidence 检查缺失动作、标签和目标。
 */
public class ClosureWindowValidator {

    /** 返回结构化问题；闭合章不在当前窗时递延，不提前报错。 */
    public List<PlanContracts.PlanIssue> validate(DraftWindowObligationValidator.DraftWindow window,
                                                  StoryClosureContract contract) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (window == null || contract == null
                || contract.chapterNo() < window.fromChapter()
                || contract.chapterNo() > window.toChapter()) {
            return issues;
        }
        List<DraftWindowObligationValidator.ClosingAct> acts = window.closingActs();
        List<DraftWindowObligationValidator.ClosureEvidence> evidence = window.closureEvidence();
        for (String required : contract.requiredActs()) {
            String act = required.toUpperCase(Locale.ROOT);
            boolean hasEvidence = evidence.stream().anyMatch(item ->
                    act.equalsIgnoreCase(item.act()));
            boolean hasAct = acts.stream().anyMatch(item ->
                    act.equalsIgnoreCase(item.act()));
            if (!hasEvidence && !hasAct) {
                issues.add(issue("CLOSURE_WINDOW_MISSING", contract.chapterNo(),
                        contract.closureKey(), List.of(required), contract.payload()));
                continue;
            }
            List<String> tags = new ArrayList<>();
            acts.stream().filter(item -> act.equalsIgnoreCase(item.act()))
                    .map(DraftWindowObligationValidator.ClosingAct::tag)
                    .filter(ClosureWindowValidator::hasText)
                    .forEach(tags::add);
            evidence.stream().filter(item -> act.equalsIgnoreCase(item.act()))
                    .map(DraftWindowObligationValidator.ClosureEvidence::tag)
                    .filter(ClosureWindowValidator::hasText)
                    .forEach(tags::add);
            boolean tagMatches = tags.stream().anyMatch(tag ->
                    contract.requiredTags().stream().anyMatch(requiredTag ->
                            requiredTag.equals(tag)));
            if (!tagMatches) {
                issues.add(issue("CLOSURE_TAG_MISMATCH", contract.chapterNo(),
                        contract.closureKey(), List.of(required), contract.payload()));
            }
            if ("CULPRIT".equals(act)) {
                Object expected = contract.payload().get("culpritKey");
                boolean culpritMismatch = expected != null && acts.stream()
                        .filter(item -> "CULPRIT".equalsIgnoreCase(item.act()))
                        .anyMatch(item -> !expected.equals(item.targetKey()));
                if (culpritMismatch) {
                    issues.add(issue("CLOSURE_CULPRIT_MISMATCH", contract.chapterNo(),
                            contract.closureKey(), List.of(required), contract.payload()));
                }
            }
        }
        return List.copyOf(issues);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static PlanContracts.PlanIssue issue(String code, int chapterNo,
                                                 String obligationKey, List<String> missingActs,
                                                 java.util.Map<String, Object> payload) {
        return new PlanContracts.PlanIssue("HARD", code, PlanContracts.RepairScope.DRAFT,
                null, chapterNo, List.of(),
                "窗口闭合失败：code=" + code + " chapterNo=" + chapterNo
                        + " obligationKey=" + obligationKey
                        + " missingActs=" + missingActs + " contractPayload=" + payload);
    }
}
