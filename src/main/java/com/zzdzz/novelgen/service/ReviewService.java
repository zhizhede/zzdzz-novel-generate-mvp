package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.GateReportDAO;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    /** 读者评审提示词模板：%s=注水率判 blocker 阈值（tuning: reader_fat_ratio_block）。 */
    private static final String READER_SYSTEM = """
            你是一个没耐心的网文读者，刷手机时点开了这一章。你只关心「想不想继续读」，只回答下列问题：
            1. hook 前 3 行：会不会继续往下读？（环境/氛围/抒情式开场、或与上章结尾接不上=不会）
            2. stakes 这场戏：谁想要什么？什么在阻止？（说不出来=没有戏剧张力）
            3. continuity 读完前 10 行，能否定位上一章结束时的情境（时间/地点/在场人物）？（定位不到=衔接断裂）
            4. fat 与剧情无关、删掉后读者不会少知道任何事的纯装饰描写（给微动作写人物志、连篇比喻、静态观察），占比大约多少？
            5. consequence 上下文给出了【上一章事件后果】（上一章的目标、章末钩子与实际收束）。本章是否与之对接——给出兑现、交代或明确推进？（完全无视另起炉灶=fail）
            只输出 JSON：
            {"verdict":"pass|blocker","hook":"pass|fail","stakes":"pass|fail","continuity":"pass|fail","consequence":"pass|fail","fat_ratio":0.4,"skip_quotes":["可整段删除的原句"],"issues":["具体问题（引用原句）"]}
            规则：
            - 引用原文一律用「」；字符串值内部禁止英文双引号。
            - hook/stakes/continuity/consequence 任一 fail，或 fat_ratio 大于 %s → verdict=blocker；否则 pass。
            - 你只管「想不想往下读」，错别字与设定连续性是另一位审校的事，不要报。
            - 不要输出思考过程，只输出 JSON。
            """.strip();

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

    private final LlmPort llmPort;      // 文本修订轮（reader_fix / ai_review_revise）
    private final LlmJson llmJson;      // JSON 评审轮（reader_review / ai_review）
    private final ContextPackerService packer;
    private final GateReportDAO gateReportDAO;
    private final ChapterDAO chapterDAO;
    private final ObjectMapper mapper;
    private final TuningService tuning;

    public ReviewService(LlmPort llmPort, LlmJson llmJson, ContextPackerService packer,
                         GateReportDAO gateReportDAO, ChapterDAO chapterDAO, ObjectMapper mapper,
                         TuningService tuning) {
        this.llmPort = llmPort;
        this.llmJson = llmJson;
        this.packer = packer;
        this.gateReportDAO = gateReportDAO;
        this.chapterDAO = chapterDAO;
        this.mapper = mapper;
        this.tuning = tuning;
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

    /**
     * 读者评审闭环（反无聊闸门）：钩子/戏剧张力/章间衔接/注水率。
     * BLOCKER 带清单重写一轮并复审；复审仍 BLOCKER 交人工（auto 不过稿）。
     * 报告落 gate_reports（gate_type='reader_review'），解析失败 fail-open。
     */
    public Outcome readerReviewAndFix(long novelId, ChapterDO ch, String fullText) {
        JsonNode r1 = readerOnce(novelId, ch, fullText, 1);
        if (!"blocker".equals(r1.path("verdict").asText())) {
            return new Outcome(null, r1.path("verdict").asText("pass"), false);
        }
        log.warn("第 {} 章读者评审判 BLOCKER（hook={} stakes={} continuity={} consequence={} fat_ratio={}），重写一轮",
                ch.chapterNo(), r1.path("hook").asText(), r1.path("stakes").asText(),
                r1.path("continuity").asText(), r1.path("consequence").asText(), r1.path("fat_ratio").asText());
        String revised = readerFix(novelId, ch, fullText, r1);
        if (revised == null) {
            return new Outcome(null, "blocker", true);
        }
        JsonNode r2 = readerOnce(novelId, ch, revised, 2);
        boolean blocked = "blocker".equals(r2.path("verdict").asText());
        return new Outcome(revised, blocked ? "blocker" : "pass", blocked);
    }

    /** 单轮读者评审：解析失败重试 1 次，仍失败 fail-open 落 skipped 报告。 */
    private JsonNode readerOnce(long novelId, ChapterDO ch, String text, int round) {
        String prevTail = packer.prevTail(novelId, ch.chapterNo());
        String prevBrief = packer.prevChapterBrief(novelId, ch.chapterNo());
        String user = "【上一章结尾（衔接定位基准）】\n" + (prevTail == null || prevTail.isBlank() ? "（无）" : prevTail)
                + "\n\n【上一章事件后果】\n" + (prevBrief == null || prevBrief.isBlank() ? "（本章是第一章，consequence 直接 pass）" : prevBrief)
                + "\n\n【本章目标】" + ch.goal()
                + "\n\n【第 " + ch.chapterNo() + " 章全文（评审对象）】\n" + text + "\n\n只输出 JSON。";
        try {
            JsonNode node = llmJson.ask(new LlmPort.ChatRequest(
                            LlmNode.READER_REVIEW, novelId, ch.id(),
                            List.of(LlmPort.Message.system(
                                            READER_SYSTEM.formatted(tuning.d("reader_fat_ratio_block", 0.33))),
                                    LlmPort.Message.user(user)),
                            0.2),
                    n -> {
                        String verdict = n.path("verdict").asText("");
                        if (!List.of("pass", "blocker").contains(verdict)) {
                            throw new LlmJson.Bad("verdict 非法: " + verdict);
                        }
                        return n;
                    }, 2);
            gateReportDAO.insert(ch.id(), null, "reader_review", round,
                    !"blocker".equals(node.path("verdict").asText()), node);
            log.info("第 {} 章读者评审 round={}：{}（fat_ratio={}）", ch.chapterNo(), round,
                    node.path("verdict").asText(), node.path("fat_ratio").asText());
            return node;
        } catch (IllegalStateException e) {
            log.warn("第 {} 章读者评审输出两次解析失败，本轮跳过（fail-open）", ch.chapterNo());
            gateReportDAO.insert(ch.id(), null, "reader_review", round, true,
                    Map.of("skipped", true, "reason", "parse_failed"));
            return mapper.createObjectNode().put("verdict", "pass").put("summary", "解析失败跳过");
        }
    }

    /** 读者评审重写轮：保留情节/信息/对白立场，删纯装饰描写；修订稿异常时保留原文（返回 null）。 */
    private String readerFix(long novelId, ChapterDO ch, String fullText, JsonNode review) {
        StringBuilder fb = new StringBuilder();
        for (JsonNode q : review.path("skip_quotes")) {
            fb.append("- 可整段删除：").append(q.asText()).append('\n');
        }
        for (JsonNode i : review.path("issues")) {
            fb.append("- ").append(i.asText()).append('\n');
        }
        if (review.path("hook").asText().equals("fail")) {
            fb.append("- 开头未过钩：前三行必须从上一章结尾的张力里直接推进，禁止环境/氛围铺陈。\n");
        }
        if (review.path("continuity").asText().equals("fail")) {
            fb.append("- 衔接断裂：读者无法定位上一章结束时的情境，开头须回到上一章结尾的时间/地点/在场人物。\n");
        }
        if (review.path("consequence").asText().equals("fail")) {
            fb.append("- 事件后果断裂：本章无视了上一章结尾的未竟事件/钩子，开场必须先与之对接（兑现、交代或明确推进），再展开新内容。\n");
        }
        if (fb.isEmpty()) {
            fb.append("- 读者判定注水或无张力：删掉所有纯装饰描写，让每一段都推进事件或揭示信息。\n");
        }
        String user = """
                任务：修订第 %d 章全文。没耐心的网文读者给出以下弃书理由：
                %s
                要求：情节、信息与对白立场全部保留；删掉全部纯装饰描写与重复观察；推动情节的对白可以增加；
                分行节奏与风格特征保持本书原貌；直接输出修订后的完整正文，不要输出思考过程。

                【第 %d 章全文（在此版本上修改）】
                %s
                """.formatted(ch.chapterNo(), fb, ch.chapterNo(), fullText);
        LlmPort.ChatResult r = llmPort.chat(new LlmPort.ChatRequest(
                LlmNode.READER_FIX, novelId, ch.id(),
                List.of(LlmPort.Message.system("你是网文编辑，任务是让这一章「每一行都值得读」：删注水、保情节、补张力。"),
                        LlmPort.Message.user(user)),
                0.5));
        String cleaned = ChapterPipelineService.stripTitleLine(
                SceneService.cleanDraft(r.content()), ch.title());
        double lenMin = tuning.d("reader_fix_len_min", 0.5);
        double lenMax = tuning.d("reader_fix_len_max", 1.15);
        if (cleaned.isBlank() || cleaned.length() < fullText.length() * lenMin
                || cleaned.length() > fullText.length() * lenMax) {
            log.warn("第 {} 章读者重写稿长度异常（{} 字符），保留原文", ch.chapterNo(), cleaned.length());
            return null;
        }
        return cleaned;
    }

    /** 回溯/UI 用：对已有正文的章跑一次审校，只落报告，不动正文与状态。 */
    public void reviewExisting(long chapterId) {        ChapterDO ch = chapterDAO.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (ch.fullText() == null || ch.fullText().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该章无正文，无法审校");
        }
        reviewOnce(ch.novelId(), ch, ch.fullText(), 1);
    }

    /** 单轮审校：解析失败重试 1 次（原因喂回），仍失败 fail-open 落 skipped 报告。 */
    private JsonNode reviewOnce(long novelId, ChapterDO ch, String text, int round) {
        String user = userPrompt(novelId, ch, text);
        try {
            JsonNode node = llmJson.ask(new LlmPort.ChatRequest(
                            LlmNode.AI_REVIEW, novelId, ch.id(),
                            List.of(LlmPort.Message.system(SYSTEM),
                                    LlmPort.Message.user(user)),
                            0.2),
                    n -> {
                        String verdict = n.path("verdict").asText("");
                        if (!List.of("pass", "minor", "blocker").contains(verdict)) {
                            throw new LlmJson.Bad("verdict 非法: " + verdict);
                        }
                        return n;
                    }, 2);
            gateReportDAO.insert(ch.id(), null, "ai_review", round,
                    !"blocker".equals(node.path("verdict").asText()), node);
            log.info("第 {} 章审校 round={}：{}（{} 条问题）", ch.chapterNo(), round,
                    node.path("verdict").asText(), node.path("issues").size());
            return node;
        } catch (IllegalStateException e) {
            log.warn("第 {} 章审校输出两次解析失败，本轮跳过（fail-open）：{}", ch.chapterNo(), e.getMessage());
            gateReportDAO.insert(ch.id(), null, "ai_review", round, true,
                    Map.of("skipped", true, "reason", "parse_failed"));
            return mapper.createObjectNode()
                    .put("verdict", "skipped")
                    .put("summary", "审校输出解析失败，本轮跳过");
        }
    }

    private String userPrompt(long novelId, ChapterDO ch, String text) {
        List<String> digests = packer.recentDigests(novelId, ch.chapterNo(), 3);
        String prevTail = packer.prevTail(novelId, ch.chapterNo());
        StringBuilder sb = new StringBuilder();
        sb.append("【世界设定与大纲】\n").append(packer.world(novelId)).append("\n\n");
        sb.append("【人物卡】\n").append(packer.characters(novelId)).append("\n\n");
        String ws = packer.worldState(novelId, ch.chapterNo());
        sb.append("【世界状态（上一章结束时）】\n").append(ws == null ? "（无）" : ws).append("\n\n");
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
        LlmPort.ChatResult r = llmPort.chat(new LlmPort.ChatRequest(
                LlmNode.AI_REVIEW_REVISE, novelId, ch.id(),
                List.of(LlmPort.Message.system("你是执行审校修订的网文编辑，只做被点名的最小修改。"),
                        LlmPort.Message.user(user)),
                0.5));
        String cleaned = ChapterPipelineService.stripTitleLine(
                SceneService.cleanDraft(r.content()), ch.title());
        double floor = tuning.d("ai_review_fix_floor", 0.6);
        if (cleaned.isBlank() || cleaned.length() < fullText.length() * floor) {
            log.warn("第 {} 章审校修订稿长度异常（{} 字符），保留原文", ch.chapterNo(), cleaned.length());
            return null;
        }
        return cleaned;
    }
}
