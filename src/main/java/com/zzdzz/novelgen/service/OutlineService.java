package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.SceneDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** AI 章纲生成：卷纲行 + 世界观 + 前情 → 场景列表（严格 JSON，失败原因喂回重试）。 */
@Service
public class OutlineService {

    private static final Logger log = LoggerFactory.getLogger(OutlineService.class);

    public record SceneSpec(int sceneNo, String goal, List<String> present,
                            List<String> mustReveal, List<String> mustNot, int words) {}

    private final LlmJson llmJson;
    private final ObjectMapper mapper;
    private final ChapterDAO chapterDAO;
    private final SceneDAO sceneDAO;

    public OutlineService(LlmJson llmJson, ObjectMapper mapper,
                          ChapterDAO chapterDAO, SceneDAO sceneDAO) {
        this.llmJson = llmJson;
        this.mapper = mapper;
        this.chapterDAO = chapterDAO;
        this.sceneDAO = sceneDAO;
    }

    public ChapterDO loadChapter(long novelId, int chapterNo) {
        return chapterDAO.find(novelId, chapterNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterNo));
    }

    public void generate(long novelId, ChapterDO ch, String world, String characters,
                         List<String> directives, List<String> digests, String prevTail,
                         String prevBrief) {
        String user = """
                任务：为第 %d 章《%s》编写场景级章纲。
                本章卷纲目标：%s
                章末钩子类型：%s
                本章距上一章的时间跨度：%s（场景与对白须体现该推进，不可凭空另设时间线）
                本章涉及的守则：%s
                伏笔任务：%s
                全章字数预算：%d–%d 字，必须拆成 2-3 个场景，每个场景 800–1200 字。

                【世界观（必须遵守，不得发明矛盾设定）】
                %s

                %s

                【前情摘要】
                %s

                【上一章事件后果（第一场景必须与之对接：兑现、交代或明确推进，禁止无视另起炉灶）】
                %s

                【上一章结尾原文（衔接其节奏）】
                %s

                只输出 JSON，格式：
                {"scenes":[{"no":1,"goal":"本场景目标","present":["出场人物"],"must_reveal":["必须让读者知道的信息"],"must_not":["禁止出现的内容"],"words":900}]}
                """.formatted(ch.chapterNo(), ch.title(), ch.goal(), ch.hook(),
                Objects.toString(ch.timeNote(), "紧接上一章，无跳跃"),
                Objects.toString(ch.ruleRefs(), "[]"), Objects.toString(ch.foreshadowRefs(), "[]"),
                ch.budgetMin(), ch.budgetMax(), world, characters,
                digests == null || digests.isEmpty() ? "（本章是第一章，无前情）" : String.join("\n---\n", digests),
                prevBrief == null || prevBrief.isBlank() ? "（本章是第一章，无上一章后果）" : prevBrief,
                prevTail == null ? "（无）" : prevTail);

        JsonNode scenes = askScenes(user, 2);

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
        chapterDAO.resetForReoutline(ch.id(), scenes.toString());
        sceneDAO.replaceAll(ch.id(), goals, present, reveal, not, words);
        log.info("章纲落库: scenes={}", scenes.size());
    }

    public List<SceneSpec> loadSpecs(long chapterId) {
        return sceneDAO.findByChapter(chapterId).stream()
                .map(s -> new SceneSpec(s.sceneNo(), s.goal() == null ? "" : s.goal(),
                        toStringList(s.present()), toStringList(s.mustReveal()),
                        toStringList(s.mustNot()), s.wordsBudget()))
                .toList();
    }

    private JsonNode askScenes(String user, int tries) {
        return llmJson.ask(new LlmPort.ChatRequest(
                        LlmNode.OUTLINE, null, null,
                        List.of(LlmPort.Message.system("你是网文章纲规划器，只输出合法 JSON，不要任何解释或 markdown 代码块。"
                                + "字符串值内部禁止英文双引号，引用一律用「」。"),
                                LlmPort.Message.user(user)),
                        0.3),
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
