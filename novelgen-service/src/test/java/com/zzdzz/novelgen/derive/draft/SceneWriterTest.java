package com.zzdzz.novelgen.derive.draft;

import com.zzdzz.novelgen.llm.LlmPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneWriterTest {

    @Mock
    private LlmPort llm;

    private SceneWriter writer;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        writer = new SceneWriter(llm);
    }

    @Test
    void writerPromptContainsOnlyCurrentScene() {
        when(llm.chat(any())).thenReturn(result(DraftFixtures.prose(1, 100)));
        SceneScript current = scene(1, "当前场景目标必须落实");
        writer.write(current, context());

        ArgumentCaptor<LlmPort.ChatRequest> request =
                ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        org.mockito.Mockito.verify(llm).chat(request.capture());
        String user = request.getValue().messages().get(1).content();
        assertTrue(user.contains(current.goal()));
        assertTrue(user.contains("当前事实"));
        assertTrue(user.contains("前章摘要"));
        assertTrue(user.contains("只输出纯散文"));
    }

    @Test
    void writerOutputIsPlainProse() {
        when(llm.chat(any())).thenReturn(result("```text\n" + DraftFixtures.prose(1, 100) + "\n```"));

        SceneDraft draft = writer.write(scene(1, "推进"), context());

        assertTrue(draft.text().startsWith("林澈"));
        assertTrue(draft.chars() > 20);
    }

    @Test
    void machineJsonTailIsRejected() {
        when(llm.chat(any())).thenReturn(result(
                DraftFixtures.prose(1, 80) + "\n{\"sceneNo\":1,\"chars\":80}"));

        SceneDraftException error = assertThrows(SceneDraftException.class,
                () -> writer.write(scene(1, "推进"), context()));

        assertEquals("DRAFT_SCENE_INVALID", error.code());
    }

    @Test
    void emptyOutputIsRejected() {
        when(llm.chat(any())).thenReturn(result("  \n "));
        SceneDraftException error = assertThrows(SceneDraftException.class,
                () -> writer.write(scene(1, "推进"), context()));
        assertEquals("DRAFT_SCENE_EMPTY", error.code());
    }

    @Test
    void tinyPlaceholderOutputIsRejected() {
        when(llm.chat(any())).thenReturn(result("好的，我开始写这个场景。"));
        SceneDraftException error = assertThrows(SceneDraftException.class,
                () -> writer.write(scene(1, "推进"), context()));
        assertEquals("DRAFT_SCENE_INVALID", error.code());
    }

    private static SceneScript scene(int no, String goal) {
        return new SceneScript(no, "B1-S" + no, goal, 80,
                List.of("已知前情"), List.of("新事实"));
    }

    private static SceneContext context() {
        return new SceneContext(59L, 4, 1, "前章摘要",
                Map.of("C1", List.of("位置：书房")));
    }

    private static LlmPort.ChatResult result(String content) {
        return new LlmPort.ChatResult(1L, content, "", new LlmPort.Usage(1, 1, 2));
    }
}
