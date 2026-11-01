package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosureWindowValidatorTest {

    @Test
    void missingCulpritActRejects() {
        DraftWindowObligationValidator.DraftWindow window = window(5,
                new DraftWindowObligationValidator.ClosingAct("MOTIVE", "C3", "旧账"),
                List.of(new DraftWindowObligationValidator.ClosureEvidence(
                        "CH5:FINAL_CLOSURE:TRUTH", 5, "CULPRIT", "EV1")));

        assertTrue(hasCode(validate(window, contract()), "CLOSURE_WINDOW_MISSING"));
    }

    @Test
    void missingMotiveTagRejects() {
        DraftWindowObligationValidator.DraftWindow window = window(5,
                new DraftWindowObligationValidator.ClosingAct("MOTIVE", "C3", "别的动机"),
                List.of(new DraftWindowObligationValidator.ClosureEvidence(
                        "CH5:FINAL_CLOSURE:TRUTH", 5, "MOTIVE", "EV1", "别的动机")));

        assertTrue(hasCode(validate(window, contract()), "CLOSURE_TAG_MISMATCH"));
    }

    @Test
    void wrongCulpritKeyRejects() {
        DraftWindowObligationValidator.DraftWindow window = window(5,
                new DraftWindowObligationValidator.ClosingAct("CULPRIT", "C2", "旧账"),
                List.of(new DraftWindowObligationValidator.ClosureEvidence(
                        "CH5:FINAL_CLOSURE:TRUTH", 5, "CULPRIT", "EV1")));

        assertTrue(hasCode(validate(window, contract()), "CLOSURE_CULPRIT_MISMATCH"));
    }

    @Test
    void futureClosureIsDeferred() {
        StoryClosureContract future = new StoryClosureContract("CH6:FINAL_CLOSURE:TRUTH",
                6, "TRUTH", "TRUTH", List.of("CULPRIT", "MOTIVE", "METHOD"),
                List.of("旧账", "换班作案"), List.of(), "MYSTERY");
        DraftWindowObligationValidator.DraftWindow window = window(5,
                new DraftWindowObligationValidator.ClosingAct("CULPRIT", "C3", "旧账"),
                List.of());

        assertFalse(hasCode(validate(window, future), "CLOSURE_WINDOW_MISSING"));
    }

    @Test
    void validClosureAccepts() {
        DraftWindowObligationValidator.DraftWindow window = window(5,
                new DraftWindowObligationValidator.ClosingAct("CULPRIT", "C3", "旧账"),
                List.of(
                        new DraftWindowObligationValidator.ClosureEvidence(
                                "CH5:FINAL_CLOSURE:TRUTH", 5, "CULPRIT", "EV1"),
                        new DraftWindowObligationValidator.ClosureEvidence(
                                "CH5:FINAL_CLOSURE:TRUTH", 5, "MOTIVE", "EV2", "旧账"),
                        new DraftWindowObligationValidator.ClosureEvidence(
                                "CH5:FINAL_CLOSURE:TRUTH", 5, "METHOD", "EV3", "换班作案")));

        List<PlanContracts.PlanIssue> issues = validate(window, contract());


        assertFalse(hasCode(issues, "CLOSURE_WINDOW_MISSING"));
        assertFalse(hasCode(issues, "CLOSURE_TAG_MISMATCH"));
        assertFalse(hasCode(issues, "CLOSURE_CULPRIT_MISMATCH"));
    }

    private static List<PlanContracts.PlanIssue> validate(
            DraftWindowObligationValidator.DraftWindow window, StoryClosureContract contract) {
        return new ClosureWindowValidator().validate(window, contract);
    }

    private static boolean hasCode(List<PlanContracts.PlanIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static DraftWindowObligationValidator.DraftWindow window(
            int chapterNo, DraftWindowObligationValidator.ClosingAct act,
            List<DraftWindowObligationValidator.ClosureEvidence> evidence) {
        return new DraftWindowObligationValidator.DraftWindow(chapterNo, chapterNo,
                List.of(new DraftWindowObligationValidator.ChapterDraft(chapterNo,
                        "揭示真相", List.of(), List.of("C1", "C3"), List.of())),
                List.of(), Map.of(), Map.of(), Set.of("C1", "C3"), List.of(act), evidence);
    }

    private static StoryClosureContract contract() {
        return new StoryClosureContract("CH5:FINAL_CLOSURE:TRUTH", 5, "TRUTH", "TRUTH",
                List.of("CULPRIT", "MOTIVE", "METHOD"), List.of("旧账", "换班作案"),
                List.of(), "MYSTERY", java.util.Map.of(
                        "culpritKey", "C3", "motiveTag", "旧账", "methodTag", "换班作案"));
    }
}
