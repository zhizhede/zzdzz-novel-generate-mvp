package com.zzdzz.novelgen.derive.draft;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 确定性章节拼装：按 sceneNo 排序，只由场景散文和空行组成。 */
@Component
public class ChapterAssembler {

    /** 组装一章；缺场、空场或机器 JSON 立即拒绝，绝不带入章节正文。 */
    public ChapterDraftResult assemble(ChapterScript chapter, List<SceneDraft> scenes) {
        if (chapter == null) {
            throw new IllegalArgumentException("chapter");
        }
        Map<Integer, SceneDraft> byNo = new TreeMap<>();
        if (scenes != null) {
            for (SceneDraft draft : scenes) {
                if (byNo.put(draft.sceneNo(), draft) != null) {
                    throw new SceneDraftException("DRAFT_SCENE_INVALID",
                            "重复 sceneNo=" + draft.sceneNo());
                }
            }
        }
        if (byNo.size() != chapter.scenes().size()) {
            throw new SceneDraftException("DRAFT_SCENE_EMPTY",
                    "chapter=" + chapter.chapterNo() + " 缺少 "
                            + (chapter.scenes().size() - byNo.size()) + " 场");
        }
        List<SceneDraft> ordered = chapter.scenes().stream()
                .map(script -> {
                    SceneDraft draft = byNo.get(script.sceneNo());
                    if (draft == null) {
                        throw new SceneDraftException("DRAFT_SCENE_EMPTY",
                                "chapter=" + chapter.chapterNo() + ".scene=" + script.sceneNo());
                    }
                    if (draft.text() == null || draft.text().isBlank()) {
                        throw new SceneDraftException("DRAFT_SCENE_EMPTY",
                                "chapter=" + chapter.chapterNo() + ".scene=" + draft.sceneNo());
                    }
                    if (draft.text().contains("```") || DraftTextRules.hasMachineJsonTail(draft.text())) {
                        throw new SceneDraftException("DRAFT_SCENE_INVALID",
                                "chapter=" + chapter.chapterNo() + ".scene=" + draft.sceneNo());
                    }
                    return new SceneDraft(draft.sceneNo(), draft.text().trim(),
                            DraftTextRules.length(draft.text()));
                })
                .collect(Collectors.toList());
        String text = ordered.stream().map(SceneDraft::text)
                .collect(Collectors.joining("\n\n"));
        int chars = DraftTextRules.length(text);
        if (chars <= 0) {
            throw new SceneDraftException("DRAFT_CHAPTER_EMPTY",
                    "chapter=" + chapter.chapterNo());
        }
        return new ChapterDraftResult(chapter.chapterNo(), text, chars, ordered, false);
    }
}


