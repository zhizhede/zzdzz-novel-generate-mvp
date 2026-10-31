package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.derive.story.StoryObligationEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysteryQcAuditorTest {

    @Test
    void rejectsMissingRequiredObligationInText() {
        StoryKernel kernel = kernel(List.of(obligation(1, "INTRODUCE_ENTITY", "C1")));
        GenreQcAuditor.CompiledDraft draft = draft(Map.of(1, "正文没有目标"));

        assertTrue(hasCode(new MysteryQcAuditor().audit(draft, kernel, new MysteryProfile()),
                "OBLIGATION_MISSING"));
    }

    @Test
    void rejectsWorldStateConflict() {
        GenreQcAuditor.CompiledDraft draft = new GenreQcAuditor.CompiledDraft(List.of(
                new GenreQcAuditor.ChapterText(1, "正文")), Map.of(1, Map.of("C1|LOCATION", "码头"),
                2, Map.of("C1|LOCATION", "车站")));

        assertTrue(hasCode(new MysteryQcAuditor().audit(draft, kernel(List.of()), new MysteryProfile()),
                "STATE_CONFLICT"));
    }

    @Test
    void rejectsMysteryTruthNotPaidOff() {
        StoryKernel kernel = kernel(List.of(obligation(2, "FINAL_CLOSURE", "TRUTH")));
        GenreQcAuditor.CompiledDraft draft = draft(Map.of(2, "正文没有答案"));

        assertTrue(hasCode(new MysteryQcAuditor().audit(draft, kernel, new MysteryProfile()),
                "OBLIGATION_MISSING"));
    }

    @Test
    void acceptsValidDraft() {
        List<StoryObligation> obligations = List.of(
                obligation(1, "INTRODUCE_ENTITY", "C1"),
                obligation(2, "FINAL_CLOSURE", "TRUTH"));
        StoryEvidenceIndex.Builder evidence = StoryEvidenceIndex.builder();
        evidence.addObligationEvidence(new StoryObligationEvidence(
                obligations.get(0).obligationKey(), 1, "CHAPTER_OUTLINE", "EV1",
                null, List.of("C1"), List.of(), "", "QC", 1.0));
        evidence.addObligationEvidence(new StoryObligationEvidence(
                obligations.get(1).obligationKey(), 2, "EVENT", "EV2",
                null, List.of(), List.of(), "", "QC", 1.0));
        StoryKernel kernel = new StoryKernel(1L, 1, "ACTIVE", null, List.of(), List.of(),
                List.of(), List.of(), List.of(), obligations, List.of(), List.of(),
                evidence.build());
        GenreQcAuditor.CompiledDraft draft = draft(Map.of(1, "C1 登场", 2, "真相揭晓"));

        assertFalse(hasCode(new MysteryQcAuditor().audit(draft, kernel, new MysteryProfile()),
                "OBLIGATION_MISSING"));
    }

    private static StoryKernel kernel(List<StoryObligation> obligations) {
        return new StoryKernel(1L, 1, "ACTIVE", null, List.of(), List.of(), List.of(), List.of(),
                List.of(), obligations, List.of(), List.of());
    }

    private static GenreQcAuditor.CompiledDraft draft(Map<Integer, String> chapters) {
        List<GenreQcAuditor.ChapterText> rows = chapters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new GenreQcAuditor.ChapterText(entry.getKey(), entry.getValue()))
                .toList();
        return new GenreQcAuditor.CompiledDraft(rows, Map.of());
    }

    private static StoryObligation obligation(int chapter, String kind, String target) {
        return new StoryObligation(StoryObligation.obligationKey(chapter, kind, target), chapter,
                kind, target, true, false, "HARD", "TEST", Map.of());
    }

    private static boolean hasCode(List<PlanContracts.PlanIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.code()));
    }
}
