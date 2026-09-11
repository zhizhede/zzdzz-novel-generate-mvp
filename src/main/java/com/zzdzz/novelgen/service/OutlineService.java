package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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

    private final LlmPort llm;
    private final ObjectMapper mapper;
    private final ChapterDAO chapterDAO;
    private final SceneDAO sceneDAO;
    /** LLM 输出专用：容忍字符串内的裸换行/Tab 等控制字符 */
    private final ObjectMapper lenientMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    public OutlineService(LlmPort llm, ObjectMapper mapper,
                          ChapterDAO chapterDAO, SceneDAO sceneDAO) {
        this.llm = llm;
        this.mapper = mapper;
        this.chapterDAO = chapterDAO;
        this.sceneDAO = sceneDAO;
    }

    public ChapterDO loadChapter(long novelId, int chapterNo) {
        return chapterDAO.find(novelId, chapterNo)
                .orElseThrow(() -> new IllegalStateException("章不存在: " + chapterNo));
    }

    public void generate(long novelId, ChapterDO ch, String world, String characters,
                         List<String> directives, List<String> digests, String prevTail) {
        String user = """
                任务：为第 %d 章《%s》编写场景级章纲。
                本章卷纲目标：%s
                章末钩子类型：%s
                本章涉及的守则：%s
                伏笔任务：%s
                全章字数预算：%d–%d 字，必须拆成 2-3 个场景，每个场景 800–1200 字。

                【世界观（必须遵守，不得发明矛盾设定）】
                %s

                【人物卡】
                %s

                【前情摘要】
                %s

                【上一章结尾原文（衔接其节奏）】
                %s

                只输出 JSON，格式：
                {"scenes":[{"no":1,"goal":"本场景目标","present":["出场人物"],"must_reveal":["必须让读者知道的信息"],"must_not":["禁止出现的内容"],"words":900}]}
                """.formatted(ch.chapterNo(), ch.title(), ch.goal(), ch.hook(),
                Objects.toString(ch.ruleRefs(), "[]"), Objects.toString(ch.foreshadowRefs(), "[]"),
                ch.budgetMin(), ch.budgetMax(), world, characters,
                digests == null || digests.isEmpty() ? "（本章是第一章，无前情）" : String.join("\n---\n", digests),
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
        Exception last = null;
        String feedback = "";
        for (int i = 0; i < tries; i++) {
            LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                    "outline", null, null,
                    List.of(LlmPort.Message.system("你是网文章纲规划器，只输出合法 JSON，不要任何解释或 markdown 代码块。"
                            + "字符串值内部禁止英文双引号，引用一律用「」。"),
                            LlmPort.Message.user(user + feedback)),
                    0.3));
            String reason = null;
            JsonNode arr = null;
            try {
                JsonNode node = lenientRead(r.content());
                arr = node.path("scenes");
                if (!arr.isArray() || arr.size() < 2 || arr.size() > 3) {
                    reason = "scenes 必须是 2-3 个元素的数组";
                } else {
                    for (JsonNode s : arr) {
                        if (s == null || !s.isObject() || s.path("goal").asText("").isBlank()) {
                            reason = "scenes 里存在非对象元素或 goal 为空";
                            break;
                        }
                    }
                }
                if (reason == null) return arr;
            } catch (Exception e) {
                reason = "JSON 解析失败：" + e.getMessage();
                last = e;
            }
            log.warn("章纲 JSON 校验未过（第 {} 次）：{} 原文前 200 字: {}", i + 1, reason,
                    r.content() == null ? "null" : r.content().substring(0, Math.min(200, r.content().length())));
            feedback = "\n\n【上一次输出不合规：" + reason + "。请重新输出，只输出合法 JSON。】";
        }
        throw new IllegalStateException("章纲生成失败", last);
    }

    /** LLM JSON 容错解析：截取首尾大括号 + 允许字符串内的裸控制字符；失败再试修复字符串值内未转义英文引号 */
    private JsonNode lenientRead(String content) throws Exception {
        String s = content.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("输出中没有 JSON 对象");
        String json = s.substring(start, end + 1);
        try {
            return lenientMapper.readTree(json);
        } catch (Exception first) {
            return lenientMapper.readTree(repairStraightQuotes(json));
        }
    }

    /**
     * 修复字符串值内部的未转义英文双引号（模型高频毛病，症状是「expecting comma to separate Array
     * entries」处撞上中文）：处于字符串内时，若一个引号的后继非空字符是 , } ] : 则视为收口引号，否则替换为「。
     */
    private static String repairStraightQuotes(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 16);
        boolean inStr = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inStr) {
                if (c == '"') inStr = true;
                sb.append(c);
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                char next = j < json.length() ? json.charAt(j) : '\0';
                if (next == ',' || next == '}' || next == ']' || next == ':') {
                    inStr = false;
                    sb.append('"');
                } else {
                    sb.append('「');
                }
            } else if (c == '\\' && i + 1 < json.length()) {
                sb.append(c).append(json.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
