package com.zzdzz.novelgen.derive.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 场景级局部重试：每场只重试自己，保留最佳候选并精确回喂本场问题。 */
@Component
public class SceneRetryPolicy {

    /** 单次场景 attempt 记录。 */
    public record SceneAttempt(int attempt, SceneDraft draft, List<String> issues) {}

    /** 场景重试结果；best=null 表示所有候选均为空。 */
    public record SceneRetryResult(
            SceneDraft best,
            int attempts,
            boolean degraded,
            List<String> lastIssues) {}

    private final SceneWriter writer;

    /** 创建场景重试策略。 */
    public SceneRetryPolicy(SceneWriter writer) {
        this.writer = Objects.requireNonNull(writer);
    }

    /** 最多 maxAttempts 次写当前场；首次完全合格即停止。 */
    public SceneRetryResult writeWithRetry(SceneScript script, SceneContext context,
                                           int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        SceneDraft best = null;
        boolean bestClean = false;
        List<String> lastIssues = new ArrayList<>();
        String priorText = "";
        int attempts = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts = attempt;
            try {
                SceneDraft draft = writer.write(script, context, lastIssues, priorText);
                boolean clean = DraftTextRules.validateScene(draft.text()) == null;
                if (best == null || (clean && !bestClean)
                        || (clean == bestClean && distance(draft, script.budget())
                                < distance(best, script.budget()))) {
                    best = draft;
                    bestClean = clean;
                }
                if (clean) {
                    return new SceneRetryResult(draft, attempt, false, List.of());
                }
                if (lastIssues.isEmpty()) {
                    lastIssues = List.of("DRAFT_SCENE_INVALID:候选稿仍含过程话或机器内容");
                }
                priorText = draft.text();
            } catch (SceneDraftException e) {
                lastIssues = List.of(e.code() + ":" + cleanMessage(e.getMessage()));
                if (!e.code().equals("DRAFT_SCENE_EMPTY") && e.getMessage() != null) {
                    priorText = priorText.isBlank() ? "" : priorText;
                }
            }
        }
        return new SceneRetryResult(best, attempts, best != null, List.copyOf(lastIssues));
    }

    private static int distance(SceneDraft draft, int budget) {
        return Math.abs(draft.chars() - budget);
    }

    private static String cleanMessage(String message) {
        int separator = message == null ? -1 : message.indexOf('：');
        return separator < 0 ? String.valueOf(message) : message.substring(separator + 1);
    }
}

