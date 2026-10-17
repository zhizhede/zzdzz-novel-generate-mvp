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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⑥b 生成具体稿（v3.2 §⑥b）。
 *
 * <p>输入：域格快照 / param_tags·pace / 抽中世界观标签层 / welded 主线 / 拍清单（含标签层与章归属）
 * / 章节计划；输出：逐拍填实 filled_text + 每章具体大纲与标题定稿 + 成品书题名/摘要/主角名。
 * 人名规避：work_fingerprint 已用主角名进禁用名单（提示词注入，⑧再登记新名）。
 * 落库（无事务，LLM 在前）：beat_instance.filled_text、跳过拍 status=SKIPPED、
 * chapters.draft_outline/title、novels.title/summary、run.status=DRAFTING。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveDraftService {

    public static final String NODE_DRAFT = "derive_draft";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    static final String DRAFT_PROMPT = """
            你在为一部小说生成"具体稿"（不是正文）。输入含：域格、类别与目标节奏、世界观条目、
            标签层主线、拍清单（seq/拍型/标签层文案/归属章）、章节计划（章号/章题/预算）。
            要求：
            1. 起作品名、主角与配角人名、地名；结局形状落地；事件必须在域格内成立；
            2. 每个拍实例填实成具体情节点（1~2 句，写 filled）；确实用不上的拍放进 skipped 数组；
            3. 主角名不得出现在禁用名单里，不得用"沈X"式单姓+单字套路名；
            4. 每章给 draft_outline（本章具体大纲 2~3 句）与 title 定稿（≤12字，可沿用或改）；
            5. 输出 cast 全书人物表（主角排首，含配角与关键路人）：name=定名人名（2~3字）、
               role=身份一句话、gender=性别、persona={temperament性格底色, conduct行为准则
               （什么绝不会做/什么一定做）, speech说话方式, desire核心欲望, flaw缺陷或恐惧}
               ——persona 优先从『题材意图』已有抽取、禁止与意图矛盾；正文各章必须严格沿用
               此表人名/性别/persona，禁止同人异名、行为与人设相悖；
            6. 类别含「推理」或「悬疑」时必须额外输出『真相卡』truth=
               {reveal_chapter真相揭示章, clues:[{clue现象级线索, planted_chapter}],
                timeline_anchors:[{fact纯数字或时刻事实, chapter}],
                solution:{culprit,motive,method}}——
               reveal_chapter 不得早于 max(planted_chapter)；clue 只写可观测现象，
               禁止写成解释或出现 solution 核心内容；anchors 不得含因果结论；
               结局章（reveal_chapter）完成 culprit/motive/method 三要素还原；非推理体裁省略 truth；
               写章纲同守无知之幕（修1）：章号 < reveal_chapter 的章，draft_outline 与 title
               只写在场角色认知内的情节走向，禁写 solution/真凶身份/解法/视角反转——
               心理医生报告类还原视角叙事只许出现在章号 ≥ reveal_chapter 的章纲里；
            7. 所有体裁必须输出『brief』拆分（把题材意图拆成两段）：
               {"premise":"人物设定/世界观/氛围/描写红线（全程可用，≤300字）",
                "ending":"结局与真相相关段落（无结局信息给空串）"}——premise 不得含结局答案，
               ending 集中放 twist/真凶/动机/结局走向；与意图矛盾的拆分视为无效；
            8. 若输入带『题材意图』：人物设定、剧情走向、结局与描写红线以它为准——拍清单只作结构
               骨架（每拍的叙事功能必须兑现），血肉（谁/在哪/发生什么）按意图重排；与意图明显冲突的
               拍填成意图内的等价情节点，不得照抄拍文案的表层事物；
            9. 意图若指明世界观前提（如「剑与魔法」「一切以存在魔法为前提」），该前提**决定文化取向**：
               人名、称谓、器物、服饰、建筑、礼俗必须符合该世界观的常识体系（西式剑与魔法⇒西式人名
               与译名、骑士/领主/修道院式称谓、相应器物），全程禁止混入异文化元素（如中式发饰、中式
               乐器、中式家族继承话语）。cast 的 name/role/persona 一并按此取名；
            10. 只输出具体稿结构，不写正文，不提素材来源与编号（对外只出现 seq）。
            输出 JSON 对象：
            {"title":"作品名","summary":"一句话概括","protagonist":"主角名",
             "cast":[{"name":"…","role":"…","gender":"…",
                      "persona":{"temperament":"…","conduct":"…","speech":"…","desire":"…","flaw":"…"}}],
             "brief":{"premise":"…","ending":"…"},
             "truth":{"reveal_chapter":12,
                      "clues":[{"clue":"…","planted_chapter":3}],
                      "timeline_anchors":[{"fact":"…","chapter":5}],
                      "solution":{"culprit":"…","motive":"…","method":"…"}},
             "chapters":[{"no":1,"title":"章题","draft_outline":"…","beats":[{"seq":1,"filled":"…"}]}],
             "skipped":[…]}
            filled 覆盖的 seq ∪ skipped 必须恰好等于拍清单全部 seq 且不重不漏；
            chapters 的章号集合与章数必须与章节计划一致；cast 至少 3 人且含 protagonist、
            每人必含 persona 五键；brief.premise 必填。只输出 JSON。
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

    /** ⑥b：LLM 生成具体稿 → 回填五张表 + run→DRAFTING。 */
    public DraftVO draft(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        // REWORK=⑧打回后重跑（qc_round+1≤2）；PLANNING=首轮
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

        // 世界观 + 原生拍标签层（ARC_SEG 按 arc_id+seg_no 回查原生链）
        String worldLabel = "";
        if (run.getPickedWorldId() != null) {
            AssetWorldDO w = worldMapper.selectById(run.getPickedWorldId());
            if (w != null) {
                worldLabel = w.getLabelText() == null ? w.getExtractedText() : w.getLabelText();
            }
        }
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

        // 主角名禁用名单（⑧登记的历史名）——防复刻开关开才注入；关=空名单不拦
        List<String> banned = Boolean.TRUE.equals(run.getAntiRepro())
                ? fingerprintMapper.selectList(new LambdaQueryWrapper<WorkFingerprintDO>()
                                .eq(WorkFingerprintDO::getKind, "PROTAGONIST_NAME"))
                        .stream().map(WorkFingerprintDO::getValue).filter(v -> v != null && !v.isBlank()).toList()
                : List.of();

        // 章节计划上下文
        Map<Integer, List<BeatInstanceDO>> byChapter = new LinkedHashMap<>();
        for (BeatInstanceDO b : beats) {
            byChapter.computeIfAbsent(b.getChapterNo() == null ? 0 : b.getChapterNo(), k -> new ArrayList<>()).add(b);
        }

        DraftContext ctx = new DraftContext(beats.stream().map(BeatInstanceDO::getSeq).toList(),
                chapters.stream().map(ChapterDO::getChapterNo).toList(), banned,
                reasoningGenre(run.getParamTags()));
        String user = buildUser(run, chapters, beats, labelBySeq, worldLabel, banned);

        // 流式（2026-10-15）：44 章大 draft 非流式输出一次 >720s 被网关 RST_STREAM 掐断（run15 连挂 3 次）；
        // 流式帧持续发送不触连接上限。onDelta 给 no-op——只借传输形态，不改记账与解析。
        JsonNode out = llmJson.ask(
                new LlmPort.ChatRequest(NODE_DRAFT, runId, novelId,
                        List.of(LlmPort.Message.system(DRAFT_PROMPT), LlmPort.Message.user(user)),
                        0.4),
                root -> parseDraft(root, ctx), 3, (think, piece) -> { });

        // ---- 落库 ----
        Set<Integer> skipped = new HashSet<>();
        out.path("skipped").forEach(n -> skipped.add(n.asInt()));
        Map<Integer, String> filled = new HashMap<>();
        int filledCount = 0;
        for (JsonNode c : out.path("chapters")) {
            for (JsonNode b : c.path("beats")) {
                filled.put(b.path("seq").asInt(), b.path("filled").asText());
                filledCount++;
            }
        }
        for (BeatInstanceDO b : beats) {
            if (skipped.contains(b.getSeq())) {
                b.setStatus("SKIPPED");
            } else {
                b.setFilledText(filled.get(b.getSeq()));
                b.setStatus("PLANNED"); // ⑦写正文时转 WRITTEN
            }
            beatInstanceMapper.updateById(b);
        }
        boolean rework = "REWORK".equals(run.getStatus());
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
        if (rework) {
            // ⑧打回后重跑：先把本轮成稿归档（V113 draft_history；run11 清稿丢稿教训），再清旧正文让⑦重写
            archiveRejectedDrafts(run, novelId, chapters);
            chapterMapper.clearForRewrite(novelId);
        }

        NovelDO novel = novelMapper.selectById(novelId);
        String finalTitle = out.path("title").asText(novel.getTitle());
        String finalSummary = out.path("summary").asText("");
        novelMapper.updateMeta(novelId, finalTitle, finalSummary);
        // 主角名 + 全书 cast 表进 story_assets（jsonb 留缝；⑦ 每章注入防称谓漂移，⑧ 读主角名做指纹）
        novelMapper.updateCast(novelId, out.path("protagonist").asText(""),
                out.path("cast").toString());
        // v3.6：brief 拆分（全体裁必落）；真相卡分段契约（推理体裁），v 升 3
        novelMapper.updateBrief(novelId, out.path("brief").toString());
        if (out.path("truth").isObject()) {
            novelMapper.updateTruth(novelId, out.path("truth").toString());
        }
        runMapper.updateStatus(runId, "DRAFTING", null);

        String protagonist = out.path("protagonist").asText("");
        log.info("⑥b具体稿：runId={} novelId={} 书名={} 主角={} 填实{} 跳过{}",
                runId, novelId, finalTitle, protagonist, filledCount, skipped.size());
        return new DraftVO(runId, novelId, finalTitle, protagonist,
                chapters.size(), filledCount, skipped.size());
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

    String buildUser(RunDO run, List<ChapterDO> chapters, List<BeatInstanceDO> beats,
                     Map<Integer, String> labelBySeq, String worldLabel, List<String> banned) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
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
        root.put("主角名禁用名单", String.join("、", banned));
        var chArr = root.putArray("章节计划");
        for (ChapterDO c : chapters) {
            var n = chArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("budget", c.getBudget());
        }
        var bArr = root.putArray("拍清单");
        for (BeatInstanceDO b : beats) {
            var n = bArr.addObject();
            n.put("seq", b.getSeq());
            n.put("chapter", b.getChapterNo());
            n.put("beat_type", b.getBeatType());
            n.put("pacing", b.getPacing().toPlainString());
            n.put("label", labelBySeq.getOrDefault(b.getSeq(), ""));
        }
        return root.toString();
    }

    // ---- 解析校验 ----

    /** 推理体裁判定：tags 含 推理/悬疑 → truth 真相卡必出（v3.4）。 */
    static boolean reasoningGenre(String paramTags) {
        return paramTags != null && (paramTags.contains("推理") || paramTags.contains("悬疑"));
    }

    record DraftContext(List<Integer> allSeqs, List<Integer> chapterNos, List<String> banned,
                        boolean reasoning) {
    }

    static JsonNode parseDraft(JsonNode root, DraftContext ctx) {
        String title = root.path("title").asText("").trim();
        String summary = root.path("summary").asText("").trim();
        String protagonist = root.path("protagonist").asText("").trim();
        if (title.isEmpty() || summary.isEmpty() || protagonist.isEmpty()) {
            throw new LlmJson.Bad("title/summary/protagonist 均不能为空");
        }
        if (ctx.banned().contains(protagonist)) {
            throw new LlmJson.Bad("protagonist「" + protagonist + "」在禁用名单（历史成品已用），换一个");
        }
        // cast 全书人物表：≥3 人、含主角、人名不重复（⑦ 各章注入的称谓锚点）
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
            // persona 人设卡五键（v3.4：行为一致性的数据载体）
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
        JsonNode chArr = root.path("chapters");
        if (!chArr.isArray() || chArr.size() != ctx.chapterNos().size()) {
            throw new LlmJson.Bad("chapters 章数必须 = " + ctx.chapterNos().size());
        }
        Set<Integer> seenCh = new HashSet<>();
        Set<Integer> filled = new HashSet<>();
        for (JsonNode c : chArr) {
            int no = c.path("no").asInt(-1);
            if (!ctx.chapterNos().contains(no) || !seenCh.add(no)) {
                throw new LlmJson.Bad("chapters 章号非法或重复: " + no);
            }
            if (c.path("draft_outline").asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("第" + no + "章缺 draft_outline");
            }
            for (JsonNode b : c.path("beats")) {
                int seq = b.path("seq").asInt(0);
                if (!ctx.allSeqs().contains(seq) || !filled.add(seq)) {
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
            if (!ctx.allSeqs().contains(seq) || filled.contains(seq) || !skipped.add(seq)) {
                throw new LlmJson.Bad("skipped 含非法/重复 seq: " + seq);
            }
        }
        // 覆盖且不重不漏
        Set<Integer> union = new HashSet<>(filled);
        union.addAll(skipped);
        if (union.size() != ctx.allSeqs().size()) {
            throw new LlmJson.Bad("filled ∪ skipped 必须恰好覆盖全部 " + ctx.allSeqs().size() + " 拍（当前 " + union.size() + "）");
        }
        // brief 拆分（v3.6 全体裁必出：premise 全程可用、ending 仅揭示章起注入）
        JsonNode brief = root.path("brief");
        if (!brief.isObject() || brief.path("premise").asText("").trim().isEmpty()) {
            throw new LlmJson.Bad("必须输出 brief.premise（人物/世界观/氛围/红线摘要，≤300字）");
        }
        // 真相卡（推理体裁必出，v3.6 分段：clues=现象、solution=揭示章前不外发）
        if (ctx.reasoning()) {
            JsonNode truth = root.path("truth");
            if (!truth.isObject()) {
                throw new LlmJson.Bad("推理体裁（tags 含 推理/悬疑）必须输出 truth 真相卡");
            }
            int reveal = truth.path("reveal_chapter").asInt(-1);
            if (!ctx.chapterNos().contains(reveal)) {
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
                if (clueText.isEmpty() || !ctx.chapterNos().contains(pc)) {
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
                        || !ctx.chapterNos().contains(a.path("chapter").asInt(-1))) {
                    throw new LlmJson.Bad("truth.timeline_anchors 有缺 fact 或 chapter 越界");
                }
            }
        }
        return root;
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
