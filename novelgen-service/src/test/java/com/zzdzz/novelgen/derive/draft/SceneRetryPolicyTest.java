package com.zzdzz.novelgen.derive.draft;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneRetryPolicyTest {

    @Mock
    private SceneWriter writer;

    private SceneRetryPolicy policy;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        policy = new SceneRetryPolicy(writer);
    }

    @Test
    void firstGoodAttemptStopsRetry() {
        when(writer.write(any(), any(), any(), any()))
                .thenReturn(draft(DraftFixtures.prose(1, 100)));

        SceneRetryPolicy.SceneRetryResult result =
                policy.writeWithRetry(scene(), context(), 3);

        assertFalse(result.degraded());
        assertEquals(1, result.attempts());
        verify(writer, times(1)).write(any(), any(), any(), any());
    }

    @Test
    void secondAttemptUsesExactFirstFailure() {
        when(writer.write(any(), any(), any(), any()))
                .thenThrow(new SceneDraftException("DRAFT_SCENE_INVALID", "机器 JSON 尾巴"))
                .thenReturn(draft(DraftFixtures.prose(1, 100)));

        SceneRetryPolicy.SceneRetryResult result =
                policy.writeWithRetry(scene(), context(), 2);

        assertFalse(result.degraded());
        assertEquals(2, result.attempts());
        verify(writer).write(eq(scene()), eq(context()),
                eq(List.of("DRAFT_SCENE_INVALID:机器 JSON 尾巴")), eq(""));
    }

    @Test
    void maxAttemptsReturnsBestNonemptyDegradedDraft() {
        when(writer.write(any(), any(), any(), any()))
                .thenThrow(new SceneDraftException("DRAFT_SCENE_INVALID", "过程话泄漏"))
                .thenReturn(draft("占位助手话"));

        SceneRetryPolicy.SceneRetryResult result =
                policy.writeWithRetry(scene(), context(), 2);

        assertTrue(result.degraded());
        assertEquals("占位助手话", result.best().text());
        assertEquals(List.of("DRAFT_SCENE_INVALID:过程话泄漏"), result.lastIssues());
    }

    @Test
    void allAttemptsEmptyReturnsNoDraft() {
        when(writer.write(any(), any(), any(), any()))
                .thenThrow(new SceneDraftException("DRAFT_SCENE_EMPTY", "空输出"));

        SceneRetryPolicy.SceneRetryResult result =
                policy.writeWithRetry(scene(), context(), 2);

        assertNull(result.best());
        assertEquals(2, result.attempts());
    }

    @Test
    void newerCleanAttemptWinsLengthTie() {
        when(writer.write(any(), any(), any(), any()))
                .thenThrow(new SceneDraftException("DRAFT_SCENE_INVALID", "首稿过程话"))
                .thenReturn(draft(DraftFixtures.prose(2, 100)));

        SceneRetryPolicy.SceneRetryResult result =
                policy.writeWithRetry(scene(), context(), 2);

        assertEquals(2, result.attempts());
        assertTrue(result.best().text().contains("场景2"));
    }

    private static SceneScript scene() {
        return new SceneScript(1, "B1-S1", "完成场景推进", 80,
                List.of(), List.of());
    }

    private static SceneContext context() {
        return new SceneContext(59L, 4, 1, "前章摘要", Map.of());
    }

    private static SceneDraft draft(String text) {
        return new SceneDraft(1, text, text.replaceAll("\\s", "").length());
    }
}

