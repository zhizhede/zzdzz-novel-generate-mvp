package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftWindowEvidenceValidatorTest {

    @Test
    void plantRequiresThreadEffectOrBeatEvidence() {
        DraftWindowObligationValidator.ChapterDraft chapter =
                new DraftWindowObligationValidator.ChapterDraft(1, "开局", List.of(),
                        List.of("C1"), List.of());
        DraftWindowObligationValidator.BeatDraft beat =
                new DraftWindowObligationValidator.BeatDraft(1, 1, "CLUE_PLANT",
                        List.of("GM-CLUE-01"), List.of("C1"), List.of());
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(1, 1, List.of(chapter), List.of(beat), Map.of(), Map.of(),
                Set.of("C1"));

        assertFalse(hasCode(validate(window, List.of(obligation(1, "PLANT_THREAD",
                "GM-CLUE-01"))), "OBLIGATION_MISSING"));
    }

    @Test
    void payoffRequiresThreadEffectOrBeatEvidence() {
        DraftWindowObligationValidator.BeatDraft beat =
                new DraftWindowObligationValidator.BeatDraft(2, 1, "SCENE",
                        List.of("GM-CLUE-01"), List.of("C1"), List.of(),
                        List.of(new DraftWindowObligationValidator.ThreadEffect(
                                "GM-CLUE-01", "PAYOFF")),
                        List.of(), "打卡记录已到手");
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(1, 1,
                List.of(new DraftWindowObligationValidator.ChapterDraft(1, "兑现", List.of(),
                        List.of("C1"), List.of("GM-CLUE-01"))),
                List.of(beat), Map.of(), Map.of(), Set.of("C1"));

        assertFalse(hasCode(validate(window, List.of(obligation(1, "PAYOFF_THREAD",
                "GM-CLUE-01"))), "OBLIGATION_MISSING"));
    }

    @Test
    void earlyPayoffRejects() {
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(1, 2,
                List.of(new DraftWindowObligationValidator.ChapterDraft(1, "提前", List.of(),
                                List.of("C1"), List.of()),
                        new DraftWindowObligationValidator.ChapterDraft(2, "兑现", List.of(),
                                List.of("C1"), List.of())),
                List.of(), Map.of(), Map.of(), Set.of("C1"));

        assertTrue(hasCode(validate(window, List.of(obligation(2, "PAYOFF_THREAD",
                "GM-CLUE-01"))), "OBLIGATION_MISSING"));
    }

    @Test
    void duplicatePayoffRejects() {
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(1, 2,
                List.of(new DraftWindowObligationValidator.ChapterDraft(1, "兑现一",
                                List.of("PAYOFF:GM-CLUE-01"), List.of("C1"), List.of()),
                        new DraftWindowObligationValidator.ChapterDraft(2, "兑现二",
                                List.of("PAYOFF:GM-CLUE-01"), List.of("C1"), List.of())),
                List.of(), Map.of(), Map.of(), Set.of("C1"));

        assertTrue(hasCode(validate(window, List.of(obligation(1, "PAYOFF_THREAD",
                "GM-CLUE-01"))), "OBLIGATION_VIOLATED"));
    }

    @Test
    void missingSetupRejects() {
        PlanContracts.CharacterContract character = new PlanContracts.CharacterContract(
                "C3", "陆敬", PlanContracts.CharacterFunction.OPPOSITION, "旧史人物",
                "掩盖旧账", "多疑", List.of("旧史铺垫"), List.of("C1"), "对峙",
                "选择公开旧账", "旧史被公开", List.of(), "", 1, 1, 1,
                "", "", "OPPOSITION");
        StoryObligation setup = new StoryObligation("CH1:INTRODUCE_ENTITY:C3", 1,
                "INTRODUCE_ENTITY", "C3", true, false, "HARD", "TEST",
                Map.of("setup", List.of("旧史铺垫")));
        StoryObligation choice = new StoryObligation("CH1:CHARACTER_CHOICE:C3", 1,
                "CHARACTER_CHOICE", "C3", true, false, "HARD", "TEST",
                Map.of("proof", "选择公开旧账"));
        StoryObligation cost = new StoryObligation("CH2:CHARACTER_COST:C3", 2,
                "CHARACTER_COST", "C3", true, false, "HARD", "TEST",
                Map.of("proof", "旧史被公开", "afterChapter", 1));
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(1, 2,
                List.of(new DraftWindowObligationValidator.ChapterDraft(1, "铺垫", List.of(),
                                List.of("C3"), List.of()),
                        new DraftWindowObligationValidator.ChapterDraft(2, "代价", List.of(),
                                List.of("C3"), List.of())),
                List.of(), Map.of(), Map.of(), Set.of("C1", "C3"));

        assertTrue(hasCode(validate(window, List.of(setup, choice, cost)),
                "OBLIGATION_MISSING_SETUP"));
    }

    @Test
    void stateConflictRejects() {
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(1, 1,
                List.of(new DraftWindowObligationValidator.ChapterDraft(1, "", List.of(),
                        List.of("C1"), List.of())),
                List.of(), Map.of(1, List.of(
                        new DraftWindowObligationValidator.StateChange("C1", "LOCATION", "码头"),
                        new DraftWindowObligationValidator.StateChange("C1", "LOCATION", "车站"))),
                Map.of(), Set.of("C1"));

        assertTrue(hasCode(validate(window, List.of()), "STATE_CONFLICT"));
    }

    @Test
    void knowledgeReadBeforeWriteRejects() {
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(1, 1,
                List.of(), List.of(),
                Map.of(), Map.of(1, List.of(
                        new DraftWindowObligationValidator.KnowledgeEvent("READ", "K1"))),
                Set.of("C1"));

        assertTrue(hasCode(validate(window, List.of()), "KNOWLEDGE_SOURCE_MISSING"));
    }

    @Test
    void outsideEntityRejects() {
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(1, 1,
                List.of(), List.of(new DraftWindowObligationValidator.BeatDraft(
                        1, 1, "HOOK", List.of(), List.of("C1"), List.of("OUTSIDE"))),
                Map.of(), Map.of(), Set.of("C1"));

        assertTrue(hasCode(validate(window, List.of()), "CONTRACT_OUT_OF_SCOPE"));
    }

    @Test
    void validEvidenceAccepts() {
        StoryObligation payoff = obligation(2, "PAYOFF_THREAD", "GM-CLUE-01");
        DraftWindowObligationValidator.ChapterDraft chapter =
                new DraftWindowObligationValidator.ChapterDraft(2, "线索兑现", List.of(),
                        List.of("C1"), List.of("GM-CLUE-01"));
        DraftWindowObligationValidator.BeatDraft beat =
                new DraftWindowObligationValidator.BeatDraft(5, 2, "SCENE", List.of(),
                        List.of("C1"), List.of(), List.of(new DraftWindowObligationValidator
                                .ThreadEffect("GM-CLUE-01", "PAYOFF")), List.of(),
                        "林默确认打卡记录指向仓库");
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(2, 2, List.of(chapter), List.of(beat), Map.of(), Map.of(),
                Set.of("C1"));

        StoryEvidenceIndex index = new DraftWindowObligationValidator().toEvidenceIndex(window);

        assertTrue(index.hasEvidence(payoff.obligationKey()));
        assertFalse(hasCode(validate(window, List.of(payoff)), "OBLIGATION_MISSING"));
    }

    @Test
    void romanceEventsProvideStageEvidence() {
        StoryObligation obligation = new StoryObligation("CH2:PAYOFF_THREAD:REL-C1-C2", 2,
                "PAYOFF_THREAD", "REL-C1-C2", true, false, "HARD", "ROMANCE", Map.of());
        DraftWindowObligationValidator.DraftWindow window = new DraftWindowObligationValidator
                .DraftWindow(2, 2,
                List.of(new DraftWindowObligationValidator.ChapterDraft(2, "告白",
                        List.of(), List.of("C1", "C2"), List.of("REL-C1-C2"),
                        List.of(new DraftWindowObligationValidator.RomanceEvent(
                                "CONFESSION", "REL-C1-C2", "REL-C1-C2",
                                "沈砚说明隐瞒原因并表达感情")))),
                List.of(), Map.of(), Map.of(), Set.of("C1", "C2"));

        assertTrue(new DraftWindowObligationValidator().toEvidenceIndex(window)
                .hasEvidence(obligation.obligationKey()));
        assertFalse(hasCode(validate(window, List.of(obligation)), "OBLIGATION_MISSING"));
    }

    private static List<PlanContracts.PlanIssue> validate(
            DraftWindowObligationValidator.DraftWindow window, List<StoryObligation> obligations) {
        return new DraftWindowObligationValidator().validate(window, obligations);
    }

    private static boolean hasCode(List<PlanContracts.PlanIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static StoryObligation obligation(int chapterNo, String kind, String target) {
        return new StoryObligation(StoryObligation.obligationKey(chapterNo, kind, target),
                chapterNo, kind, target, true, false, "HARD", "TEST", Map.of());
    }
}
