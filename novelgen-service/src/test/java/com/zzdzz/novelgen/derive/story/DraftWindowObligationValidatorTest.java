package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftWindowObligationValidatorTest {

    private static final StoryObligation PAYOFF =
            obligation(3, "PAYOFF_THREAD", "GM-CLUE-01");
    private static final StoryObligation PLANT =
            obligation(1, "PLANT_THREAD", "GM-CLUE-01");

    @Test
    void rejectsEarlyPayoff() {
        DraftWindowObligationValidator.DraftWindow window = window(
                chapter(1, List.of("PAYOFF:GM-CLUE-01"), List.of("C1"), List.of("GM-CLUE-01")));

        assertTrue(hasCode(validate(window, List.of(PLANT, PAYOFF)), "OBLIGATION_EARLY"));
    }

    @Test
    void rejectsDuplicatePayoff() {
        DraftWindowObligationValidator.DraftWindow window = window(
                chapter(3, List.of("PAYOFF:GM-CLUE-01"), List.of("C1"), List.of("GM-CLUE-01")),
                chapter(4, List.of("PAYOFF:GM-CLUE-01"), List.of("C1"), List.of("GM-CLUE-01")));

        assertTrue(hasCode(validate(window, List.of(PAYOFF)), "OBLIGATION_VIOLATED"));
    }

    @Test
    void rejectsMissingRequiredObligation() {
        DraftWindowObligationValidator.DraftWindow window = window(
                chapter(1, List.of(), List.of("C1"), List.of()));

        assertTrue(hasCode(validate(window, List.of(PLANT)), "OBLIGATION_MISSING"));
    }

    @Test
    void rejectsForbiddenEvent() {
        StoryObligation forbidden = new StoryObligation(
                StoryObligation.obligationKey(1, "FORBIDDEN", "C1"), 1, "FORBIDDEN", "C1",
                false, true, "HARD", "TEST", Map.of());
        DraftWindowObligationValidator.DraftWindow window = window(
                chapter(1, List.of(), List.of("C1"), List.of()));

        assertTrue(hasCode(validate(window, List.of(forbidden)), "OBLIGATION_VIOLATED"));
    }

    @Test
    void rejectsStateConflict() {
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator.DraftWindow(
                1, 1, List.of(chapter(1, List.of(), List.of("C1"), List.of())),
                List.of(), Map.of(1, List.of(
                        new DraftWindowObligationValidator.StateChange("C1", "LOCATION", "码头"),
                        new DraftWindowObligationValidator.StateChange("C1", "LOCATION", "车站"))),
                Map.of(), Set.of("C1"));

        assertTrue(hasCode(validate(window, List.of()), "STATE_CONFLICT"));
    }

    @Test
    void rejectsKnowledgeReadBeforeWrite() {
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator.DraftWindow(
                1, 2, List.of(chapter(1, List.of(), List.of("C1"), List.of()),
                        chapter(2, List.of(), List.of("C1"), List.of())),
                List.of(), Map.of(), Map.of(
                        1, List.of(new DraftWindowObligationValidator.KnowledgeEvent("READ", "K1")),
                        2, List.of(new DraftWindowObligationValidator.KnowledgeEvent("WRITE", "K1"))),
                Set.of("C1"));

        assertTrue(hasCode(validate(window, List.of()), "KNOWLEDGE_SOURCE_MISSING"));
    }

    @Test
    void rejectsContractOutsideEntity() {
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator.DraftWindow(
                1, 1, List.of(), List.of(new DraftWindowObligationValidator.BeatDraft(
                        1, 1, "HOOK", List.of(), List.of("C1"), List.of("OUTSIDE"))),
                Map.of(), Map.of(), Set.of("C1"));

        assertTrue(hasCode(validate(window, List.of()), "CONTRACT_OUT_OF_SCOPE"));
    }

    @Test
    void acceptsValidWindow() {
        DraftWindowObligationValidator.DraftWindow window = window(
                chapter(1, List.of("PLANT:GM-CLUE-01"), List.of("C1"), List.of("GM-CLUE-01")),
                chapter(3, List.of("PAYOFF:GM-CLUE-01"), List.of("C1"), List.of("GM-CLUE-01")));

        assertFalse(hasCode(validate(window, List.of(PLANT, PAYOFF)), "OBLIGATION_MISSING"));
    }

    private static List<PlanContracts.PlanIssue> validate(
            DraftWindowObligationValidator.DraftWindow window, List<StoryObligation> obligations) {
        return new DraftWindowObligationValidator().validate(window, obligations);
    }

    private static boolean hasCode(List<PlanContracts.PlanIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static DraftWindowObligationValidator.DraftWindow window(
            DraftWindowObligationValidator.ChapterDraft... chapters) {
        return new DraftWindowObligationValidator.DraftWindow(1, 4, List.of(chapters), List.of(),
                Map.of(), Map.of(), Set.of("C1"));
    }

    private static DraftWindowObligationValidator.ChapterDraft chapter(
            int chapterNo, List<String> clueActs, List<String> entities, List<String> threads) {
        return new DraftWindowObligationValidator.ChapterDraft(chapterNo, "正文",
                clueActs, entities, threads);
    }

    private static StoryObligation obligation(int chapterNo, String kind, String target) {
        return new StoryObligation(StoryObligation.obligationKey(chapterNo, kind, target),
                chapterNo, kind, target, true, false, "HARD", "TEST", Map.of());
    }
}
