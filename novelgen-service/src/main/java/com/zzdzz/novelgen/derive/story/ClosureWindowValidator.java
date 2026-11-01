package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ⑥b 闭合窗口校验器：按结构化 closingActs/closureEvidence 检查动作、标签、目标和证明事件。
 */
public class ClosureWindowValidator {

    private static final StoryEvidenceIndex EMPTY_EVIDENCE = StoryEvidenceIndex.builder().build();

    /** 返回结构化问题；闭合章在当前窗之后时递延，不在当前窗之前时按既往证据判定。 */
    public List<PlanContracts.PlanIssue> validate(DraftWindowObligationValidator.DraftWindow window,
                                                  StoryClosureContract contract) {
        return validate(window, contract, EMPTY_EVIDENCE);
    }

    /** 带既往窗口证据的闭合校验；既往证据只用于判定已过窗闭合是否兑现。 */
    public List<PlanContracts.PlanIssue> validate(DraftWindowObligationValidator.DraftWindow window,
                                                  StoryClosureContract contract,
                                                  StoryEvidenceIndex priorEvidence) {
        if (window == null || contract == null) {
            return List.of();
        }
        if (contract.chapterNo() > window.toChapter()) {
            return List.of();
        }
        if (contract.chapterNo() < window.fromChapter()) {
            if (priorEvidence != null && priorEvidence.hasEvidence(contract.closureKey())) {
                return List.of();
            }
            return List.of(issue("CLOSURE_WINDOW_MISSING", window, contract,
                    contract.requiredActs(), "pastUnfulfilled"));
        }

        List<DraftWindowObligationValidator.ClosingAct> acts = window.closingActs();
        List<DraftWindowObligationValidator.ClosureEvidence> evidence = window.closureEvidence()
                .stream()
                .filter(item -> item.obligationKey().isBlank()
                        || item.obligationKey().equals(contract.closureKey()))
                .toList();
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        List<String> missingActs = new ArrayList<>();
        for (String required : contract.requiredActs()) {
            String act = required.toUpperCase(Locale.ROOT);
            boolean hasEvidence = evidence.stream().anyMatch(item ->
                    act.equalsIgnoreCase(item.act()));
            boolean hasAct = acts.stream().anyMatch(item ->
                    act.equalsIgnoreCase(item.act()));
            if (!hasEvidence && !hasAct) {
                missingActs.add(required);
                continue;
            }
            DraftWindowObligationValidator.ClosingAct closingAct = acts.stream()
                    .filter(item -> act.equalsIgnoreCase(item.act()))
                    .findFirst().orElse(null);
            DraftWindowObligationValidator.ClosureEvidence closureEvidence = evidence.stream()
                    .filter(item -> act.equalsIgnoreCase(item.act()))
                    .findFirst().orElse(null);
            String mismatch = structuredMismatch(act, contract, closingAct, closureEvidence);
            if (mismatch != null) {
                String code = mismatch.startsWith("culpritKey=")
                        ? "CLOSURE_CULPRIT_MISMATCH"
                        : mismatch.startsWith("tag=") || mismatch.startsWith("relationshipThreadKey=")
                        ? "CLOSURE_TAG_MISMATCH" : "CLOSURE_EVIDENCE_MISMATCH";
                issues.add(issue(code, window, contract, List.of(required), mismatch));
                continue;
            }
            if (!tagMatches(act, contract, closingAct, closureEvidence)) {
                issues.add(issue("CLOSURE_TAG_MISMATCH", window, contract,
                        List.of(required), "tagMismatch"));
            }
        }
        if (!missingActs.isEmpty()) {
            issues.add(issue("CLOSURE_WINDOW_MISSING", window, contract,
                    missingActs, "missingActs"));
        }
        Set<String> proofKeys = new LinkedHashSet<>();
        evidence.forEach(item -> proofKeys.addAll(item.proofEventKeys()));
        List<String> missingProof = contract.proofEventKeys().stream()
                .filter(key -> !proofKeys.contains(key))
                .toList();
        if (!missingProof.isEmpty()) {
            issues.add(issue("CLOSURE_PROOF_MISSING", window, contract,
                    missingProof, "proofEventKeys"));
        }
        return List.copyOf(issues);
    }

    private static String structuredMismatch(String act,
                                             StoryClosureContract contract,
                                             DraftWindowObligationValidator.ClosingAct closingAct,
                                             DraftWindowObligationValidator.ClosureEvidence evidence) {
        Map<String, Object> payload = contract.payload();
        return switch (act) {
            case "CULPRIT" -> culpritMismatch(contract, closingAct, evidence);
            case "MOTIVE" -> tagValueMismatch(payload.get("motiveTag"),
                    evidence == null ? "" : evidence.motiveTag(),
                    evidence, closingAct);
            case "METHOD" -> tagValueMismatch(payload.get("methodTag"),
                    evidence == null ? "" : evidence.methodTag(),
                    evidence, closingAct);
            case "RELATIONSHIP_DECISION" -> relationshipMismatch(contract, closingAct, evidence);
            default -> null;
        };
    }

    private static String culpritMismatch(StoryClosureContract contract,
                                          DraftWindowObligationValidator.ClosingAct closingAct,
                                          DraftWindowObligationValidator.ClosureEvidence evidence) {
        String actual = evidence != null && hasText(evidence.culpritKey())
                ? evidence.culpritKey()
                : closingAct != null ? closingAct.targetKey() : "";
        if (!hasText(actual)) {
            return "culpritKey missing";
        }
        Object expected = contract.payload().get("culpritKey");
        if (expected != null && hasText(String.valueOf(expected))
                && !String.valueOf(expected).equals(actual)) {
            return "culpritKey=" + actual + " expected=" + expected;
        }
        Object castKeys = contract.payload().get("castKeys");
        if (expected == null && castKeys instanceof List<?> keys && !keys.isEmpty()
                && keys.stream().noneMatch(key -> String.valueOf(key).equals(actual))) {
            return "culpritKey=" + actual + " not in castKeys";
        }
        return null;
    }

    private static String relationshipMismatch(
            StoryClosureContract contract,
            DraftWindowObligationValidator.ClosingAct closingAct,
            DraftWindowObligationValidator.ClosureEvidence evidence) {
        String actual = evidence != null && hasText(evidence.relationshipThreadKey())
                ? evidence.relationshipThreadKey()
                : closingAct != null ? closingAct.targetKey() : "";
        Object expected = contract.payload().get("relationshipThreadKey");
        String expectText = expected == null ? contract.targetKey() : String.valueOf(expected);
        if (!hasText(actual) || !expectText.equals(actual)) {
            return "relationshipThreadKey=" + actual + " expected=" + expectText;
        }
        return null;
    }

    private static String tagValueMismatch(Object expected, String structuredTag,
                                           DraftWindowObligationValidator.ClosureEvidence evidence,
                                           DraftWindowObligationValidator.ClosingAct closingAct) {
        String actual = hasText(structuredTag) ? structuredTag
                : evidence != null && hasText(evidence.tag()) ? evidence.tag()
                : closingAct != null ? closingAct.tag() : "";
        if (expected == null || !hasText(String.valueOf(expected))) {
            return null;
        }
        if (!String.valueOf(expected).equals(actual)) {
            return "tag=" + actual + " expected=" + expected;
        }
        return null;
    }

    private static boolean tagMatches(String act,
                                      StoryClosureContract contract,
                                      DraftWindowObligationValidator.ClosingAct closingAct,
                                      DraftWindowObligationValidator.ClosureEvidence evidence) {
        String actual = evidence != null && hasText(evidence.tag()) ? evidence.tag()
                : closingAct != null ? closingAct.tag() : "";
        if (!hasText(actual)) {
            return true;
        }
        if (contract.requiredTags().isEmpty()) {
            return true;
        }
        if ("MOTIVE".equals(act) || "METHOD".equals(act)) {
            Object expected = contract.payload().get(
                    "MOTIVE".equals(act) ? "motiveTag" : "methodTag");
            if (expected != null && hasText(String.valueOf(expected))) {
                return String.valueOf(expected).equals(actual);
            }
        }
        if ("RELATIONSHIP_DECISION".equals(act)) {
            return true;
        }
        return contract.requiredTags().stream().anyMatch(actual::equals);
    }

    private static PlanContracts.PlanIssue issue(String code,
                                                 DraftWindowObligationValidator.DraftWindow window,
                                                 StoryClosureContract contract,
                                                 List<String> missing,
                                                 String detail) {
        int windowNo = window.fromChapter();
        String message = "errorCode=" + code
                + " windowNo=" + windowNo
                + " chapterNo=" + contract.chapterNo()
                + " obligationKey=" + contract.closureKey()
                + " missing=" + missing
                + " detail=" + detail
                + " contractPayload=" + contract.payload()
                + " repairTarget=WINDOW[" + windowNo + "]";
        return new PlanContracts.PlanIssue("HARD", code,
                PlanContracts.RepairScope.DRAFT, null, contract.chapterNo(),
                List.of(), message);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}