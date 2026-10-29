package com.zzdzz.novelgen.derive.draft;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterAssemblerTest {

    private final ChapterAssembler assembler = new ChapterAssembler();

    @Test
    void assemblesScenesInSceneNumberOrder() {
        ChapterScript chapter = script(1, List.of(
                new SceneScript(1, "S1", "推进", 50, List.of(), List.of()),
                new SceneScript(2, "S2", "收束", 50, List.of(), List.of())));

        ChapterDraftResult result = assembler.assemble(chapter, List.of(
                draft(2, "第二场的完整正文在这里。"),
                draft(1, "第一场的完整正文在这里。")));

        assertTrue(result.text().indexOf("第一场") < result.text().indexOf("第二场"));
        assertEquals(2, result.scenes().size());
    }

    @Test
    void missingSceneFailsChapterAssembly() {
        ChapterScript chapter = script(1, List.of(
                new SceneScript(1, "S1", "推进", 50, List.of(), List.of()),
                new SceneScript(2, "S2", "收束", 50, List.of(), List.of())));
        SceneDraftException error = assertThrows(SceneDraftException.class,
                () -> assembler.assemble(chapter, List.of(draft(1, "只有第一场正文内容。"))));

        assertEquals("DRAFT_SCENE_EMPTY", error.code());
    }

    @Test
    void emptySceneFailsChapterAssembly() {
        ChapterScript chapter = script(1, List.of(
                new SceneScript(1, "S1", "推进", 50, List.of(), List.of())));
        SceneDraftException error = assertThrows(SceneDraftException.class,
                () -> assembler.assemble(chapter, List.of(draft(1, "   "))));

        assertEquals("DRAFT_SCENE_EMPTY", error.code());
    }

    @Test
    void machineJsonIsRejectedInsteadOfLeakingIntoChapter() {
        ChapterScript chapter = script(1, List.of(
                new SceneScript(1, "S1", "推进", 50, List.of(), List.of())));
        SceneDraftException error = assertThrows(SceneDraftException.class,
                () -> assembler.assemble(chapter, List.of(
                        draft(1, "{\"sceneNo\":1,\"text\":\"不是正文\"}"))));

        assertEquals("DRAFT_SCENE_INVALID", error.code());
    }

    @Test
    void charCountIgnoresWhitespaceNormalization() {
        ChapterScript chapter = script(1, List.of(
                new SceneScript(1, "S1", "推进", 50, List.of(), List.of())));
        ChapterDraftResult result = assembler.assemble(chapter,
                List.of(draft(1, "  第一场\n 正文  ")));

        assertEquals(5, result.chars());
    }

    private static ChapterScript script(int chapterNo, List<SceneScript> scenes) {
        return new ChapterScript(chapterNo, "章题", 100, scenes);
    }

    private static SceneDraft draft(int sceneNo, String text) {
        return new SceneDraft(sceneNo, text, text.replaceAll("\\s", "").length());
    }
}

