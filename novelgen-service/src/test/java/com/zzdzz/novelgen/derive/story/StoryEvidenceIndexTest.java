package com.zzdzz.novelgen.derive.story;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryEvidenceIndexTest {

    @Test
    void evidenceIndexGroupsByChapter() {
        StoryEvent event = event("EV1", 2);
        StoryObligationEvidence evidence = evidence("CH2:PLANT_THREAD:GM-CLUE-01", 2,
                "BEAT", "EV1");
        StoryThread thread = thread("GM-CLUE-01", 2);

        StoryEvidenceIndex index = StoryEvidenceIndex.builder()
                .addEvent(event)
                .addObligationEvidence(evidence)
                .addThread(thread)
                .build();

        assertEquals(List.of(event), index.eventsByChapter().get(2));
        assertEquals(List.of(evidence), index.obligationsByChapter().get(2));
        assertEquals(List.of(thread), index.threadsByChapter().get(2));
    }

    @Test
    void evidenceIndexFindsObligationEvidence() {
        StoryObligationEvidence first = evidence("CH2:PLANT_THREAD:GM-CLUE-01", 2,
                "BEAT", "EV1");
        StoryObligationEvidence second = evidence("CH3:PAYOFF_THREAD:GM-CLUE-01", 3,
                "THREAD_EFFECT", "EV2");

        StoryEvidenceIndex index = StoryEvidenceIndex.builder()
                .addObligationEvidence(first)
                .addObligationEvidence(second)
                .build();

        assertTrue(index.hasEvidence("CH2:PLANT_THREAD:GM-CLUE-01"));
        assertTrue(index.hasEvidence("CH3:PAYOFF_THREAD:GM-CLUE-01"));
        assertEquals(List.of(first), index.evidenceFor("CH2:PLANT_THREAD:GM-CLUE-01"));
        assertFalse(index.hasEvidence("CH2:PAYOFF_THREAD:GM-CLUE-01"));
    }

    @Test
    void evidenceIndexRejectsDuplicateEvidenceKey() {
        StoryObligationEvidence duplicate = evidence("CH2:PLANT_THREAD:GM-CLUE-01", 2,
                "BEAT", "EV1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StoryEvidenceIndex.builder()
                        .addObligationEvidence(duplicate)
                        .addObligationEvidence(duplicate)
                        .build());

        assertTrue(error.getMessage().contains("证据 key 重复"));
    }

    @Test
    void evidenceIndexDoesNotTreatObligationKeyAsText() {
        StoryEvidenceIndex index = StoryEvidenceIndex.builder()
                .addObligationEvidence(evidence("CH2:PLANT_THREAD:GM-CLUE-01", 2,
                        "BEAT", "EV1"))
                .build();

        assertFalse(index.hasEvidence("GM-CLUE-01"));
        assertFalse(index.hasEvidence("正文里出现了 GM-CLUE-01"));
    }

    @Test
    void evidenceRecordRejectsInvalidConfidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new StoryObligationEvidence("CH2:X:E1", 2, "BEAT", "EV1", 1,
                        List.of(), List.of(), "fact", "BATCH", 1.1));
    }

    private static StoryEvent event(String eventKey, int chapterNo) {
        return new StoryEvent(eventKey, chapterNo, "SCENE", List.of(), List.of(),
                "", "", List.of(), List.of(), List.of(), "BATCH");
    }

    private static StoryObligationEvidence evidence(String obligationKey, int chapterNo,
                                                     String evidenceKind, String eventKey) {
        return new StoryObligationEvidence(obligationKey, chapterNo, evidenceKind, eventKey, 1,
                List.of(), List.of(), "事实", "BATCH", 1.0);
    }

    private static StoryThread thread(String threadKey, int chapterNo) {
        return new StoryThread(threadKey, "CLUE", threadKey, chapterNo, "OPEN",
                "C1", null, Map.of());
    }
}
