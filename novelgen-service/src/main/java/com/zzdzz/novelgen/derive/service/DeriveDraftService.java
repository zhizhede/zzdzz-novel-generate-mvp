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
import com.zzdzz.novelgen.derive.mapper.WorldStateMapper;
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

    /**
     * 全局头三段式（2026-10-18 夜）：原先一次要模型吐 title+summary+cast(每人五键 persona)+brief+truth，
     * 输出 3000–5200 字符，run22 六次调用六次畸形（如 {@code "flade":"怕什么":"怕发现…"} 键值边界错乱）。
     * 现拆成 A 定名与人物表 / B 人设卡 / C 摘要与真相卡，单次输出 ≤1000 字符；每段落库即幂等（headStage）。
     */
    static final String HEAD_A_PROMPT = """
            你在为一部小说写"全局头 A：定名与人物表"（不是正文，不写章纲）。输入含：域格、类别与节奏、
            结局形状、世界观、主线、题材意图、章节计划（只作规模参考）。
            要求：
            1. 起作品名、一句话概括、主角名，并给出 cast 全书人物表（主角排首，含配角与关键路人）；
            2. 人名 2~3 字；主角名不得出现在禁用名单里，不得用"沈X"式单姓+单字套路名；
            3. role=身份一句话（≤20字），gender=男或女；
            4. 此表是全书唯一合法人名来源，后续步骤不得再新增任何名字（含路人）；
            5. 若输入带『题材意图』：人名/称谓/器物/礼俗按该体系取名，禁止混入异文化元素。
            输出 JSON 对象：
            {"title":"作品名","summary":"一句话概括","protagonist":"主角名",
             "cast":[{"name":"…","role":"…","gender":"…"}]}
            只输出 JSON 对象，无其他文字。
            """;

    static final String HEAD_B_PROMPT = """
            你在为一部小说写"全局头 B：人物人设卡"（不是正文）。输入含：已定人名表（name/role/gender）、
            题材意图、域格、类别与节奏。
            要求：
            1. 给名单内每一个人各出一张五人设卡：禁止新增人名、禁止改名、禁止漏人；
            2. 五键必填且互不重复：temperament性格底色、conduct行为准则（什么绝不会做/什么一定做）、
            speech说话方式、desire核心欲望、flaw缺陷或恐惧；
            3. 每键 8~25 字，写具体可演的倾向，不写"善良勇敢"这类空话；
            4. 与『题材意图』已有的性格与描写红线一致，禁止矛盾。
            输出 JSON 对象：
            {"persona":[{"name":"名单内人名","temperament":"…","conduct":"…","speech":"…",
                         "desire":"…","flaw":"…"}]}
            只输出 JSON 对象，无其他文字。
            """;

    static final String HEAD_C_PROMPT = """
            你在为一部小说写"全局头 C：叙述摘要与真相卡"（不是正文，不写章纲）。输入含：已定书名/
            主角/cast 人名表、域格、类别与节奏、结局形状、主线、章节计划（章号与预算）。
            要求：
            1. brief 拆分：{"premise":"人物设定/世界观/氛围/描写红线（全程可用，≤300字）",
            "ending":"结局与真相相关段落（无结局信息给空串）"}——premise 不得含结局答案，
            ending 集中放 twist/真凶/动机/结局走向；
            1b. 若输入带『目标文风』：premise 里的氛围与描写红线必须服从该文风——不许把腔调写成
                冷硬文书/卷宗/文言体，除非目标文风本身就是那个调子；premise 只约束"写什么可信"，
                不负责指定"用什么语体写"；
            2. 类别含「推理」或「悬疑」时必须额外输出真相卡 truth（其余体裁省略 truth 键）：
            {"reveal_chapter":揭示章, "clues":[{"clue":"可观测现象","planted_chapter":章号}],
            "timeline_anchors":[{"fact":"纯数字或时刻事实","chapter":章号}],
            "solution":{"culprit":"凶手（必须是 cast 名单内人名）","motive":"…","method":"…"}}
            —— reveal_chapter 不得早于最晚 planted_chapter；clues 至少 3 条；clue 只写可观测现象，
            禁止写成解释，禁止出现 culprit 姓名与 solution 内容；anchors 不得含因果结论；
            3. 所有章号必须是输入章节计划里出现过的章号。
            输出 JSON 对象：
            {"brief":{"premise":"…","ending":"…"},
             "truth":{"reveal_chapter":12,"clues":[{"clue":"…","planted_chapter":3}],
                      "timeline_anchors":[{"fact":"…","chapter":5}],
                      "solution":{"culprit":"…","motive":"…","method":"…"}}}
            只输出 JSON 对象，无其他文字。
            """;

    static final String BATCH_PROMPT = """
            你在为一部小说生成"具体稿·分批章纲"（不是正文）。输入含：全局头（protagonist/cast 人名表
            /brief/truth）、域格、类别与节奏、世界观、主线、前批章纲概览、已发生不可逆状态、
            本窗章节计划与本窗拍清单。
            要求：
            1. 每个本窗拍实例填实成具体情节点（1~2 句，写 filled）；确实用不上的拍放进 skipped 数组；
            2. 本窗每章给 draft_outline（本章具体大纲 2~3 句）与 title 定稿（≤12字，可沿用或改）；
            3. 人名、性别、称谓严格沿用全局头 cast 表，禁止新造任何新名字（含路人）——
               需要新配角时改用表内人名或把该拍放进 skipped；
            4. 写纲无知之幕：章号 < reveal_chapter 的章（若输入带 truth），draft_outline 与 title
               只写在场角色认知内的情节走向，禁写 solution/真凶身份/解法/视角反转；
            5. 与前批章纲衔接：不推翻前批已定事实（人物关系、已发生事件、关键物件）；
            5b. 已发生不可逆状态是硬约束：其中记为死亡/离场/被囚/失去身份的人，本窗不得给他安排
               现场戏份（出场说话、行动、被当面拜访）；回忆、梦境、遗体、遗物、他人转述、信件引用
               不受此限，但必须在 draft_outline 里写明是哪种形式；
            5c. 每章必须给 time（本章时间跨度，如"当晚""三日后"）与 state_changes
               （本章造成、后续不可回退的变更；没有就给空数组）；
            6. 若输入带『题材意图』：走向与红线以它为准，拍清单只作结构骨架；
            7. 只输出本窗章纲结构，不写正文，不提素材来源与编号（对外只出现 seq）。
            输出 JSON 对象：
            {"chapters":[{"no":1,"title":"…","draft_outline":"…","time":"当晚",
                          "state_changes":[{"who":"布兰","what":"死亡"},
                                           {"who":"薇拉","what":"得知","detail":"布兰与卧房案有关"}],
                          "beats":[{"seq":1,"filled":"…"}]}],
             "skipped":[…]}
            state_changes.what 只允许：死亡 / 离场 / 被囚 / 失去身份 / 关系改变 / 物件易主 / 得知
            （得知须用 detail 写知道了什么）。who 必须是 cast 名单内人名。
            只输出 JSON 对象，无其他文字。
            """;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final AssetWorldMapper worldMapper;
    private final AssetBeatMapper beatMapper;
    private final WorkFingerprintMapper fingerprintMapper;
    private final WorldStateMapper worldStateMapper;
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
        if (!("PLANNING".equals(run.getStatus()) || "REWORK".equals(run.getStatus()))) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥b 具体稿需要 run 处于 PLANNING/REWORK（当前 " + run.getStatus()
                            + "）——先让 ⑥a 章节计划完成");
        }
        if (run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该 run 还没有成品书骨架（novel_id 为空＝⑥a 章节计划尚未完成）——"
                            + "先点「⑥a 生成章节计划」，成功后再做⑥b具体稿");
        }
        long novelId = run.getNovelId();

        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByAsc(BeatInstanceDO::getSeq));
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        List<Integer> chapterNos = chapters.stream().map(ChapterDO::getChapterNo).toList();
        if (chapterNos.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥b 需要先完成 ⑥a 章节计划（这本书还没有任何章行，真相卡也没章号可对）"
                            + "——先把⑥a 跑到「章节计划完成」再点⑥b");
        }
        List<List<Integer>> wins = windows(chapterNos, WINDOW_CHAPTERS);

        if ("REWORK".equals(run.getStatus())) {
            archiveRejectedDrafts(run, novelId, chapters);
            chapterMapper.clearForRewrite(novelId);
            chapterMapper.clearDraftOutlines(novelId);
            novelMapper.clearDraftAssets(novelId);
            if (worldStateMapper != null) {
                worldStateMapper.deleteByNovel(novelId);
            }
            for (BeatInstanceDO b : beats) {
                b.setFilledText(null);
                b.setStatus("PLANNED");
                beatInstanceMapper.updateById(b);
            }
            runMapper.updateStatus(runId, "PLANNING", null);
            runMapper.updateStepStatus(runId,
                    DeriveGateService.gateReset(clearDraftState(run.getStepStatus())));
            log.info("⑥b REWORK 回炉清稿：runId={} novelId={} 窗数={}（GATE 作废，客户端续调从头跑）",
                    runId, novelId, wins.size());
            return new DraftVO(runId, novelId, "", "", 0, 0, 0, "RESET", 0, wins.size(), false);
        }

        NovelDO novel = novelMapper.selectById(novelId);
        boolean reasoning = reasoningGenre(run.getParamTags());
        String gateDirective = DeriveGateService.gateDirective(run.getStepStatus());

        int stage = headStage(novel, reasoning);
        if (stage < 3) {
            return headStep(run, novel, stage, chapters, chapterNos, reasoning, gateDirective);
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
            Set<String> castNames = new LinkedHashSet<>();
            for (JsonNode c : assets.path("cast")) {
                castNames.add(c.path("name").asText(""));
            }
            BatchContext ctx = new BatchContext(new ArrayList<>(winSeqs), win, castNames);
            JsonNode priorChanges = readDraftState(run.getStepStatus());
            String user = buildBatchUser(run, chapters, win, winBeats, labelBySeq, worldLabel(run),
                    assets, gateDirective, priorChanges);
            beginProgress(runId, "窗 " + (i + 1) + "/" + wins.size()
                    + "（章 " + win.get(0) + "-" + win.get(win.size() - 1) + "）");
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_DRAFT, novelId, null, runId,
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
            runMapper.updateStepStatus(runId, appendDraftState(run.getStepStatus(), out.path("chapters")));
            log.info("⑥b-1 分批窗：runId={} novelId={} 窗{}/{} 章{}-{} 填实{} 跳过{} 状态变更{}",
                    runId, novelId, i + 1, wins.size(), win.get(0), win.get(win.size() - 1),
                    filledCount, skippedCount, countChanges(out.path("chapters")));
            return new DraftVO(runId, novelId, "", assets.path("protagonist").asText(""),
                    win.size(), filledCount, skippedCount, "BATCH", i + 1, wins.size(), false);
        }

        globalReconcile(beats, chapters);
        runMapper.updateStatus(runId, "DRAFTING", null);
        log.info("⑥b 全部窗完成：runId={} novelId={} 窗数={} → DRAFTING", runId, novelId, wins.size());
        return new DraftVO(runId, novelId, novel.getTitle(), assets.path("protagonist").asText(""),
                chapters.size(), 0, 0, "DONE", wins.size(), wins.size(), true);
    }

    /** 全局头人设卡五键（顺序即提示词顺序）。 */
    static final List<String> PERSONA_KEYS =
            List.of("temperament", "conduct", "speech", "desire", "flaw");

    /**
     * 全局头完成度：0=未定名 1=缺人设卡 2=缺摘要（推理体裁还缺真相卡）3=齐。
     * 每段独立落库，故按缺失段续跑，不会因某段失败而把已成的段落重做。
     */
    static int headStage(NovelDO novel, boolean reasoning) {
        JsonNode assets = readTreeSafe(novel.getStoryAssets());
        if (assets.path("protagonist").asText("").trim().isEmpty()) {
            return 0;
        }
        JsonNode cast = assets.path("cast");
        if (!cast.isArray() || cast.isEmpty()) {
            return 1;
        }
        for (JsonNode c : cast) {
            JsonNode persona = c.path("persona");
            if (!persona.isObject()) {
                return 1;
            }
            for (String k : PERSONA_KEYS) {
                if (persona.path(k).asText("").trim().isEmpty()) {
                    return 1;
                }
            }
        }
        if (assets.path("brief").path("premise").asText("").trim().isEmpty()) {
            return 2;
        }
        if (reasoning && assets.path("truth").path("solution").path("culprit").asText("").trim().isEmpty()) {
            return 2;
        }
        return 3;
    }

    /** 推进一步全局头：A 定名与人物表 → B 人设卡 → C 摘要与真相卡（单次输出小，畸形率低）。 */
    private DraftVO headStep(RunDO run, NovelDO novel, int stage, List<ChapterDO> chapters,
                             List<Integer> chapterNos, boolean reasoning, String gateDirective) {
        long runId = run.getId();
        long novelId = novel.getId();
        String worldLabel = worldLabel(run);
        if (stage == 0) {
            List<String> banned = bannedNames(run);
            HeadContext ctx = new HeadContext(chapterNos, banned, reasoning);
            String user = buildHeadUser(run, chapters, worldLabel, banned, gateDirective);
            beginProgress(runId, "全局头A 定名与人物表");
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_DRAFT, novelId, null, runId,
                            List.of(LlmPort.Message.system(HEAD_A_PROMPT), LlmPort.Message.user(user)), 0.4),
                    root -> parseHeadBasic(root, ctx), 3, (think, piece) -> touchProgress(runId, piece));
            novelMapper.updateMeta(novelId, out.path("title").asText(""), out.path("summary").asText(""));
            novelMapper.updateCast(novelId, out.path("protagonist").asText(""), out.path("cast").toString());
            log.info("⑥b-0a 定名：runId={} novelId={} 书名={} 主角={} cast={}",
                    runId, novelId, out.path("title").asText(""), out.path("protagonist").asText(""),
                    out.path("cast").size());
            return new DraftVO(runId, novelId, out.path("title").asText(""), out.path("protagonist").asText(""),
                    0, 0, 0, "HEAD", 1, 3, false);
        }
        JsonNode assets = readTreeSafe(novel.getStoryAssets());
        String protagonist = assets.path("protagonist").asText("");
        if (stage == 1) {
            String user = buildPersonaUser(run, assets, worldLabel, gateDirective);
            beginProgress(runId, "全局头B 人物人设卡");
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_DRAFT, novelId, null, runId,
                            List.of(LlmPort.Message.system(HEAD_B_PROMPT), LlmPort.Message.user(user)), 0.5),
                    root -> parsePersona(root, assets), 3, (think, piece) -> touchProgress(runId, piece));
            String merged = mergePersona(assets.path("cast"), out);
            novelMapper.updateCast(novelId, protagonist, merged);
            log.info("⑥b-0b 人设卡：runId={} novelId={} 人数={}", runId, novelId, out.size());
            return new DraftVO(runId, novelId, assets.path("title").asText(""), protagonist,
                    0, 0, 0, "HEAD", 2, 3, false);
        }
        HeadContext ctx = new HeadContext(chapterNos, List.of(), reasoning);
        String user = buildBriefUser(run, assets, chapters, worldLabel, gateDirective);
        beginProgress(runId, "全局头C 摘要与真相卡");
        JsonNode out = llmJson.ask(
                new LlmPort.ChatRequest(NODE_DRAFT, novelId, null, runId,
                        List.of(LlmPort.Message.system(HEAD_C_PROMPT), LlmPort.Message.user(user)), 0.3),
                root -> parseBriefTruth(root, ctx), 3, (think, piece) -> touchProgress(runId, piece));
        novelMapper.updateBrief(novelId, out.path("brief").toString());
        if (out.path("truth").isObject()) {
            novelMapper.updateTruth(novelId, out.path("truth").toString());
        }
        log.info("⑥b-0c 摘要与真相卡：runId={} novelId={} 有真相卡={}",
                runId, novelId, out.path("truth").isObject());
        return new DraftVO(runId, novelId, assets.path("title").asText(""), protagonist,
                0, 0, 0, "HEAD", 3, 3, false);
    }

    /** 人设卡回填进已有 cast（按 name 对齐，保持原顺序与字段）。 */
    static String mergePersona(JsonNode cast, JsonNode personas) {
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode p : personas) {
            byName.put(p.path("name").asText(""), p);
        }
        var merged = STATIC_MAPPER.createArrayNode();
        for (JsonNode c : cast) {
            ObjectNode copy = c.deepCopy();
            JsonNode p = byName.get(c.path("name").asText(""));
            ObjectNode persona = copy.putObject("persona");
            for (String k : PERSONA_KEYS) {
                persona.put(k, p.path(k).asText(""));
            }
            merged.add(copy);
        }
        return merged.toString();
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
                          String worldLabel, JsonNode assets, String gateDirective,
                          JsonNode priorChanges) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        if (priorChanges != null && priorChanges.isArray() && !priorChanges.isEmpty()) {
            root.set("已发生不可逆状态（硬约束：这些人不得再有现场戏份，回忆/遗体/转述除外）",
                    priorChanges);
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

    /** 头 B 输入：已定人名表 + 意图/域格（不带章节计划，避免模型顺手写章纲）。 */
    String buildPersonaUser(RunDO run, JsonNode assets, String worldLabel, String gateDirective) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        root.put("主角", assets.path("protagonist").asText(""));
        root.set("已定人名表", assets.path("cast"));
        return root.toString();
    }

    /** 头 C 输入：书名/主角/cast 人名 + 章节计划（真相卡的 reveal/planted 必须落在这些章号里）。 */
    String buildBriefUser(RunDO run, JsonNode assets, List<ChapterDO> chapters,
                          String worldLabel, String gateDirective) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        root.put("书名", assets.path("title").asText(""));
        root.put("主角", assets.path("protagonist").asText(""));
        if (run.getParamStyle() != null && !run.getParamStyle().isBlank()) {
            root.put("目标文风（premise 的氛围与描写红线必须服从它，不得另立腔调）", run.getParamStyle());
        }
        var names = root.putArray("cast 人名表");
        for (JsonNode c : assets.path("cast")) {
            names.add(c.path("name").asText(""));
        }
        var chArr = root.putArray("章节计划");
        for (ChapterDO c : chapters) {
            var n = chArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("budget", c.getBudget());
        }
        return root.toString();
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

    record BatchContext(List<Integer> windowSeqs, List<Integer> windowChapters, Set<String> castNames) {
        BatchContext(List<Integer> windowSeqs, List<Integer> windowChapters) {
            this(windowSeqs, windowChapters, Set.of());
        }
    }

    /** state_changes.what 的封闭集合（不可逆变更，才需要跨窗传递）。 */
    static final Set<String> STATE_WHAT = Set.of(
            "死亡", "离场", "被囚", "失去身份", "关系改变", "物件易主", "得知");

    /** 头 A：定名与人物表（人名表是全书唯一合法名源，人设卡留到 B 段）。 */
    static JsonNode parseHeadBasic(JsonNode root, HeadContext ctx) {
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
            if (c.path("role").asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("cast 成员「" + nm + "」缺 role（身份一句话）");
            }
        }
        if (!castHasProtagonist) {
            throw new LlmJson.Bad("cast 必须包含 protagonist「" + protagonist + "」");
        }
        return root;
    }

    /** 头 B：人设卡（只准名单内的人，五键全填，不得漏人）。 */
    static JsonNode parsePersona(JsonNode root, JsonNode assets) {
        JsonNode arr = root.path("persona");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new LlmJson.Bad("必须输出 persona 数组");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode c : assets.path("cast")) {
            allowed.add(c.path("name").asText(""));
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode p : arr) {
            String nm = p.path("name").asText("").trim();
            if (!allowed.contains(nm)) {
                throw new LlmJson.Bad("persona 出现名单外人名「" + nm + "」——只许用 cast 表内人名");
            }
            if (!seen.add(nm)) {
                throw new LlmJson.Bad("persona 人名重复: " + nm);
            }
            for (String k : PERSONA_KEYS) {
                if (p.path(k).asText("").trim().isEmpty()) {
                    throw new LlmJson.Bad("「" + nm + "」缺 " + k);
                }
            }
        }
        List<String> missing = allowed.stream().filter(n -> !seen.contains(n)).toList();
        if (!missing.isEmpty()) {
            throw new LlmJson.Bad("persona 漏了名单内的人：" + missing);
        }
        return arr;
    }

    /** 头 C：brief 拆分 + 推理体裁真相卡。 */
    static JsonNode parseBriefTruth(JsonNode root, HeadContext ctx) {
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
            if (c.path("time").asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("第" + no + "章缺 time（本章时间跨度，如\"当晚\"\"三日后\"）");
            }
            JsonNode changes = c.path("state_changes");
            if (!changes.isArray()) {
                throw new LlmJson.Bad("第" + no + "章 state_changes 必须是数组（没有就写空数组）");
            }
            for (JsonNode ch : changes) {
                String who = ch.path("who").asText("").trim();
                String what = ch.path("what").asText("").trim();
                if (who.isEmpty() || what.isEmpty()) {
                    throw new LlmJson.Bad("第" + no + "章 state_changes 有条目缺 who/what");
                }
                if (!ctx.castNames().isEmpty() && !ctx.castNames().contains(who)) {
                    throw new LlmJson.Bad("第" + no + "章 state_changes 的 who「" + who
                            + "」不在 cast 名单内");
                }
                if (!STATE_WHAT.contains(what)) {
                    throw new LlmJson.Bad("第" + no + "章 state_changes.what「" + what
                            + "」不在允许集合 " + STATE_WHAT);
                }
                if ("得知".equals(what) && ch.path("detail").asText("").trim().isEmpty()) {
                    throw new LlmJson.Bad("第" + no + "章 state_changes「得知」必须用 detail 写知道了什么");
                }
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

    /** 统计本窗产出的状态变更条数（日志用）。 */
    static int countChanges(JsonNode chapterArr) {
        int n = 0;
        for (JsonNode c : chapterArr) {
            n += c.path("state_changes").size();
        }
        return n;
    }

    /** ⑥b 已发生不可逆状态（跨窗硬约束）：读 run.step_status.steps.DRAFT.changes。 */
    static JsonNode readDraftState(String stepStatus) {
        JsonNode arr = readTreeSafe(stepStatus).path("steps").path("DRAFT").path("changes");
        return arr.isArray() ? arr : STATIC_MAPPER.createArrayNode();
    }

    /** 把本窗的 time/state_changes 累加进 step_status（保留 GATE/PLAN 等其它段）。 */
    static String appendDraftState(String stepStatus, JsonNode chapterArr) {
        JsonNode parsed = readTreeSafe(stepStatus);
        ObjectNode root = parsed.isObject() ? (ObjectNode) parsed.deepCopy() : STATIC_MAPPER.createObjectNode();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        ObjectNode draft = steps.path("DRAFT").isObject()
                ? (ObjectNode) steps.path("DRAFT") : steps.putObject("DRAFT");
        com.fasterxml.jackson.databind.node.ArrayNode arr = draft.path("changes").isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) draft.path("changes")
                : draft.putArray("changes");
        for (JsonNode c : chapterArr) {
            int no = c.path("no").asInt(0);
            String time = c.path("time").asText("");
            for (JsonNode ch : c.path("state_changes")) {
                ObjectNode n = arr.addObject();
                n.put("no", no);
                n.put("time", time);
                n.put("who", ch.path("who").asText(""));
                n.put("what", ch.path("what").asText(""));
                if (!ch.path("detail").asText("").isBlank()) {
                    n.put("detail", ch.path("detail").asText(""));
                }
            }
        }
        return root.toString();
    }

    /** 清空 ⑥b 已发生状态（REWORK 回炉时整本重做，旧事实作废）。 */
    static String clearDraftState(String stepStatus) {
        JsonNode parsed = readTreeSafe(stepStatus);
        ObjectNode root = parsed.isObject() ? (ObjectNode) parsed.deepCopy() : STATIC_MAPPER.createObjectNode();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        ObjectNode draft = steps.putObject("DRAFT");
        draft.putArray("changes");
        return root.toString();
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
    /** 全局头是否齐（三段全成；REWORK 清头产物后自然回到 0 段）。 */
    static boolean headDone(NovelDO novel) {
        return headStage(novel, false) >= 3;
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
