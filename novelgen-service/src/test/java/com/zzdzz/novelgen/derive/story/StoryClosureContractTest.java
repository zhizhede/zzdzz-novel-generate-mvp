package com.zzdzz.novelgen.derive.story;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryClosureContractTest {

    @Test
    void closureContractRequiresActsAndTags() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StoryClosureContract("CH5:FINAL_CLOSURE:TRUTH", 5, "TRUTH",
                        "TRUTH", List.of(), List.of(), List.of(), "MYSTERY"));

        assertTrue(error.getMessage().contains("requiredActs"));
    }

    @Test
    void windowScopeDefersFutureObligations() {
        StoryObligation future = obligation(5, "FINAL_CLOSURE", "TRUTH");
        WindowObligationScope scope = WindowObligationScope.of(1, 4, List.of(future));

        assertTrue(scope.current().isEmpty());
        assertEquals(List.of(future), scope.deferred());
        assertEquals(List.of(), scope.future());
    }

    @Test
    void windowScopeKeepsCurrentObligations() {
        StoryObligation current = obligation(3, "PAYOFF_THREAD", "GM-CLUE-01");
        WindowObligationScope scope = WindowObligationScope.of(1, 4, List.of(current));

        assertEquals(List.of(current), scope.current());
        assertTrue(scope.deferred().isEmpty());
        assertTrue(scope.future().isEmpty());
    }

    @Test
    void windowScopeFlagsPastUnfulfilledObligations() {
        StoryObligation past = obligation(1, "PLANT_THREAD", "GM-CLUE-01");
        WindowObligationScope scope = WindowObligationScope.of(2, 4, List.of(past));

        assertTrue(scope.current().isEmpty());
        assertTrue(scope.deferred().isEmpty());
        assertEquals(List.of(past), scope.future());
    }

    private static StoryObligation obligation(int chapterNo, String kind, String target) {
        return new StoryObligation(StoryObligation.obligationKey(chapterNo, kind, target),
                chapterNo, kind, target, true, false, "HARD", "TEST", java.util.Map.of());
    }
}
