package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualProbeClosureWindowTest {

    @Test
    void futureClosureIsDeferred() {
        StoryClosureContract future = mysteryContract(6);
        DraftWindowObligationValidator.DraftWindow window = mysteryWindow(5);

        assertFalse(hasCode(validate(window, future), "CLOSURE_WINDOW_MISSING"));
    }

    @Test
    void currentClosureRequiresActs() {
        StoryClosureContract contract = mysteryContract(5);
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator.DraftWindow(
                5, 5, mysteryChapters(5), List.of(), Map.of(), Map.of(),
                Set.of("C1", "C3"), List.of(), List.of());

        assertTrue(hasCode(validate(window, contract), "CLOSURE_WINDOW_MISSING"));
    }

    @Test
    void currentClosureRequiresTags() {
        StoryClosureContract contract = mysteryContract(5);
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator.DraftWindow(
                5, 5, mysteryChapters(5), List.of(), Map.of(), Map.of(),
                Set.of("C1", "C3"),
                List.of(
                        new DraftWindowObligationValidator.ClosingAct("CULPRIT", "C3", "C3"),
                        new DraftWindowObligationValidator.ClosingAct("MOTIVE", "C3", "错误动机"),
                        new DraftWindowObligationValidator.ClosingAct("METHOD", "C3", "错误手法")),
                List.of(evidence("CULPRIT", "C3", "", List.of()),
                        evidence("MOTIVE", "", "错误动机", List.of()),
                        evidence("METHOD", "", "错误手法", List.of())));

        assertTrue(hasAnyCode(validate(window, contract),
                "CLOSURE_TAG_MISMATCH", "CLOSURE_EVIDENCE_MISMATCH"));
    }

    @Test
    void currentClosureRequiresProofEvents() {
        StoryClosureContract contract = mysteryContract(5);
        DraftWindowObligationValidator.DraftWindow window = validMysteryWindow(List.of());

        assertTrue(hasCode(validate(window, contract), "CLOSURE_PROOF_MISSING"));
    }

    @Test
    void validClosureAcceptsStructuredEvidence() {
        StoryClosureContract contract = mysteryContract(5);
        DraftWindowObligationValidator.DraftWindow window = validMysteryWindow(
                List.of("GM-CLUE-01", "GM-CLUE-02"));

        assertFalse(hasCode(validate(window, contract), "CLOSURE_WINDOW_MISSING"));
        assertFalse(hasCode(validate(window, contract), "CLOSURE_TAG_MISMATCH"));
        assertFalse(hasCode(validate(window, contract), "CLOSURE_EVIDENCE_MISMATCH"));
        assertFalse(hasCode(validate(window, contract), "CLOSURE_PROOF_MISSING"));
    }

    @Test
    void pastUnfulfilledClosureFails() {
        StoryClosureContract contract = mysteryContract(4);
        DraftWindowObligationValidator.DraftWindow window = validMysteryWindow(
                List.of("GM-CLUE-01", "GM-CLUE-02"));

        assertTrue(hasCode(validate(window, contract), "CLOSURE_WINDOW_MISSING"));
    }

    @Test
    void pastFulfilledClosureIsAcceptedWithPriorEvidence() {
        StoryClosureContract contract = mysteryContract(4);
        DraftWindowObligationValidator.DraftWindow window = validMysteryWindow(
                List.of("GM-CLUE-01", "GM-CLUE-02"));
        StoryEvidenceIndex prior = StoryEvidenceIndex.builder()
                .addObligationEvidence(new StoryObligationEvidence(
                        contract.closureKey(), 4, "CLOSURE_EVIDENCE", "CH4:CLOSURE", null,
                        List.of("C3"), List.of("GM-CLUE-01"), "已闭合", "BATCH", 1.0))
                .build();

        assertFalse(hasCode(new ClosureWindowValidator().validate(window, contract, prior),
                "CLOSURE_WINDOW_MISSING"));
    }

    @Test
    void romanceClosureAcceptsRelationshipDecision() {
        StoryClosureContract contract = new StoryClosureContract(
                "CH7:FINAL_CLOSURE:REL-C1-C2", 7, "REL-C1-C2", "FINAL_CLOSURE",
                List.of("RELATIONSHIP_DECISION"), List.of("REL-C1-C2"),
                List.of("ROM-STAGE-CONFESSION"), "ROMANCE_CONTRACT",
                Map.of("relationshipThreadKey", "REL-C1-C2"));
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator.DraftWindow(
                7, 7,
                List.of(new DraftWindowObligationValidator.ChapterDraft(
                        7, "关系结局", List.of(), List.of("C1", "C2"), List.of("REL-C1-C2"))),
                List.of(), Map.of(), Map.of(), Set.of("C1", "C2"),
                List.of(new DraftWindowObligationValidator.ClosingAct(
                        "RELATIONSHIP_DECISION", "REL-C1-C2", "REL-C1-C2")),
                List.of(new DraftWindowObligationValidator.ClosureEvidence(
                        contract.closureKey(), 7, "RELATIONSHIP_DECISION", "CH7:ROMANCE:CLOSURE",
                        "REL-C1-C2", "", "", "", "REL-C1-C2",
                        List.of("ROM-STAGE-CONFESSION"))));

        assertFalse(hasCode(validate(window, contract), "CLOSURE_WINDOW_MISSING"));
        assertFalse(hasCode(validate(window, contract), "CLOSURE_EVIDENCE_MISMATCH"));
        assertFalse(hasCode(validate(window, contract), "CLOSURE_PROOF_MISSING"));
    }

    private static List<PlanContracts.PlanIssue> validate(
            DraftWindowObligationValidator.DraftWindow window, StoryClosureContract contract) {
        return new ClosureWindowValidator().validate(window, contract);
    }

    private static boolean hasCode(List<PlanContracts.PlanIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static boolean hasAnyCode(List<PlanContracts.PlanIssue> issues, String... codes) {
        return issues.stream().anyMatch(issue -> java.util.Arrays.asList(codes).contains(issue.code()));
    }

    private static DraftWindowObligationValidator.DraftWindow mysteryWindow(int chapterNo) {
        return new DraftWindowObligationValidator.DraftWindow(chapterNo, chapterNo,
                mysteryChapters(chapterNo), List.of(), Map.of(), Map.of(),
                Set.of("C1", "C3"), List.of(), List.of());
    }

    private static List<DraftWindowObligationValidator.ChapterDraft> mysteryChapters(int chapterNo) {
        return List.of(new DraftWindowObligationValidator.ChapterDraft(
                chapterNo, "揭示真相", List.of(), List.of("C1", "C3"), List.of()));
    }

    private static DraftWindowObligationValidator.DraftWindow validMysteryWindow(
            List<String> proofEventKeys) {
        return new DraftWindowObligationValidator.DraftWindow(5, 5, mysteryChapters(5),
                List.of(), Map.of(), Map.of(), Set.of("C1", "C3"),
                List.of(
                        new DraftWindowObligationValidator.ClosingAct("CULPRIT", "C3", "C3"),
                        new DraftWindowObligationValidator.ClosingAct("MOTIVE", "C3", "旧账"),
                        new DraftWindowObligationValidator.ClosingAct("METHOD", "C3", "换班作案")),
                List.of(
                        evidence("CULPRIT", "C3", "", proofEventKeys),
                        evidence("MOTIVE", "", "旧账", proofEventKeys),
                        evidence("METHOD", "", "换班作案", proofEventKeys)));
    }

    private static DraftWindowObligationValidator.ClosureEvidence evidence(
            String act, String culpritKey, String tag, List<String> proofEventKeys) {
        return new DraftWindowObligationValidator.ClosureEvidence(
                "CH5:FINAL_CLOSURE:TRUTH", 5, act, "EV-" + act, tag,
                culpritKey, "MOTIVE".equals(act) ? tag : "",
                "METHOD".equals(act) ? tag : "", "", proofEventKeys);
    }

    private static StoryClosureContract mysteryContract(int chapterNo) {
        return new StoryClosureContract(
                "CH" + chapterNo + ":FINAL_CLOSURE:TRUTH", chapterNo, "TRUTH", "FINAL_CLOSURE",
                List.of("CULPRIT", "MOTIVE", "METHOD"),
                List.of("C3", "旧账", "换班作案"),
                List.of("GM-CLUE-01", "GM-CLUE-02"), "MYSTERY_CONTRACT",
                Map.of("culpritKey", "C3", "motiveTag", "旧账", "methodTag", "换班作案",
                        "castKeys", List.of("C1", "C3")));
    }
}