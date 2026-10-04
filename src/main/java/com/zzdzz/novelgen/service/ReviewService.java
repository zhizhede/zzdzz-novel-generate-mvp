package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.zzdzz.novelgen.model.enums.GateType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 语义审校闭环：机械门禁之后的连续性/逻辑/错字审校。
 * BLOCKER 带问题清单修订一轮并复审，复审仍 BLOCKER 则交人工（管线不过稿）。
 * 报告落 gate_reports（gate_type='ai_review'），解析失败 fail-open 跳过——审校员故障不能卡死管线。
 * 本类零提示词文本：所有固定文案走 PromptTemplateService（node+phase 定位，库值优先、目录回退）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewService {

    private final LlmPort llmPort;      // 文本修订轮（reader_fix / ai_review_revise）
    private final LlmJson llmJson;      // JSON 评审轮（reader_review / ai_review）
    private final ContextPackerService packer;
    private final GateReportDataService gateReportData;
    private final ChapterDataService chapterData;
    private final ObjectMapper mapper;
    private final TuningService tuning;
    private final PromptTemplateService promptTemplates;
    private final GateService gateService;
    private final StageLog stageLog;


    /** 书级评审标准：gate_config 优先（按书定制），tuning 平台默认兜底；键名两边一致。 */
    private double perNovel(long novelId, String key, double def) {
        return gateService.configValue(novelId, key, tuning.d(key, def));
    }

    /** 审校结论：revised 为修订后全文（null=未改），blocked=复审仍 BLOCKER，issues 为末轮意见摘要行（透明化）。 */
    public record Outcome(String revised, String verdict, boolean blocked, List<String> issues) {
        public Outcome(String revised, String verdict, boolean blocked) {
            this(revised, verdict, blocked, List.of());
        }
    }

    /** 管线闭环：审校 → BLOCKER 则带清单修订一轮 → 复审。onDelta 非空时思考流实时转发。 */
    public Outcome reviewAndFix(long novelId, ChapterDO ch, String fullText, LlmPort.StreamDelta onDelta) {
        JsonNode r1 = reviewOnce(novelId, ch, fullText, 1, onDelta);
        String verdict = r1.path("verdict").asText("skipped");
        if (!"blocker".equals(verdict)) {
            return new Outcome(null, verdict, false, aiIssueLines(r1));
        }
        log.warn("第 {} 章审校判 BLOCKER（{} 条），带清单修订一轮", ch.getChapterNo(),
                r1.path("issues").size());
        String revised = reviseForIssues(novelId, ch, fullText, r1);
        JsonNode r2 = reviewOnce(novelId, ch, revised != null ? revised : fullText, 2, onDelta);
        String v2 = r2.path("verdict").asText("skipped");
        boolean blocked = "blocker".equals(v2);
        return new Outcome(revised, blocked ? "blocker" : v2, blocked, aiIssueLines(r2));
    }

    public Outcome reviewAndFix(long novelId, ChapterDO ch, String fullText) {
        return reviewAndFix(novelId, ch, fullText, null);
    }

    /**
     * 读者评审闭环（反无聊闸门）：钩子/戏剧张力/章间衔接/注水率。
     * BLOCKER 带清单重写一轮并复审；复审仍 BLOCKER 交人工（auto 不过稿）。
     * 报告落 gate_reports（gate_type='reader_review'），解析失败 fail-open。
     */
    public Outcome readerReviewAndFix(long novelId, ChapterDO ch, String fullText, LlmPort.StreamDelta onDelta) {
        JsonNode r1 = readerOnce(novelId, ch, fullText, 1, onDelta);
        if (!"blocker".equals(r1.path("verdict").asText())) {
            // 复沓硬闸（2026-10-04）：各维度都过、但同一句/近似句反复（评审第 6 问的 repeat 清单）超阈值时，
            // 也治一轮。此前 repeat 只在判 BLOCKER 时才被 readerFix 消费，于是「过稿但通篇复沓」无处置——
            // 实跑迁移书「回来×12 / 进去×6 / 出来×8」、换皮书「冷光三次近重复」都是这么漏过去的。
            // 治不好也不阻塞（照常通过并落报告），避免把可用的稿子卡在人工。
            JsonNode repeat = r1.path("repeat");
            int minRepeat = (int) perNovel(novelId, "reader_repeat_fix_min", TuningDefaults.READER_REPEAT_FIX_MIN);
            if (repeat.isArray() && repeat.size() >= minRepeat) {
                log.warn("第 {} 章读者评审通过但复沓清单 {} 条（阈值 {}），执行一轮去复沓修订",
                        ch.getChapterNo(), repeat.size(), minRepeat);
                String polished = readerFix(novelId, ch, fullText, r1);
                if (polished != null) {
                    JsonNode r2 = readerOnce(novelId, ch, polished, 2, onDelta);
                    return new Outcome(polished, r2.path("verdict").asText("pass"), false, readerIssueLines(r2));
                }
            }
            return new Outcome(null, r1.path("verdict").asText("pass"), false, readerIssueLines(r1));
        }
        log.warn("第 {} 章读者评审判 BLOCKER（hook={} stakes={} continuity={} consequence={} fat_ratio={}），重写一轮",
                ch.getChapterNo(), r1.path("hook").asText(), r1.path("stakes").asText(),
                r1.path("continuity").asText(), r1.path("consequence").asText(), r1.path("fat_ratio").asText());
        String revised = readerFix(novelId, ch, fullText, r1);
        if (revised == null) {
            return new Outcome(null, "blocker", true, readerIssueLines(r1));
        }
        JsonNode r2 = readerOnce(novelId, ch, revised, 2, onDelta);
        boolean blocked = "blocker".equals(r2.path("verdict").asText());
        return new Outcome(revised, blocked ? "blocker" : "pass", blocked, readerIssueLines(r2));
    }

    public Outcome readerReviewAndFix(long novelId, ChapterDO ch, String fullText) {
        return readerReviewAndFix(novelId, ch, fullText, null);
    }

    /** 单轮读者评审：解析失败重试 1 次，仍失败 fail-open 落 skipped 报告。onDelta 非空时思考流转发。 */
    private JsonNode readerOnce(long novelId, ChapterDO ch, String text, int round, LlmPort.StreamDelta onDelta) {
        String prevTail = packer.prevTail(novelId, ch.getChapterNo());
        String prevBrief = packer.prevChapterBrief(novelId, ch.getChapterNo());
        String user = promptTemplates.getSection(LlmNode.READER_REVIEW, "user",
                java.util.Map.of("prev_tail", prevTail == null || prevTail.isBlank() ? "（无）" : prevTail,
                        "prev_brief", prevBrief == null || prevBrief.isBlank() ? "（本章是第一章，consequence 直接 pass）" : prevBrief,
                        "goal", java.util.Objects.toString(ch.getGoal(), ""),
                        "chapter_no", String.valueOf(ch.getChapterNo()),
                        "full_text", text == null ? "" : text));
        try {
            double fatSoft = perNovel(novelId, "reader_fat_ratio_block", TuningDefaults.READER_FAT_RATIO_BLOCK);
            double fatHard = perNovel(novelId, "reader_fat_ratio_hard", TuningDefaults.READER_FAT_RATIO_HARD);
            JsonNode node = llmJson.ask(new LlmPort.ChatRequest(
                            LlmNode.READER_REVIEW, novelId, ch.getId(),
                            List.of(LlmPort.Message.system(
                                            promptTemplates.format(LlmNode.READER_REVIEW, "system", fatSoft, fatHard)),
                                    LlmPort.Message.user(user)),
                            LlmTemps.READER_REVIEW),
                    n -> {
                        String verdict = n.path("verdict").asText("");
                        if (!List.of("pass", "blocker").contains(verdict)) {
                            throw new LlmJson.Bad("verdict 非法: " + verdict);
                        }
                        return n;
                    }, 2, onDelta);
            node = downgradeFatOnly(ch, node, fatHard);
            gateReportData.insert(ch.getId(), null, GateType.READER_REVIEW.wire(), round,
                    !"blocker".equals(node.path("verdict").asText()), node);
            log.info("第 {} 章读者评审 round={}：{}（fat_ratio={}）", ch.getChapterNo(), round,
                    node.path("verdict").asText(), node.path("fat_ratio").asText());
            stageLog.emit(novelId, ch.getChapterNo(), StageLog.Stage.READER, StageLog.Phase.VERDICT,
                    Map.of("round", round, "verdict", node.path("verdict").asText("pass"),
                            "issues", readerIssueLines(node)));
            return node;
        } catch (IllegalStateException e) {
            log.warn("第 {} 章读者评审输出两次解析失败，本轮跳过（fail-open）", ch.getChapterNo());
            gateReportData.insert(ch.getId(), null, GateType.READER_REVIEW.wire(), round, true,
                    Map.of("skipped", true, "reason", "parse_failed"));
            return mapper.createObjectNode().put("verdict", "pass").put("summary", "解析失败跳过");
        }
    }

    /** 连贯性优先（本书标准）：四个结构性维度全过、仅 fat_ratio 超软阈值时降级为 pass；超硬上限仍拦。 */
    private JsonNode downgradeFatOnly(ChapterDO ch, JsonNode node, double fatHard) {
        if (!"blocker".equals(node.path("verdict").asText()) || !(node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
            return node;
        }
        boolean structuralFail = List.of("hook", "stakes", "continuity", "consequence").stream()
                .anyMatch(k -> "fail".equals(node.path(k).asText()));
        double fat = node.path("fat_ratio").asDouble(0);
        if (structuralFail || fat > fatHard) {
            return node;
        }
        log.info("第 {} 章仅注水比超标（{}），结构性四问全过，按本书标准不拦（硬上限 {}）", ch.getChapterNo(), fat, fatHard);
        return obj.put("raw_verdict", "blocker")
                .put("note", "仅 fat_ratio 超软阈值，四问全过且未超硬上限 " + fatHard + "，连贯性优先降级为 pass");
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
        // 复沓清单（评审第 6 问产出，带次数）：不消费它就只是落库报告，重写轮拿不到（提示词里承诺"直接喂给下一稿"）
        for (JsonNode r : review.path("repeat")) {
            fb.append("- 复沓（同一句/近似句反复）：").append(r.asText()).append("　只保留一处，其余删掉\n");
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
        String band = "目标 " + ch.getBudgetMin() + "–" + (int) (ch.getBudgetMax() * 1.05)
                + " 字；删注水后低于目标时可用推进情节的对白与动作补足，但与保留剧情冲突时宁短勿注";
        String user = promptTemplates.format(LlmNode.READER_FIX, "user", ch.getChapterNo(), fb, band, ch.getChapterNo(), fullText);
        LlmPort.ChatResult r = llmPort.chat(new LlmPort.ChatRequest(
                LlmNode.READER_FIX, novelId, ch.getId(),
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.READER_FIX, "system")),
                        LlmPort.Message.user(user)),
                LlmTemps.READER_FIX));
        String cleaned = ChapterPipelineService.stripTitleLine(
                SceneService.cleanDraft(r.content()), ch.getTitle());
        // 长度上限保留相对护栏防失控扩写；下限允许按书牺牲字数（reader_fix_len_min × 预算下限，
        // 平台默认 0.75）：轻微低于预算不扩写（连贯性优先），跌破恢复线才走一轮恢复扩写
        double lenMax = perNovel(novelId, "reader_fix_len_max", TuningDefaults.READER_FIX_LEN_MAX);
        if (cleaned.isBlank() || cleaned.length() > fullText.length() * lenMax) {
            log.warn("第 {} 章读者重写稿长度异常（{} 字符），保留原文", ch.getChapterNo(), cleaned.length());
            return null;
        }
        int recoverFloor = (int) (ch.getBudgetMin() * perNovel(novelId, "reader_fix_len_min", TuningDefaults.READER_FIX_LEN_MIN));
        if (cleaned.length() < recoverFloor) {
            log.warn("第 {} 章读者重写稿 {} 字低于恢复线 {}（下限 {}），启动恢复扩写",
                    ch.getChapterNo(), cleaned.length(), recoverFloor, ch.getBudgetMin());
            cleaned = recoverLength(novelId, ch, cleaned, fb);
        }
        return cleaned;
    }

    /** 恢复扩写：把被过度删除的情节节拍以对白/动作形式扩回预算带；结果仍异常则返回扩写前文本。 */
    private String recoverLength(long novelId, ChapterDO ch, String cleaned, StringBuilder fb) {
        int floor = ch.getBudgetMin();
        int cap = (int) (ch.getBudgetMax() * 1.05);
        String user = promptTemplates.format(LlmNode.READER_FIX, "user_recover",
                ch.getChapterNo(), cleaned.length(), floor, floor, cap, fb, cleaned);
        try {
            LlmPort.ChatResult r = llmPort.chat(new LlmPort.ChatRequest(
                    LlmNode.READER_FIX, novelId, ch.getId(),
                    List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.READER_FIX, "system")),
                            LlmPort.Message.user(user)),
                    LlmTemps.RECOVERY_EXPAND));
            String recovered = ChapterPipelineService.stripTitleLine(
                    SceneService.cleanDraft(r.content()), ch.getTitle());
            if (!recovered.isBlank() && recovered.length() >= cleaned.length()
                    && recovered.length() <= fullTextLimit(ch)) {
                log.info("第 {} 章恢复扩写完成：{} 字", ch.getChapterNo(), recovered.length());
                return recovered;
            }
            log.warn("第 {} 章恢复扩写结果异常（{} 字符），保留扩写前文本", ch.getChapterNo(), recovered.length());
        } catch (Exception e) {
            log.warn("第 {} 章恢复扩写失败，保留扩写前文本：{}", ch.getChapterNo(), e.getMessage());
        }
        return cleaned;
    }

    private int fullTextLimit(ChapterDO ch) {
        double lenMax = perNovel(ch.getNovelId(), "reader_fix_len_max", 1.15);
        return (int) (ch.getBudgetMax() * 1.05 * lenMax);
    }

    /** 回溯/UI 用：对已有正文的章跑一次审校，只落报告，不动正文与状态。 */
    public void reviewExisting(long chapterId) {        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (ch.getFullText() == null || ch.getFullText().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该章无正文，无法审校");
        }
        reviewOnce(ch.getNovelId(), ch, ch.getFullText(), 1);
    }

    /** 单轮审校：解析失败重试 1 次（原因喂回），仍失败 fail-open 落 skipped 报告。onDelta 非空时思考流转发。 */
    private JsonNode reviewOnce(long novelId, ChapterDO ch, String text, int round) {
        return reviewOnce(novelId, ch, text, round, null);
    }

    private JsonNode reviewOnce(long novelId, ChapterDO ch, String text, int round, LlmPort.StreamDelta onDelta) {
        String user = userPrompt(novelId, ch, text);
        try {
            JsonNode node = llmJson.ask(new LlmPort.ChatRequest(
                            LlmNode.AI_REVIEW, novelId, ch.getId(),
                            List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.AI_REVIEW, "system")),
                                    LlmPort.Message.user(user)),
                            LlmTemps.AI_REVIEW),
                    n -> {
                        String verdict = n.path("verdict").asText("");
                        if (!List.of("pass", "minor", "blocker").contains(verdict)) {
                            throw new LlmJson.Bad("verdict 非法: " + verdict);
                        }
                        return n;
                    }, 2, onDelta);
            gateReportData.insert(ch.getId(), null, GateType.AI_REVIEW.wire(), round,
                    !"blocker".equals(node.path("verdict").asText()), node);
            log.info("第 {} 章审校 round={}：{}（{} 条问题）", ch.getChapterNo(), round,
                    node.path("verdict").asText(), node.path("issues").size());
            stageLog.emit(novelId, ch.getChapterNo(), StageLog.Stage.REVIEW, StageLog.Phase.VERDICT,
                    Map.of("round", round, "verdict", node.path("verdict").asText("skipped"),
                            "issues", aiIssueLines(node)));
            return node;
        } catch (IllegalStateException e) {
            log.warn("第 {} 章审校输出两次解析失败，本轮跳过（fail-open）：{}", ch.getChapterNo(), e.getMessage());
            gateReportData.insert(ch.getId(), null, GateType.AI_REVIEW.wire(), round, true,
                    Map.of("skipped", true, "reason", "parse_failed"));
            return mapper.createObjectNode()
                    .put("verdict", "skipped")
                    .put("summary", "审校输出解析失败，本轮跳过");
        }
    }

    /** 审校意见摘要行（事件/Outcome 透明化）：type：quote——explanation，超 80 字截断。 */
    static List<String> aiIssueLines(JsonNode node) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode i : node.path("issues")) {
            String line = i.path("type").asText("issue") + "：「" + i.path("quote").asText("") + "」"
                    + (i.path("explanation").asText("").isBlank() ? "" : "——" + i.path("explanation").asText());
            out.add(line.length() > 80 ? line.substring(0, 80) + "…" : line);
        }
        return out;
    }

    /** 读者评审摘要行：四结构性维度 + 注水率 + 意见列表。 */
    static List<String> readerIssueLines(JsonNode node) {
        List<String> out = new java.util.ArrayList<>();
        for (String k : List.of("hook", "stakes", "continuity", "consequence")) {
            String v = node.path(k).asText("");
            if (!v.isEmpty()) {
                out.add(k + "=" + v);
            }
        }
        if (!node.path("fat_ratio").isMissingNode()) {
            out.add("fat_ratio=" + node.path("fat_ratio").asText());
        }
        for (JsonNode i : node.path("issues")) {
            out.add("意见：" + i.asText());
        }
        // 复沓清单也要出现在报告/UI 里：它是「过稿但读起来重复」的唯一证据，
        // 此前只进过 readerFix 的重写提示词，报告里看不到（实跑复盘时只能靠人肉数词频）。
        for (JsonNode r : node.path("repeat")) {
            out.add("复沓：" + r.asText());
        }
        return out;
    }

    private String userPrompt(long novelId, ChapterDO ch, String text) {
        List<String> digests = packer.recentDigests(novelId, ch.getChapterNo(), 3);
        String prevTail = packer.prevTail(novelId, ch.getChapterNo());
        String ws = packer.worldState(novelId, ch.getChapterNo());
        String digestBlock = digests.isEmpty() ? "（无）\n"
                : digests.stream().map(d -> "---\n" + d + "\n").collect(java.util.stream.Collectors.joining());
        return promptTemplates.getSection(LlmNode.AI_REVIEW, "user",
                java.util.Map.of("world", java.util.Objects.toString(packer.world(novelId), ""),
                        "characters", java.util.Objects.toString(packer.characters(novelId), ""),
                        "world_state", ws == null ? "（无）" : ws,
                        "digests", digestBlock,
                        "prev_tail", prevTail == null || prevTail.isBlank() ? "（无）" : prevTail,
                        "chapter_no", String.valueOf(ch.getChapterNo()),
                        "full_text", text == null ? "" : text));
    }

    /**
     * 改稿用「事实基准」：世界状态快照（时间/位置/随身物品/新承诺/未解）+ 上一章后果 + 本章章纲。
     *
     * <p>为什么必须给修订轮：审校轮本来就拿这些（{@code userPrompt} 里有 world_state/prev_tail/digests），
     * 所以它能判出「陆朴说同行三年、可实际只走了半月」；但修订轮此前只拿到 BLOCKER 条目 + 全文，
     * 只能就句子改句子——同一处矛盾的其他表述改不干净，下一轮又报一条新的（实跑第 3 章两轮都栽在这）。
     * 把基准一并给下去，改稿才有「以谁为准」的依据。
     */
    private String factBaseline(long novelId, ChapterDO ch) {
        StringBuilder sb = new StringBuilder();
        String ws = packer.worldState(novelId, ch.getChapterNo());
        sb.append("· 上一章结束时的事实状态：\n").append(ws == null ? "（无快照）" : ws).append('\n');
        String prevBrief = packer.prevChapterBrief(novelId, ch.getChapterNo());
        sb.append("· 上一章发生了什么：")
                .append(prevBrief == null || prevBrief.isBlank() ? "（本章是第一章）" : prevBrief).append('\n');
        sb.append("· 本章的目标与钩子：目标=")
                .append(Objects.toString(ch.getGoal(), "（无）")).append("；钩子=")
                .append(Objects.toString(ch.getHook(), "（无）")).append('\n');
        return sb.toString().strip();
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
        String user = promptTemplates.format(LlmNode.AI_REVIEW_REVISE, "user", ch.getChapterNo(), fb,
                factBaseline(novelId, ch), ch.getChapterNo(), fullText);
        LlmPort.ChatResult r = llmPort.chat(new LlmPort.ChatRequest(
                LlmNode.AI_REVIEW_REVISE, novelId, ch.getId(),
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.AI_REVIEW_REVISE, "system")),
                        LlmPort.Message.user(user)),
                LlmTemps.AI_REVIEW_REVISE));
        String cleaned = ChapterPipelineService.stripTitleLine(
                SceneService.cleanDraft(r.content()), ch.getTitle());
        double floor = perNovel(novelId, "ai_review_fix_floor", TuningDefaults.AI_REVIEW_FIX_FLOOR);
        if (cleaned.isBlank() || cleaned.length() < fullText.length() * floor) {
            log.warn("第 {} 章审校修订稿长度异常（{} 字符），保留原文", ch.getChapterNo(), cleaned.length());
            return null;
        }
        return cleaned;
    }
}
