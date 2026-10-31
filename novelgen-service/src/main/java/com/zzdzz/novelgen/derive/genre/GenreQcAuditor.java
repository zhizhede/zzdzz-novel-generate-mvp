package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 QC 审计：检查正文是否兑现必需义务，并发现跨章世界状态硬冲突。
 */
@Component
public class GenreQcAuditor {

    /** QC 使用的正文投影。 */
    public record CompiledDraft(List<ChapterText> chapters,
                                Map<Integer, Map<String, String>> worldStates) {
        public CompiledDraft {
            chapters = chapters == null ? List.of() : List.copyOf(chapters);
            worldStates = worldStates == null ? Map.of() : Map.copyOf(worldStates);
        }
    }

    /** 单章正文投影。 */
    public record ChapterText(int chapterNo, String text) {
        public ChapterText {
            text = text == null ? "" : text;
        }
    }

    /** 审计通用 QC；返回结构化问题。 */
    public List<PlanContracts.PlanIssue> audit(CompiledDraft draft, StoryKernel kernel,
                                               GenreProfile profile) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (draft == null || kernel == null) {
            return issues;
        }
        Map<Integer, String> textByChapter = new HashMap<>();
        for (ChapterText chapter : draft.chapters()) {
            textByChapter.put(chapter.chapterNo(), chapter.text());
        }
        for (StoryObligation obligation : kernel.obligations()) {
            if (!obligation.required()) {
                continue;
            }
            boolean fulfilled = kernel.evidenceIndex().hasEvidence(obligation.obligationKey());
            if (!fulfilled) {
                issues.add(issue("OBLIGATION_MISSING", obligation.chapterNo(),
                        "正文未兑现义务 " + obligation.obligationKey()));
            }
        }
        Map<String, String> seen = new HashMap<>();
        for (Map.Entry<Integer, Map<String, String>> entry : draft.worldStates().entrySet()) {
            for (Map.Entry<String, String> state : entry.getValue().entrySet()) {
                String previous = seen.putIfAbsent(state.getKey(), state.getValue());
                if (previous != null && !previous.equals(state.getValue())) {
                    issues.add(issue("STATE_CONFLICT", entry.getKey(),
                            "世界状态 " + state.getKey() + " 出现冲突"));
                }
            }
        }
        return List.copyOf(issues);
    }

    protected static PlanContracts.PlanIssue issue(String code, Integer chapterNo, String message) {
        return new PlanContracts.PlanIssue("HARD", code, PlanContracts.RepairScope.DRAFT,
                null, chapterNo, List.of(), message);
    }
}
