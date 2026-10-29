package com.zzdzz.novelgen.derive.draft;

import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.llm.PromptCatalog;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 单场散文 writer：只生成当前场纯散文，不读写计划、不修改章节状态。 */
@Service
public class SceneWriter {

    private final LlmPort llm;

    /** 创建场景 writer。 */
    public SceneWriter(LlmPort llm) {
        this.llm = Objects.requireNonNull(llm);
    }

    /** 无重试地写一场。 */
    public SceneDraft write(SceneScript script, SceneContext context) {
        return write(script, context, List.of(), "");
    }

    /** 带本场精确失败反馈重试；不得携带其他场脚本。 */
    public SceneDraft write(SceneScript script, SceneContext context,
                            List<String> priorIssues, String priorText) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(context, "context");
        String system = PromptCatalog.contentOf(LlmNode.SCENE_DRAFT, "contract_system");
        String user = user(script, context, priorIssues, priorText);
        LlmPort.ChatResult result = llm.chat(new LlmPort.ChatRequest(
                LlmNode.SCENE_DRAFT, null, null, context.runId(),
                List.of(LlmPort.Message.system(system), LlmPort.Message.user(user)), 0.7));
        String text = DraftTextRules.stripFence(result.content());
        String issue = DraftTextRules.validateScene(text);
        if (issue != null) {
            throw new SceneDraftException(issue,
                    issue.startsWith("DRAFT_SCENE_EMPTY")
                            ? "第" + context.chapterNo() + "章场景" + script.sceneNo() + "为空"
                            : "第" + context.chapterNo() + "章场景" + script.sceneNo()
                                    + "含机器 JSON、过程话或占位输出");
        }
        return new SceneDraft(script.sceneNo(), text, DraftTextRules.length(text));
    }

    private String user(SceneScript script, SceneContext context,
                        List<String> priorIssues, String priorText) {
        StringBuilder text = new StringBuilder();
        text.append("第").append(context.chapterNo()).append("章·第")
                .append(script.sceneNo()).append("场\n");
        text.append("本场目标：").append(script.goal()).append('\n');
        text.append("本场字数预算：").append(script.budget()).append('\n');
        text.append("必须承接的知识：").append(join(script.carryKnowledge())).append('\n');
        text.append("本场必须揭示：").append(join(script.revealKnowledge())).append('\n');
        text.append("前章摘要：").append(orNone(context.priorSummary())).append('\n');
        text.append("当前事实/状态：").append(states(context.stateBefore())).append('\n');
        if (!context.styleConstraints().isBlank()) {
            text.append("文风约束：").append(context.styleConstraints()).append('\n');
        }
        if (!priorIssues.isEmpty()) {
            text.append("上一稿的精确问题（只修复这些问题）：")
                    .append(join(priorIssues)).append('\n');
            text.append("上一稿：").append(orNone(priorText)).append('\n');
        }
        text.append("只输出纯散文。不要标题、不要 JSON、不要解释、不要过程话。");
        return text.toString();
    }

    private static String states(Map<String, List<String>> values) {
        if (values.isEmpty()) {
            return "无";
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + join(entry.getValue()))
                .collect(Collectors.joining("；"));
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "无" : String.join("；", values);
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }
}


