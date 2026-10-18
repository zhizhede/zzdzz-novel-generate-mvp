package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.DraftHistoryMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetWorldDO;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.DraftHistoryDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO;
import com.zzdzz.novelgen.model.vo.derive.DraftVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⑥b 生成具体稿（v3.7 分批：全局头 ⑥b-0 + 连续章段窗 ⑥b-1..n，幂等单元制）。
 *
 * <p>每次调用只推进一个单元：头未完成做头；否则取第一个未完成窗；全部完成后终局对账
 * 并 run→DRAFTING。头产出全书级实体（书名/主角/cast/truth/brief，落 story_assets），
 * 窗逐批回填 beat_instance.filled_text 与 chapters.draft_outline（每窗落库、进度可见）。
 * REWORK 入口：归档正文 + 清稿 + 清头产物后回 PLANNING（本调用返回 RESET，客户端续调）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveDraftService {

    public static final String NODE_DRAFT = "derive_draft";

    /** 窗宽 4 章（2026-10-18 提速：今晚模型单窗 8 章输出失控屡超 10 分钟；4 章减半单次输出）。 */
    static final int WINDOW_CHAPTERS = 4;

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    static final String HEAD_PROMPT = """
            你在为一部小说生成"具体稿·全局头"（不是正文）。输入含：域格、类别与目标节奏、结局形状、
            世界观条目、标签层主线、全书章节计划（章号/章题/预算，只作规模参考）。
            要求：
            1. 起作品名、主角与配角人名、地名；结局形状落地；事件必须在域格内成立；
            2. 主角名不得出现在禁用名单里，不得用"沈X"式单姓+单字套路名；
            3. 输出 cast 全书人物表（主角排首，含配角与关键路人）：name=定名人名（2~3字）、
               role=身份一句话、gender=性别、persona={temperament性格底色, conduct行为准则
               （什么绝不会做/什么一定做）, speech说话方式, desire核心欲望, flaw缺陷或恐惧}
               ——persona 优先从『题材意图』已有抽取、禁止与意图矛盾；此表是全书唯一合法人名
               来源，后续分批只许用表内人名；
            4. 类别含「推理」或「悬疑」时必须额外输出『真相卡』truth=
               {reveal_chapter真相揭示章, clues:[{clue现象级线索, planted_chapter}],
                timeline_anchors:[{fact纯数字或时刻事实, chapter}],
                solution:{culprit,motive,method}}——
               reveal_chapter 不得早于 max(planted_chapter)；clue 只写可观测现象，
               禁止写成解释或出现 solution 核心内容；anchors 不得含因果结论；非推理体裁省略 truth；
            5. 所有体裁必须输出『brief』拆分：{"premise":"人物设定/世界观/氛围/描写红线
               （全程可用，≤300字）","ending":"结局与真相相关段落（无结局信息给空串）"}
               ——premise 不得含结局答案，ending 集中放 twist/真凶/动机/结局走向；
            6. 若输入带『题材意图』：人物设定、走向与描写红线以它为准；意图若指明世界观前提，
               人名/称谓/器物/礼俗按该体系取名，全程禁止混入异文化元素；
            7. 只输出全局头结构，不写正文与章纲，不提素材来源与编号。
            输出 JSON 对象：
            {"title":"作品名","summary":"一句话概括","protagonist":"主角名",
             "cast":[{"name":"…","role":"…","gender":"…",
                      "persona":{"temperament":"…","conduct":"…","speech":"…","desire":"…","flaw":"…"}}],
             "brief":{"premise":"…","ending":"…"},
             "truth":{"reveal_chapter":12,
                      "clues":[{"clue":"…","planted_chapter":3}],
                      "timeline_anchors":[{"fact":"…","chapter":5}],
                      "solution":{"culprit":"…","motive":"…","method":"…"}}}
            truth 仅推理/悬疑体裁输出。只输出 JSON 对象，无其他文字。
            """;

    static final String BATCH_PROMPT = """
            你在为一部小说生成"具体稿·分批章纲"（不是正文）。输入含：全局头（protagonist/cast 人名表
            /brief/truth）、域格、类别与节奏、世界观、主线、前批章纲概览、本窗章节计划与本窗拍清单。
            要求：
            1. 每个本窗拍实例填实成具体情节点（1~2 句，写 filled）；确实用不上的拍放进 skipped 数组；
            2. 本窗每章给 draft_outline（本章具体大纲 2~3 句）与 title 定稿（≤12字，可沿用或改）；
            3. 人名、性别、称谓严格沿用全局头 cast 表，禁止新造任何新名字（含路人）——
               需要新配角时改用表内人名或把该拍放进 skipped；
            4. 写纲无知之幕：章号 < reveal_chapter 的章（若输入带 truth），draft_outline 与 title
               只写在场角色认知内的情节走向，禁写 solution/真凶身份/解法/视角反转；
            5. 与前批章纲衔接：不推翻前批已定事实（人物关系、已发生事件、关键物件）；
            6. 若输入带『题材意图』：走向与红线以它为准，拍清单只作结构骨架；
            7. 只输出本窗章纲结构，不写正文，不提素材来源与编号（对外只出现 seq）。
            输出 JSON 对象：
            {"chapters":[{"no":1,"title":"…","draft_outline":"…","beats":[{"seq":1,"filled":"…"}]}],
             "skipped":[…]}
            只输出 JSON 对象，无其他文字。
            """;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final AssetWorldMapper worldMapper;
    private final AssetBeatMapper beatMapper;
    private final WorkFingerprintMapper fingerprintMapper;
    private final DraftHistoryMapper draftHistoryMapper;
    private final LlmJson llmJson;

    /** ⑥b：幂等推进一个单元（RESET/HEAD/BATCH/DONE，v3.7；客户端循环调用至 allDone）。 */
    public DraftVO draft(long runId) {
        try {
            return draftInner(runId);
        } finally {
            DeriveActivity.end(runId);
        }
    }

    /** 长任务实时活动快照（⑥b/闸/⑦/⑧ 共用；无活跃活动 active=false）。 */
    public com.zzdzz.novelgen.model.vo.derive.DraftProgressVO progress(long runId) {
        return DeriveActivity.snapshot(runId);
    }

    void beginProgress(long runId, String phase) {
        DeriveActivity.begin(runId, phase);
    }

    void touchProgress(long runId, String piece) {
        DeriveActivity.touch(runId, piece);
    }

    DraftVO draftInner(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!("PLANNING".equals(run.getStatus()) || "REWORK".equals(run.getStatus())) || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "run 应处于 PLANNING/REWORK 且已计划（当前 " + run.getStatus() + "，novelId=" + run.getNovelId() + "）");
        }
        long novelId = run.getNovelId();

        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByAsc(BeatInstanceDO::getSeq));
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        List<Integer> chapterNos = chapters.stream().map(ChapterDO::getChapterNo).toList();
        List<List<Integer>> wins = windows(chapterNos, WINDOW_CHAPTERS);

        if ("REWORK".equals(run.getStatus())) {
            archiveRejectedDrafts(run, novelId, chapters);
            chapterMapper.clearForRewrite(novelId);
            chapterMapper.clearDraftOutlines(novelId);
            novelMapper.clearDraftAssets(novelId);
            for (BeatInstanceDO b : beats) {
                b.setFilledText(null);
                b.setStatus("PLANNED");
                beatInstanceMapper.updateById(b);
            }
            runMapper.updateStatus(runId, "PLANNING", null);
            runMapper.updateStepStatus(runId, DeriveGateService.gateReset(run.getStepStatus()));
            log.info("⑥b REWORK 回炉清稿：runId={} novelId={} 窗数={}（GATE 作废，客户端续调从头跑）",
                    runId, novelId, wins.size());
            return new DraftVO(runId, novelId, "", "", 0, 0, 0, "RESET", 0, wins.size(), false);
        }

        NovelDO novel = novelMapper.selectById(novelId);
        boolean reasoning = reasoningGenre(run.getParamTags());
        String gateDirective = DeriveGateService.gateDirective(run.getStepStatus());

        if (!headDone(novel)) {
            List<String> banned = bannedNames(run);
            HeadContext ctx = new HeadContext(chapterNos, banned, reasoning);
            String user = buildHeadUser(run, chapters, worldLabel(run), banned, gateDirective);
            beginProgress(runId, "全局头");
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_DRAFT, runId, novelId,
                            List.of(LlmPort.Message.system(HEAD_PROMPT), LlmPort.Message.user(user)), 0.4),
                    root -> parseHead(root, ctx), 3, (think, piece) -> touchProgress(runId, piece));
            novelMapper.updateMeta(novelId, out.path("title").asText(""), out.path("summary").asText(""));
            novelMapper.updateCast(novelId, out.path("protagonist").asText(""), out.path("cast").toString());
            novelMapper.updateBrief(novelId, out.path("brief").toString());
            if (out.path("truth").isObject()) {
                novelMapper.updateTruth(novelId, out.path("truth").toString());
            }
            log.info("⑥b-0 全局头：runId={} novelId={} 书名={} 主角={} cast={}",
                    runId, novelId, out.path("title").asText(""), out.path("protagonist").asText(""),
                    out.path("cast").size());
            return new DraftVO(runId, novelId, out.path("title").asText(""), out.path("protagonist").asText(""),
                    0, 0, 0, "HEAD", 0, wins.size(), false);
        }

        JsonNode assets = readTreeSafe(novel.getStoryAssets());
        Map<Integer, String> labelBySeq = buildLabelBySeq(beats);
        for (int i = 0; i < wins.size(); i++) {
            List<Integer> win = wins.get(i);
            if (windowDone(win, chapters)) {
                continue;
            }
            List<BeatInstanceDO> winBeats = beats.stream()
                    .filter(b -> win.contains(b.getChapterNo()))
                    .toList();
            Set<Integer> winSeqs = new LinkedHashSet<>();
            for (BeatInstanceDO b : winBeats) {
                winSeqs.add(b.getSeq());
            }
            BatchContext ctx = new BatchContext(new ArrayList<>(winSeqs), win);
            String user = buildBatchUser(run, chapters, win, winBeats, labelBySeq, worldLabel(run), assets, gateDirective);
            beginProgress(runId, "窗 " + (i + 1) + "/" + wins.size()
                    + "（章 " + win.get(0) + "-" + win.get(win.size() - 1) + "）");
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_DRAFT, runId, novelId,
                            List.of(LlmPort.Message.system(BATCH_PROMPT), LlmPort.Message.user(user)), 0.4),
                    root -> parseBatch(root, ctx), 3, (think, piece) -> touchProgress(runId, piece));
            Map<Integer, String> filled = new HashMap<>();
            for (JsonNode c : out.path("chapters")) {
                for (JsonNode b : c.path("beats")) {
                    filled.put(b.path("seq").asInt(), b.path("filled").asText());
                }
            }
            Set<Integer> skipped = new HashSet<>();
            for (JsonNode s : out.path("skipped")) {
                skipped.add(s.asInt());
            }
            int filledCount = 0;
            int skippedCount = 0;
            for (BeatInstanceDO b : winBeats) {
                if (skipped.contains(b.getSeq())) {
                    b.setStatus("SKIPPED");
                    skippedCount++;
                } else {
                    b.setFilledText(filled.get(b.getSeq()));
                    b.setStatus("PLANNED");
                    filledCount++;
                }
                beatInstanceMapper.updateById(b);
            }
            for (JsonNode c : out.path("chapters")) {
                int no = c.path("no").asInt();
                ChapterDO ch = chapters.stream().filter(x -> x.getChapterNo() == no).findFirst().orElse(null);
                if (ch == null) {
                    continue;
                }
                ch.setTitle(c.path("title").asText(ch.getTitle()));
                ch.setDraftOutline(c.path("draft_outline").asText(""));
                chapterMapper.updateById(ch);
            }
            log.info("⑥b-1 分批窗：runId={} novelId={} 窗{}/{} 章{}-{} 填实{} 跳过{}",
                    runId, novelId, i + 1, wins.size(), win.get(0), win.get(win.size() - 1), filledCount, skippedCount);
            return new DraftVO(runId, novelId, "", assets.path("protagonist").asText(""),
                    win.size(), filledCount, skippedCount, "BATCH", i + 1, wins.size(), false);
        }

        globalReconcile(beats, chapters);
        runMapper.updateStatus(runId, "DRAFTING", null);
        log.info("⑥b 全部窗完成：runId={} novelId={} 窗数={} → DRAFTING", runId, novelId, wins.size());
        return new DraftVO(runId, novelId, novel.getTitle(), assets.path("protagonist").asText(""),
                chapters.size(), 0, 0, "DONE", wins.size(), wins.size(), true);
    }

    /**
     * 打回归档（V113 draft_history，附录 A-2）：clearForRewrite 之前把 content 非空的章
     * 逐行落档，version=归档时 qc_round。清稿后 write 失败不再丢稿（run11 教训）。
     * 空正文/首跑（qc_round=0）自然零行，幂等。
     */
    void archiveRejectedDrafts(RunDO run, long novelId, List<ChapterDO> chapters) {
        int round = run.getQcRound() == null ? 0 : run.getQcRound();
        int version = Math.max(1, round);
        int n = 0;
        for (ChapterDO ch : chapters) {
            if (ch.getContent() == null || ch.getContent().isBlank()) {
                continue;
            }
            DraftHistoryDO h = new DraftHistoryDO();
            h.setRunId(run.getId());
            h.setNovelId(novelId);
            h.setChapterId(ch.getId());
            h.setChapterNo(ch.getChapterNo());
            h.setVersion(version);
            h.setQcRound(round);
            h.setContent(ch.getContent());
            h.setCharCount(ch.getCharCount() == null ? ch.getContent().length() : ch.getCharCount());
            h.setReason("REWORK");
            draftHistoryMapper.insert(h);
            n++;
        }
        if (n > 0) {
            log.info("打回归档：runId={} novelId={} 归档 {} 章（version={}）→ draft_history", run.getId(), novelId, n, version);
        }
    }

    // ---- 输入构造 ----

    String buildHeadUser(RunDO run, List<ChapterDO> chapters, String worldLabel,
                         List<String> banned, String gateDirective) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        root.put("主角名禁用名单", String.join("、", banned));
        var chArr = root.putArray("章节计划");
        for (ChapterDO c : chapters) {
            var n = chArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("budget", c.getBudget());
        }
        return root.toString();
    }

    String buildBatchUser(RunDO run, List<ChapterDO> chapters, List<Integer> window,
                          List<BeatInstanceDO> winBeats, Map<Integer, String> labelBySeq,
                          String worldLabel, JsonNode assets, String gateDirective) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        var head = root.putObject("全局头");
        head.put("protagonist", assets.path("protagonist").asText(""));
        head.set("cast", assets.path("cast"));
        head.set("brief", assets.path("brief"));
        if (assets.path("truth").isObject()) {
            head.set("truth", assets.path("truth"));
        }
        var prev = root.putArray("前批章纲概览");
        for (ChapterDO c : chapters) {
            if (!window.contains(c.getChapterNo())
                    && c.getDraftOutline() != null && !c.getDraftOutline().isBlank()) {
                var n = prev.addObject();
                n.put("no", c.getChapterNo());
                n.put("title", c.getTitle());
                n.put("draft_outline", c.getDraftOutline());
            }
        }
        var chArr = root.putArray("本窗章节计划");
        for (ChapterDO c : chapters) {
            if (window.contains(c.getChapterNo())) {
                var n = chArr.addObject();
                n.put("no", c.getChapterNo());
                n.put("title", c.getTitle());
                n.put("budget", c.getBudget());
            }
        }
        var bArr = root.putArray("本窗拍清单");
        for (BeatInstanceDO b : winBeats) {
            var n = bArr.addObject();
            n.put("seq", b.getSeq());
            n.put("chapter", b.getChapterNo());
            n.put("beat_type", b.getBeatType());
            n.put("pacing", b.getPacing().toPlainString());
            n.put("label", labelBySeq.getOrDefault(b.getSeq(), ""));
        }
        return root.toString();
    }

    private void fillCommon(ObjectNode root, RunDO run, String worldLabel) {
        root.set("域", readTreeSafe(run.getDomainSnapshot()));
        root.set("类别", readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        if (run.getParamIntent() != null && !run.getParamIntent().isBlank()) {
            root.put("题材意图", run.getParamIntent());
        }
        root.put("世界观", worldLabel);
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
    }

    String worldLabel(RunDO run) {
        if (run.getPickedWorldId() == null) {
            return "";
        }
        AssetWorldDO w = worldMapper.selectById(run.getPickedWorldId());
        if (w == null) {
            return "";
        }
        return w.getLabelText() == null ? w.getExtractedText() : w.getLabelText();
    }

    List<String> bannedNames(RunDO run) {
        if (!Boolean.TRUE.equals(run.getAntiRepro())) {
            return List.of();
        }
        return fingerprintMapper.selectList(new LambdaQueryWrapper<WorkFingerprintDO>()
                        .eq(WorkFingerprintDO::getKind, "PROTAGONIST_NAME"))
                .stream().map(WorkFingerprintDO::getValue).filter(v -> v != null && !v.isBlank()).toList();
    }

    /** 原生拍标签层（ARC_SEG 按 arc_id+seg_no 回查原生链）；⑥b-1 窗体输入用。 */
    Map<Integer, String> buildLabelBySeq(List<BeatInstanceDO> beats) {
        Map<Long, List<String>> nativeLabels = new HashMap<>();
        Map<Integer, String> labelBySeq = new HashMap<>();
        for (BeatInstanceDO b : beats) {
            if ("ASSET".equals(b.getSource()) && b.getAssetId() != null) {
                AssetBeatDO row = beatMapper.selectById(b.getAssetId());
                labelBySeq.put(b.getSeq(), row == null ? "" : nullToEmpty(row.getLabelText(), row.getExtractedText()));
            } else if (b.getArcId() != null) {
                nativeLabels.computeIfAbsent(b.getArcId(), k -> {
                    List<AssetBeatDO> list = beatMapper.selectList(new LambdaQueryWrapper<AssetBeatDO>()
                            .eq(AssetBeatDO::getParentArcId, k)
                            .orderByAsc(AssetBeatDO::getId));
                    List<String> out = new ArrayList<>();
                    for (AssetBeatDO r : list) {
                        out.add(nullToEmpty(r.getLabelText(), r.getExtractedText()));
                    }
                    return out;
                });
                List<String> chain = nativeLabels.get(b.getArcId());
                int seg = b.getArcSegNo() == null ? -1 : b.getArcSegNo();
                labelBySeq.put(b.getSeq(), seg >= 0 && seg < chain.size() ? chain.get(seg) : "");
            }
        }
        return labelBySeq;
    }

    // ---- 解析校验 ----

    /** 推理体裁判定：tags 含 推理/悬疑 → truth 真相卡必出（v3.4）。 */
    static boolean reasoningGenre(String paramTags) {
        return paramTags != null && (paramTags.contains("推理") || paramTags.contains("悬疑"));
    }

    record HeadContext(List<Integer> chapterNos, List<String> banned, boolean reasoning) {
    }

    record BatchContext(List<Integer> windowSeqs, List<Integer> windowChapters) {
    }

    static JsonNode parseHead(JsonNode root, HeadContext ctx) {
        String title = root.path("title").asText("").trim();
        String summary = root.path("summary").asText("").trim();
        String protagonist = root.path("protagonist").asText("").trim();
        if (title.isEmpty() || summary.isEmpty() || protagonist.isEmpty()) {
            throw new LlmJson.Bad("title/summary/protagonist 均不能为空");
        }
        if (ctx.banned().contains(protagonist)) {
            throw new LlmJson.Bad("protagonist「" + protagonist + "」在禁用名单（历史成品已用），换一个");
        }
        JsonNode cast = root.path("cast");
        if (!cast.isArray() || cast.size() < 3) {
            throw new LlmJson.Bad("cast 至少 3 人");
        }
        Set<String> castNames = new HashSet<>();
        boolean castHasProtagonist = false;
        for (JsonNode c : cast) {
            String nm = c.path("name").asText("").trim();
            if (nm.isEmpty()) {
                throw new LlmJson.Bad("cast 有成员缺 name");
            }
            if (!castNames.add(nm)) {
                throw new LlmJson.Bad("cast 人名重复: " + nm);
            }
            if (nm.equals(protagonist)) {
                castHasProtagonist = true;
            }
            JsonNode persona = c.path("persona");
            if (!persona.isObject()) {
                throw new LlmJson.Bad("cast 成员「" + nm + "」缺 persona 人设卡（五键对象）");
            }
            for (String k : List.of("temperament", "conduct", "speech", "desire", "flaw")) {
                if (persona.path(k).asText("").trim().isEmpty()) {
                    throw new LlmJson.Bad("cast 成员「" + nm + "」persona 缺 " + k);
                }
            }
        }
        if (!castHasProtagonist) {
            throw new LlmJson.Bad("cast 必须包含 protagonist「" + protagonist + "」");
        }
        JsonNode brief = root.path("brief");
        if (!brief.isObject() || brief.path("premise").asText("").trim().isEmpty()) {
            throw new LlmJson.Bad("必须输出 brief.premise（人物/世界观/氛围/红线摘要，≤300字）");
        }
        if (ctx.reasoning()) {
            validateTruth(root.path("truth"), ctx.chapterNos());
        }
        return root;
    }

    static JsonNode parseBatch(JsonNode root, BatchContext ctx) {
        JsonNode chArr = root.path("chapters");
        if (!chArr.isArray() || chArr.size() != ctx.windowChapters().size()) {
            throw new LlmJson.Bad("chapters 章数必须 = 本窗章数 " + ctx.windowChapters().size());
        }
        Set<Integer> seenCh = new HashSet<>();
        Set<Integer> filled = new HashSet<>();
        for (JsonNode c : chArr) {
            int no = c.path("no").asInt(-1);
            if (!ctx.windowChapters().contains(no) || !seenCh.add(no)) {
                throw new LlmJson.Bad("chapters 章号非法或重复: " + no);
            }
            if (c.path("draft_outline").asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("第" + no + "章缺 draft_outline");
            }
            for (JsonNode b : c.path("beats")) {
                int seq = b.path("seq").asInt(0);
                if (!ctx.windowSeqs().contains(seq) || !filled.add(seq)) {
                    throw new LlmJson.Bad("filled beats 含非法或重复 seq: " + seq);
                }
                if (b.path("filled").asText("").trim().isEmpty()) {
                    throw new LlmJson.Bad("seq " + seq + " 缺 filled");
                }
            }
        }
        Set<Integer> skipped = new HashSet<>();
        for (JsonNode s : root.path("skipped")) {
            int seq = s.asInt(0);
            if (!ctx.windowSeqs().contains(seq) || filled.contains(seq) || !skipped.add(seq)) {
                throw new LlmJson.Bad("skipped 含非法/重复 seq: " + seq);
            }
        }
        Set<Integer> union = new HashSet<>(filled);
        union.addAll(skipped);
        if (union.size() != ctx.windowSeqs().size()) {
            throw new LlmJson.Bad("filled ∪ skipped 必须恰好覆盖本窗全部 " + ctx.windowSeqs().size()
                    + " 拍（当前 " + union.size() + "）");
        }
        JsonNode newNames = root.path("new_names");
        if (newNames.isArray() && !newNames.isEmpty()) {
            throw new LlmJson.Bad("禁止新造人名（new_names 必须为空）——改用全局头 cast 表内人名或把该拍放进 skipped");
        }
        return root;
    }

    static void validateTruth(JsonNode truth, List<Integer> chapterNos) {
        if (!truth.isObject()) {
            throw new LlmJson.Bad("推理体裁（tags 含 推理/悬疑）必须输出 truth 真相卡");
        }
        int reveal = truth.path("reveal_chapter").asInt(-1);
        if (!chapterNos.contains(reveal)) {
            throw new LlmJson.Bad("truth.reveal_chapter 越界或缺失: " + reveal);
        }
        JsonNode sol = truth.path("solution");
        if (!sol.isObject()
                || sol.path("culprit").asText("").trim().isEmpty()
                || sol.path("motive").asText("").trim().isEmpty()
                || sol.path("method").asText("").trim().isEmpty()) {
            throw new LlmJson.Bad("truth.solution 缺 culprit/motive/method 三要素");
        }
        JsonNode clues = truth.path("clues");
        if (!clues.isArray() || clues.size() < 3) {
            throw new LlmJson.Bad("truth.clues 至少 3 条（当前 "
                    + (clues.isArray() ? clues.size() : 0) + "）");
        }
        int maxPlanted = -1;
        String culprit = sol.path("culprit").asText("");
        for (JsonNode cl : clues) {
            int pc = cl.path("planted_chapter").asInt(-1);
            String clueText = cl.path("clue").asText("").trim();
            if (clueText.isEmpty() || !chapterNos.contains(pc)) {
                throw new LlmJson.Bad("truth.clues 有缺 clue 或 planted_chapter 越界: " + pc);
            }
            if (!culprit.isEmpty() && clueText.contains(culprit)) {
                throw new LlmJson.Bad("clue「" + clueText + "」直接出现 solution 内容——"
                        + "clue 只许写可观测现象，不许写解释或答案");
            }
            maxPlanted = Math.max(maxPlanted, pc);
        }
        if (reveal < maxPlanted) {
            throw new LlmJson.Bad("truth.reveal_chapter(" + reveal + ") 不得早于最晚埋设章(" + maxPlanted + ")");
        }
        for (JsonNode a : truth.path("timeline_anchors")) {
            if (a.path("fact").asText("").trim().isEmpty()
                    || !chapterNos.contains(a.path("chapter").asInt(-1))) {
                throw new LlmJson.Bad("truth.timeline_anchors 有缺 fact 或 chapter 越界");
            }
        }
    }

    // ---- 窗与对账 ----

    /** 连续章段窗（确定性、不消耗 seed）：按章号升序切 size 段。 */
    static List<List<Integer>> windows(List<Integer> chapterNos, int size) {
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < chapterNos.size(); i += size) {
            out.add(List.copyOf(chapterNos.subList(i, Math.min(i + size, chapterNos.size()))));
        }
        return out;
    }

    /** 窗完成判定 = 窗内各章 draft_outline 非空（进度即库态，幂等依据）。 */
    static boolean windowDone(List<Integer> window, List<ChapterDO> chapters) {
        for (ChapterDO c : chapters) {
            if (window.contains(c.getChapterNo())
                    && (c.getDraftOutline() == null || c.getDraftOutline().isBlank())) {
                return false;
            }
        }
        return true;
    }

    /** 头完成判定 = story_assets.protagonist 非空（REWORK 清头后逼 ⑥b-0 重跑）。 */
    static boolean headDone(NovelDO novel) {
        JsonNode assets = readTreeSafe(novel.getStoryAssets());
        return !assets.path("protagonist").asText("").trim().isEmpty();
    }

    /** 终局对账：全拍 filled 或 SKIPPED、全章有纲。 */
    static void globalReconcile(List<BeatInstanceDO> beats, List<ChapterDO> chapters) {
        List<Integer> missingBeats = new ArrayList<>();
        for (BeatInstanceDO b : beats) {
            boolean filled = b.getFilledText() != null && !b.getFilledText().isBlank();
            boolean skipped = "SKIPPED".equals(b.getStatus());
            if (!filled && !skipped) {
                missingBeats.add(b.getSeq());
            }
        }
        List<Integer> missingCh = new ArrayList<>();
        for (ChapterDO c : chapters) {
            if (c.getDraftOutline() == null || c.getDraftOutline().isBlank()) {
                missingCh.add(c.getChapterNo());
            }
        }
        if (!missingBeats.isEmpty() || !missingCh.isEmpty()) {
            throw new LlmJson.Bad("终局对账失败：未填拍 seq=" + missingBeats + " 无纲章=" + missingCh);
        }
    }

    static String nullToEmpty(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    static JsonNode readTreeSafe(String json) {
        try {
            return STATIC_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return STATIC_MAPPER.createObjectNode();
        }
    }
}
