package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.GateReportDAO;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 语义审校闭环：机械门禁之后的连续性/逻辑/错字审校。
 * BLOCKER 带问题清单修订一轮并复审，复审仍 BLOCKER 则交人工（管线不过稿）。
 * 报告落 gate_reports（gate_type='ai_review'），解析失败 fail-open 跳过——审校员故障不能卡死管线。
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private static final String SYSTEM = """
            你是资深网文审校编辑，在机械门禁之后做语义审校。只查以下四类问题：
            1. continuity 连续性：与给定上下文（世界设定、人物卡、近章事实账、上一章结尾）矛盾——时间线、称呼、物件、地点、人物状态。
            2. logic 逻辑硬伤：情节自相矛盾、前因后果断裂。
            3. typo 错别字/用词错误：明显的错字、漏字、用词不当。
            4. format 格式：章题混入正文、markdown 残留、阿拉伯数字。
            只输出 JSON：
            {"verdict":"pass|minor|blocker","summary":"一句话总评","issues":[{"type":"continuity|logic|typo|format","severity":"minor|blocker","quote":"原句","explanation":"问题说明","suggestion":"修改建议"}]}
            规则：
            - 引用原文一律用「」；字符串值内部禁止英文双引号。
            - 存在必须改的硬伤（时间线矛盾、称呼错、错字）才判 blocker；只有不破坏阅读的瑕疵判 minor；无问题判 pass。
            - 宁可漏报不可误报：没有把握的不要报，不提风格意见。
            - 不要输出思考过程，只输出 JSON。
            """.strip();

    private final LlmPort llm;
    private final ContextPackerService packer;
    private final GateReportDAO gateReportDAO;
    private final ChapterDAO chapterDAO;
    private final ObjectMapper mapper;

    public ReviewService(LlmPort llm, ContextPackerService packer,
                         GateReportDAO gateReportDAO, ChapterDAO chapterDAO, ObjectMapper mapper) {
        this.llm = llm;
        this.packer = packer;
        this.gateReportDAO = gateReportDAO;
        this.chapterDAO = chapterDAO;
        this.mapper = mapper;
    }

    /** 审校结论：revised 为修订后全文（null=未改），blocked=复审仍 BLOCKER。 */
    public record Outcome(String revised, String verdict, boolean blocked) {
    }

    /** 管线闭环：审校 → BLOCKER 则带清单修订一轮 → 复审。 */
    public Outcome reviewAndFix(long novelId, ChapterDO ch, String fullText) {
        JsonNode r1 = reviewOnce(novelId, ch, fullText, 1);
        String verdict = r1.path("verdict").asText("skipped");
        if (!"blocker".equals(verdict)) {
            return new Outcome(null, verdict, false);
        }
        log.warn("第 {} 章审校判 BLOCKER（{} 条），带清单修订一轮", ch.chapterNo(),
                r1.path("issues").size());
        String revised = reviseForIssues(novelId, ch, fullText, r1);
        JsonNode r2 = reviewOnce(novelId, ch, revised != null ? revised : fullText, 2);
        String v2 = r2.path("verdict").asText("skipped");
        boolean blocked = "blocker".equals(v2);
        return new Outcome(revised, blocked ? "blocker" : v2, blocked);
    }

    /** 回溯/UI 用：对已有正文的章跑一次审校，只落报告，不动正文与状态。 */
    public void reviewExisting(long chapterId) {
        ChapterDO ch = chapterDAO.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("章不存在: " + chapterId));
        if (ch.fullText() == null || ch.fullText().isBlank()) {
            throw new IllegalArgumentException("该章无正文，无法审校");
        }
        reviewOnce(ch.novelId(), ch, ch.fullText(), 1);
    }

    /** 单轮审校：解析失败重试 1 次（原因喂回），仍失败 fail-open 落 skipped 报告。 */
    private JsonNode reviewOnce(long novelId, ChapterDO ch, String text, int round) {
        String user = userPrompt(novelId, ch, text);
        String feedback = null;
        String lastRaw = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            List<LlmPort.Message> msgs = new ArrayList<>();
            msgs.add(LlmPort.Message.system(SYSTEM));
            if (feedback != null) msgs.add(LlmPort.Message.user(feedback));
            msgs.add(LlmPort.Message.user(user));
            LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                    "ai_review", novelId, ch.id(), msgs, 0.2));
            lastRaw = r.content();
            try {
                JsonNode node;
                try {
                    node = mapper.readTree(lenientJson(r.content()));
                } catch (Exception first) {
                    node = mapper.readTree(repairStraightQuotes(lenientJson(r.content())));
                }
                String verdict = node.path("verdict").asText("");
                if (!List.of("pass", "minor", "blocker").contains(verdict)) {
                    throw new IllegalStateException("verdict 非法: " + verdict);
                }
                gateReportDAO.insert(ch.id(), null, "ai_review", round,
                        !"blocker".equals(verdict), node);
                log.info("第 {} 章审校 round={}：{}（{} 条问题）", ch.chapterNo(), round,
                        verdict, node.path("issues").size());
                return node;
            } catch (Exception e) {
                feedback = "【上一次输出不合规：" + e.getMessage() + "。请重新输出，只输出合法 JSON，"
                        + "verdict 必须是 pass|minor|blocker。】";
            }
        }
        log.warn("第 {} 章审校输出两次解析失败，本轮跳过（fail-open）：{}", ch.chapterNo(),
                lastRaw.substring(0, Math.min(120, lastRaw.length())));
        gateReportDAO.insert(ch.id(), null, "ai_review", round, true,
                Map.of("skipped", true, "reason", "parse_failed"));
        return mapper.createObjectNode()
                .put("verdict", "skipped")
                .put("summary", "审校输出解析失败，本轮跳过");
    }

    private String userPrompt(long novelId, ChapterDO ch, String text) {
        List<String> digests = packer.recentDigests(novelId, ch.chapterNo(), 3);
        String prevTail = packer.prevTail(novelId, ch.chapterNo());
        StringBuilder sb = new StringBuilder();
        sb.append("【世界设定与大纲】\n").append(packer.world(novelId)).append("\n\n");
        sb.append("【人物卡】\n").append(packer.characters(novelId)).append("\n\n");
        sb.append("【近章事实账】\n");
        if (digests.isEmpty()) sb.append("（无）\n");
        digests.forEach(d -> sb.append("---\n").append(d).append('\n'));
        sb.append("\n【上一章结尾】\n").append(prevTail == null || prevTail.isBlank() ? "（无）" : prevTail);
        sb.append("\n\n【第 ").append(ch.chapterNo()).append(" 章全文（审校对象）】\n").append(text);
        sb.append("\n\n审校以上全文，只输出 JSON。");
        return sb.toString();
    }

    /** 带问题清单的修订轮：外科手术式，只修 BLOCKER 条目；修订稿异常时保留原文（返回 null）。 */
    private String reviseForIssues(long novelId, ChapterDO ch, String fullText, JsonNode review) {
        StringBuilder fb = new StringBuilder();
        for (JsonNode i : review.path("issues")) {
            if (!"blocker".equals(i.path("severity").asText("blocker"))) continue;
            fb.append("- [").append(i.path("type").asText()).append("] 原句：")
                    .append(i.path("quote").asText()).append("；问题：")
                    .append(i.path("explanation").asText()).append("；建议：")
                    .append(i.path("suggestion").asText()).append('\n');
        }
        if (fb.isEmpty()) {
            fb.append("- 审校判定存在硬伤但未给出条目，请通读自查时间线、称呼与错别字。\n");
        }
        String user = """
                任务：修订第 %d 章全文。语义审校发现以下必须修复的问题：
                %s
                要求：只修被点名的问题（错字改字、矛盾句最小改写），严禁改动情节走向与分行节奏，总字数变化控制在 ±10%% 内。
                直接输出修订后的完整正文，不要输出思考过程。

                【第 %d 章全文（在此版本上修改）】
                %s
                """.formatted(ch.chapterNo(), fb, ch.chapterNo(), fullText);
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                "ai_review_revise", novelId, ch.id(),
                List.of(LlmPort.Message.system("你是执行审校修订的网文编辑，只做被点名的最小修改。"),
                        LlmPort.Message.user(user)),
                0.5));
        String cleaned = ChapterPipelineService.stripTitleLine(
                SceneService.cleanDraft(r.content()), ch.title());
        if (cleaned.isBlank() || cleaned.length() < fullText.length() * 0.6) {
            log.warn("第 {} 章审校修订稿长度异常（{} 字符），保留原文", ch.chapterNo(), cleaned.length());
            return null;
        }
        return cleaned;
    }

    /** 模型偶发输出 ```json 围栏或前后缀话术：截取首个 { 到末个 } 再解析。 */
    private static String lenientJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    /**
     * 修复字符串值内部的未转义英文双引号（症状：「expecting comma to separate Array entries」处撞上中文）：
     * 处于字符串内时，若一个引号的后继非空字符是 , } ] : 则视为收口引号，否则替换为「。
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
