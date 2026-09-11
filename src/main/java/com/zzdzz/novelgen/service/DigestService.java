package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.DigestDAO;
import com.zzdzz.novelgen.dao.ForeshadowDAO;
import com.zzdzz.novelgen.dao.WorldStateDAO;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** 章摘要：定稿正文 → 事实账 + 世界状态快照；伏笔状态随章号确定性推进（不走模型）。 */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private static final String STATE_SPEC = """
            "state":{"time":"本章结束时的时间点（故事内历法或相对事件表述）",
             "locations":{"人名或重要物名":"所在位置"},
             "possessions":{"人名":["随身携带的重要物品"]},
             "new_promises":["本章新立下的承诺/约定/邀约"],
             "unresolved":["本章留下的未解之谜或未回收伏笔"]}""";

    private static final String STATE_SYSTEM = """
            你是世界状态记录员。读完本章，输出本章结束时刻的结构化状态快照，只输出 JSON：
            {%s}
            规则：只记硬事实；人名用规范名；拿不准的不写；字符串值内部禁止英文双引号，引用一律用「」。
            """.strip().formatted(STATE_SPEC);

    private final LlmPort llm;
    private final ObjectMapper mapper;
    private final DigestDAO digestDAO;
    private final ForeshadowDAO foreshadowDAO;
    private final ChapterDAO chapterDAO;
    private final WorldStateDAO worldStateDAO;

    public DigestService(LlmPort llm, ObjectMapper mapper, DigestDAO digestDAO,
                         ForeshadowDAO foreshadowDAO, ChapterDAO chapterDAO,
                         WorldStateDAO worldStateDAO) {
        this.llm = llm;
        this.mapper = mapper;
        this.digestDAO = digestDAO;
        this.foreshadowDAO = foreshadowDAO;
        this.chapterDAO = chapterDAO;
        this.worldStateDAO = worldStateDAO;
    }

    public void digest(long novelId, long chapterId, int chapterNo, String fullText) {
        if (digestDAO.existsByChapter(chapterId)) {
            log.info("第 {} 章事实账已存在，跳过", chapterNo);
            chapterDAO.updateStatus(chapterId, "DIGESTED");
            return;
        }
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                "digest", novelId, chapterId,
                List.of(LlmPort.Message.system("""
                        你是事实账记录员。把章节压缩成供后续章节续写使用的事实账，只输出 JSON：
                        {"summary_md":"300字以内的md：谁做了什么/信息揭示/情绪落点/章末钩子",
                         "facts":["一条一句的硬事实（人名、物件、承诺、时间线变化）"],
                         %s,
                         "new_threads":[{"name":"三到六字短名","content":"一句话：这条新长线是什么、为何值得跨章追踪"}]}
                        字符串值内部禁止使用英文双引号，引用一律用「」。
                        summary_md 不要包含任何标题行，直接从摘要正文开始。
                        new_threads 只提议真正的长线（需要多章才能回收的谜、承诺、关系变化），本章内已解决的不提；
                        与已有伏笔账本同义的不提；最多 2 条；没有就给空数组。
                        """.strip().formatted(STATE_SPEC)),
                        LlmPort.Message.user(digestUserPrompt(novelId, chapterId, fullText))), 0.3));
        String candidate = lenientJson(r.content());
        JsonNode node;
        try {
            node = mapper.readTree(candidate);
        } catch (Exception first) {
            try {
                node = mapper.readTree(repairStraightQuotes(candidate));
            } catch (Exception e) {
                throw new IllegalStateException("digest JSON 解析失败: " + r.content(), e);
            }
        }
        // 模型偶发无视指令在摘要前加「## 事实账」标题行：入库前剥掉
        String summary = node.path("summary_md").asText("")
                .replaceAll("(?m)^#{1,6}[^\\n]*\\n?", "").strip();
        digestDAO.insert(chapterId, summary, node.path("facts").toString());
        JsonNode state = node.path("state");
        if (state.isObject() && state.size() > 0) {
            worldStateDAO.upsert(novelId, chapterNo, state);
            log.info("第 {} 章世界状态快照落库", chapterNo);
        }
        proposeThreads(novelId, chapterNo, node.path("new_threads"));
        foreshadowDAO.markPlanted(novelId, chapterNo);
        foreshadowDAO.markRecovered(novelId, chapterNo);
        chapterDAO.updateStatus(chapterId, "DIGESTED");
        log.info("第 {} 章事实账落库（{} tokens）", chapterNo, r.usage().totalTokens());
    }

    /** digest 用户提示：时间锚点 + 已有伏笔账本（防同义重复提议）+ 本章全文。 */
    private String digestUserPrompt(long novelId, long chapterId, String fullText) {
        StringBuilder sb = new StringBuilder();
        chapterDAO.findById(chapterId).ifPresent(ch -> {
            if (ch.timeNote() != null && !ch.timeNote().isBlank()) {
                sb.append("【时间锚点】本章距上一章：").append(ch.timeNote())
                        .append("（state.time 必须体现该推进）\n\n");
            }
        });
        List<ForeshadowDO> existing = foreshadowDAO.listByNovel(novelId);
        if (!existing.isEmpty()) {
            sb.append("【已有伏笔账本（同义勿重复提议）】\n");
            for (ForeshadowDO f : existing) {
                sb.append(f.code()).append('（').append(f.status()).append('）').append(f.content()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【本章全文】\n").append(fullText);
        return sb.toString();
    }

    /** 自动提议落库：status='proposed'，等素材库人工采纳（→planned）或忽略（→dropped）。 */
    private void proposeThreads(long novelId, int chapterNo, JsonNode threads) {
        if (!threads.isArray() || threads.isEmpty()) {
            return;
        }
        int added = 0;
        for (JsonNode t : threads) {
            String name = t.path("name").asText("").strip();
            String content = t.path("content").asText("").strip();
            if (name.isBlank() || content.isBlank()) continue;
            String full = name + "：" + content;
            if (foreshadowDAO.contentExists(novelId, full) || foreshadowDAO.contentExists(novelId, content)) {
                continue;
            }
            foreshadowDAO.insertProposal(novelId, foreshadowDAO.nextCode(novelId), full, chapterNo);
            added++;
        }
        if (added > 0) {
            log.info("第 {} 章自动提议 {} 条新伏笔（PROPOSED，待人工采纳）", chapterNo, added);
        }
    }

    /** 存量回填：只产出世界状态快照，不动事实账（轻量调用，逐章触发）。 */
    public void backfillState(long novelId, int chapterNo) {
        ChapterDO ch = chapterDAO.find(novelId, chapterNo)
                .orElseThrow(() -> new IllegalArgumentException("章不存在: " + chapterNo));
        if (ch.fullText() == null || ch.fullText().isBlank()) {
            throw new IllegalArgumentException("该章无正文，无法回填状态");
        }
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                "world_state", novelId, ch.id(),
                List.of(LlmPort.Message.system(STATE_SYSTEM),
                        LlmPort.Message.user(ch.fullText() + "\n\n只输出 state JSON。")), 0.2));
        JsonNode node;
        try {
            node = mapper.readTree(lenientJson(r.content()));
        } catch (Exception first) {
            try {
                node = mapper.readTree(repairStraightQuotes(lenientJson(r.content())));
            } catch (Exception e) {
                throw new IllegalStateException("第 " + chapterNo + " 章状态 JSON 解析失败", e);
            }
        }
        // prompt 模板带 "state" 外壳，模型会如实包一层：解包后再校验
        if (node.has("state") && node.path("state").isObject()) {
            node = node.path("state");
        }
        if (!node.isObject() || node.size() == 0) {
            throw new IllegalStateException("第 " + chapterNo + " 章状态输出为空");
        }
        worldStateDAO.upsert(novelId, chapterNo, node);
        log.info("第 {} 章世界状态回填完成（{} tokens）", chapterNo, r.usage().totalTokens());
    }

    /** 模型偶发输出 ```json 围栏或前后缀话术：截取首个 { 到末个 } 再解析。 */
    private static String lenientJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    /**
     * 修复字符串值内部的未转义英文双引号（模型高频毛病）。
     * 状态机：处于字符串内时，若一个引号的后继非空字符是 , } ] : 则视为收口引号，否则替换为「。
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
}
