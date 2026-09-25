package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.dto.ChapterDTO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** AI 章纲生成：卷纲行 + 世界观 + 前情 → 场景列表（严格 JSON，失败原因喂回重试）。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutlineService {


    public record SceneSpec(int sceneNo, String goal, List<String> present,
                            List<String> mustReveal, List<String> mustNot, int words) {}

    private final LlmJson llmJson;
    private final ObjectMapper mapper;
    private final ChapterDataService chapterData;
    private final SceneDataService sceneData;
    private final PromptTemplateService promptTemplates;
    private final TuningService tuning;


    public ChapterDTO loadChapter(long novelId, int chapterNo) {
        return chapterData.find(novelId, chapterNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterNo));
    }

    public void generate(long novelId, ChapterDTO ch, String world, String characters,
                         List<String> directives, List<String> digests, String prevTail,
                         String prevBrief) {
        // 流 A：未消费的打回意见拼进卷纲目标行（不动提示词模板目录），章纲落库成功后清零；
        // 长度护栏走调参键 reject_reason_max_len（默认 200），超长截断并标注
        String goal = ch.getGoal();
        String rejectReason = ch.getRejectReason();
        if (rejectReason != null && !rejectReason.isBlank()) {
            int maxLen = tuning.i("reject_reason_max_len", TuningDefaults.REJECT_REASON_MAX_LEN);
            String trimmed = rejectReason.strip();
            if (trimmed.length() > maxLen) {
                trimmed = trimmed.substring(0, maxLen) + "…（意见超长已截断）";
            }
            goal = goal + promptTemplates.getSection(LlmNode.OUTLINE, "reject_suffix",
                    java.util.Map.of("reason", trimmed));
        }
        String user = promptTemplates.format(LlmNode.OUTLINE, "user", ch.getChapterNo(), ch.getTitle(), goal, ch.getHook(),
                Objects.toString(ch.getTimeNote(), "紧接上一章，无跳跃"),
                Objects.toString(ch.getRuleRefs(), "[]"), Objects.toString(ch.getForeshadowRefs(), "[]"),
                ch.getBudgetMin(), ch.getBudgetMax(), world, characters,
                digests == null || digests.isEmpty() ? "（本章是第一章，无前情）" : String.join("\n---\n", digests),
                prevBrief == null || prevBrief.isBlank() ? "（本章是第一章，无上一章后果）" : prevBrief,
                prevTail == null ? "（无）" : prevTail);

        JsonNode scenes = askScenes(novelId, ch.getId(), user, 2);
        if (rejectReason != null && !rejectReason.isBlank()) {
            chapterData.clearRejectReason(ch.getId()); // 意见已注入本次章纲，消费清零
        }

        List<String> goals = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> reveal = new ArrayList<>();
        List<String> not = new ArrayList<>();
        List<Integer> words = new ArrayList<>();
        for (JsonNode s : scenes) {
            goals.add(s.path("goal").asText(""));
            present.add(jsonText(s.path("present")));
            reveal.add(jsonText(s.path("must_reveal")));
            not.add(jsonText(s.path("must_not")));
            words.add(s.path("words").asInt(900));
        }
        // 先清旧场景与门禁报告（外键顺序在 repository 内处理），再物化新场景
        chapterData.resetForReoutline(ch.getId(), scenes.toString());
        sceneData.replaceAll(ch.getId(), goals, present, reveal, not, words);
        log.info("章纲落库: scenes={}", scenes.size());
    }

    public List<SceneSpec> loadSpecs(long chapterId) {
        return sceneData.findByChapter(chapterId).stream()
                .map(s -> new SceneSpec(s.getSceneNo(), s.getGoal() == null ? "" : s.getGoal(),
                        toStringList(s.getPresent()), toStringList(s.getMustReveal()),
                        toStringList(s.getMustNot()), s.getWordsBudget()))
                .toList();
    }

    /** novelId/chapterId 必传：章纲调用归章（台账按章回放/档案聚合依赖此前修的双空归属缺口）。 */
    private JsonNode askScenes(long novelId, long chapterId, String user, int tries) {
        return llmJson.ask(new LlmPort.ChatRequest(
                        LlmNode.OUTLINE, novelId, chapterId,
                        List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.OUTLINE, "system")),
                                LlmPort.Message.user(user)),
                        LlmTemps.OUTLINE),
                node -> {
                    JsonNode arr = node.path("scenes");
                    if (!arr.isArray() || arr.size() < 2 || arr.size() > 3) {
                        throw new LlmJson.Bad("scenes 必须是 2-3 个元素的数组");
                    }
                    for (JsonNode s : arr) {
                        if (s == null || !s.isObject() || s.path("goal").asText("").isBlank()) {
                            throw new LlmJson.Bad("scenes 里存在非对象元素或 goal 为空");
                        }
                    }
                    return arr;
                }, tries);
    }

    /** 模型可能漏字段：MissingNode/Null 的 toString 是空串，对 ::jsonb 是非法输入，兜底为 [] */
    private String jsonText(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? "[]" : node.toString();
    }

    private List<String> toStringList(String json) {
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode n : mapper.readTree(json)) out.add(n.asText());
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
