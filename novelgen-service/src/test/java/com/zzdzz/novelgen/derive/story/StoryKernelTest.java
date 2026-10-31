package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryKernelTest {

    @Test
    void kernelRejectsMysterySpecificFields() {
        List<String> forbidden = List.of("culprit", "motive", "method", "revealChapter");

        for (Class<?> type : List.of(StoryKernel.class, StoryEntity.class, StoryRelation.class,
                StoryState.class, StoryThread.class, StoryEvent.class, StoryObligation.class,
                StoryConstraint.class, StoryClosure.class)) {
            assertFalse(Arrays.stream(type.getDeclaredFields())
                    .anyMatch(field -> forbidden.contains(field.getName())),
                    type.getSimpleName() + " 泄漏悬疑专属字段");
        }
    }

    @Test
    void entityKeysAreUnique() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StoryKernel(1L, 1, "ACTIVE", scope(),
                        List.of(entity("E1"), entity("E1")), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()));

        assertTrue(error.getMessage().contains("重复"));
    }

    @Test
    void obligationChapterMustBePositive() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StoryObligation("CH0:X:E1", 0, "X", "E1", true, false,
                        "HARD", "SCHEDULE", Map.of()));

        assertTrue(error.getMessage().contains("chapterNo"));
    }

    @Test
    void obligationKindIsRequired() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StoryObligation("CH1::E1", 1, " ", "E1", true, false,
                        "HARD", "SCHEDULE", Map.of()));

        assertTrue(error.getMessage().contains("kind"));
    }

    @Test
    void constraintRangeIsValid() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StoryConstraint("K1", "CHAPTER", 5, 4, "FORBIDDEN", "E1", "范围倒置"));

        assertTrue(error.getMessage().contains("chapterFrom"));
    }

    @Test
    void closureRequiresTargetAndChapter() {
        IllegalArgumentException targetError = assertThrows(IllegalArgumentException.class,
                () -> new StoryClosure("K1", "THREAD", " ", 4, true, List.of()));
        assertTrue(targetError.getMessage().contains("targetKey"));

        IllegalArgumentException chapterError = assertThrows(IllegalArgumentException.class,
                () -> new StoryClosure("K1", "THREAD", "T1", 0, true, List.of()));
        assertTrue(chapterError.getMessage().contains("requiredChapter"));
    }

    @Test
    void kernelCopiesNullSafeCollections() {
        StoryKernel kernel = new StoryKernel(1L, 1, "ACTIVE", scope(), List.of(entity("E1")),
                null, null, null, null, null, null, null);

        assertEquals(1, kernel.entities().size());
        assertTrue(kernel.relations().isEmpty());
        assertTrue(kernel.obligations().isEmpty());
    }

    private static PlanContracts.ScopeContract scope() {
        return new PlanContracts.ScopeContract(PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 4,
                2000, List.of("测试"));
    }

    private static StoryEntity entity(String key) {
        return new StoryEntity(key, "CHARACTER", key, Map.of(), 1);
    }
}
