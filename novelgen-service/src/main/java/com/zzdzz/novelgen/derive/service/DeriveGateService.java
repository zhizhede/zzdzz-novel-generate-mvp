package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import com.zzdzz.novelgen.model.vo.derive.GateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * P6 立项闸（⑥b 全窗完成后、⑦ 开写前的 LLM 立项评审，六判据）。
 *
 * <p>入口 {@code POST /runs/{id}/gate}：GO=放行（step GATE=PASS，⑦ 可写）；
 * REWORK=带减法指令回 ⑥b 整本重做（run→REWORK，复用回炉链，prevRound&lt;2 才允许）；
 * REJECT=熔断（run→FAILED）。评审记录落 run.step_status.steps.GATE（L2 层，无 DDL）；
 * ⑦ writeNext 要求 GATE=PASS 才开写——烂大纲写不出正文。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveGateService {

    public static final String NODE_GATE = "derive_gate";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    static final String GATE_PROMPT = """
            你是小说大纲立项评审员。输入含：类别/产品形态/字数带/结局形状/主线、人物表（cast 含 persona）、
            真相卡（若有）、brief、全书章纲（每章 no/title/outline）、拍清单（seq/拍型/归属章/状态/filled 情节点）、
            母题黑名单（跨稿复读母题，命中要换写）。
            只依据给出材料评审，六判据逐条给 pass 与 note（note ≤40字）：
            1. 规则单轨：真相卡存在时，诡计/手法层只允许一套可理性化规则——超自然只能做氛围，
               不得进入解释通道；两套规则并存判 fail；
            2. 体量预算：主线 ≤1 条；支线（独立人物线/赌局/复仇/私奔类）≤1（中篇）或 ≤2（长篇）；
               正文死亡/命案 ≤4 起；机关+死亡+揭穿类高密度事件中篇 ≤3、长篇 ≤5；
               拍总量与字数带折算明显超载（事件拍占绝对多数且支线塞不进章容量）判 fail；
            3. 动机链：主要人物每次立场/阵营变化在章纲里能找到 ≥1 个铺垫章；
               连续翻转无中间台阶、反派动机一句带过判 fail；
            4. 闭合：真相卡存在时，结局章（reveal_chapter 及后 1~2 章）章纲必须写明
               culprit/motive/method 三要素的兑现动作；先写死案情、主题余韵殿后合法，
               案情悬空/替身未决/真凶未点名判 fail；
            5. 职能锁定：主视角/侦探的行为与 persona 方法论一致（如"只记不断"者不得
               亲自潜入/肉搏/夺人质）；侦探与被卷入者职能重叠到读者会认错主视角判 fail；
            6. 母题查重：章纲与母题黑名单逐条比对，命中 ≥3 处（或核心转折母题命中 ≥2 处）
               则 fail——要求 directive 给出替换方向（换动机载体/换收尾动作/换嫁祸手段）。
            verdict 口径：六判据全 pass → GO；仅 2/3/5/6 类可修复问题（给得出减法指令）→ REWORK；
            1 规则双轨无法自洽、或问题多到重构 → REJECT。
            输出 JSON 对象：
            {"verdict":"GO|REWORK|REJECT","issues":["逐条问题，无则空数组"],
             "directive":"REWORK 时给可执行减法指令（砍什么/留什么/改什么），无则空串"}
            只输出 JSON 对象，无其他文字。
            """;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final WordlistMapper wordlistMapper;
    private final LlmJson llmJson;

    /** ⑥b→⑦ 之间跑一次立项评审。 */
    public GateVO gate(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"DRAFTING".equals(run.getStatus()) || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "run 应处于 DRAFTING（⑥b 已完成），当前 " + run.getStatus());
        }
        long novelId = run.getNovelId();
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        long noOutline = chapters.stream()
                .filter(c -> c.getDraftOutline() == null || c.getDraftOutline().isBlank()).count();
        if (chapters.isEmpty() || noOutline > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥b 未完成：无纲章 " + noOutline + "/" + chapters.size() + "，先循环 POST /draft 至 allDone");
        }
        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByAsc(BeatInstanceDO::getSeq));
        NovelDO novel = novelMapper.selectById(novelId);

        int prevRound = gateRound(run.getStepStatus());
        int round = prevRound + 1;

        List<String> motifs = wordlistMapper.selectList(new LambdaQueryWrapper<WordlistDO>()
                        .eq(WordlistDO::getKind, "BLACKLIST")
                        .eq(WordlistDO::getGrp, "MOTIF"))
                .stream().map(WordlistDO::getWord).filter(w -> w != null && !w.isBlank()).toList();
        String user = buildUser(run, novel, chapters, beats, motifs);

        JsonNode out;
        DeriveActivity.begin(runId, "P6 立项闸评审（六判据）");
        try {
            out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_GATE, runId, novelId,
                            List.of(LlmPort.Message.system(GATE_PROMPT), LlmPort.Message.user(user)), 0.2),
                    DeriveGateService::parseGate, 3, (think, piece) -> DeriveActivity.touch(runId, piece));
        } finally {
            DeriveActivity.end(runId);
        }

        String verdict = out.path("verdict").asText("");
        List<String> issues = new ArrayList<>();
        out.path("issues").forEach(n -> issues.add(n.asText("")));
        String directive = out.path("directive").asText("");
        String action = gateAction(verdict, prevRound);

        run.setStepStatus(withGate(run.getStepStatus(), action, round, issues, directive));
        runMapper.updateStepStatus(runId, run.getStepStatus());
        if ("REWORK".equals(action)) {
            runMapper.updateStatus(runId, "REWORK", null);
        } else if ("FAILED".equals(action)) {
            runMapper.updateStatus(runId, "FAILED", null);
        }
        log.info("P6立项闸：runId={} round={} verdict={} action={} issues={} directive={}",
                runId, round, verdict, action, issues.size(),
                directive.length() > 80 ? directive.substring(0, 80) + "…" : directive);
        return new GateVO(runId, novelId, verdict, action, round, issues, directive);
    }

    // ---- 输入构造 ----

    String buildUser(RunDO run, NovelDO novel, List<ChapterDO> chapters,
                     List<BeatInstanceDO> beats, List<String> motifs) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.set("类别", DeriveDraftService.readTreeSafe(run.getParamTags()));
        root.put("产品形态", run.getParamForm());
        root.put("字数带", run.getParamWordsMin() + "~" + run.getParamWordsMax());
        root.put("结局形状", run.getParamOutcome());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        JsonNode assets = DeriveDraftService.readTreeSafe(novel.getStoryAssets());
        root.set("人物表", assets.path("cast"));
        root.put("protagonist", assets.path("protagonist").asText(""));
        if (assets.path("truth").isObject()) {
            root.set("真相卡", assets.path("truth"));
        }
        root.set("brief", assets.path("brief"));
        var chArr = root.putArray("章纲");
        for (ChapterDO c : chapters) {
            var n = chArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("outline", c.getDraftOutline() == null ? "" : c.getDraftOutline());
        }
        var bArr = root.putArray("拍清单");
        for (BeatInstanceDO b : beats) {
            var n = bArr.addObject();
            n.put("seq", b.getSeq());
            n.put("beat_type", b.getBeatType());
            n.put("chapter", b.getChapterNo());
            n.put("status", b.getStatus());
            n.put("filled", b.getFilledText() == null ? "" : b.getFilledText());
        }
        ArrayNode mArr = root.putArray("母题黑名单");
        motifs.forEach(mArr::add);
        return root.toString();
    }

    // ---- 解析与判定（纯函数，单测覆盖） ----

    static JsonNode parseGate(JsonNode root) {
        String verdict = root.path("verdict").asText("").trim();
        if (!"GO".equals(verdict) && !"REWORK".equals(verdict) && !"REJECT".equals(verdict)) {
            throw new LlmJson.Bad("verdict 必须是 GO|REWORK|REJECT，当前「" + verdict + "」");
        }
        if (!root.path("issues").isArray()) {
            throw new LlmJson.Bad("issues 必须是数组（可为空）");
        }
        if (!root.has("directive")) {
            throw new LlmJson.Bad("缺 directive（可为空串，但键必须在）");
        }
        return root;
    }

    /** LLM verdict + 既有轮次 → 落地动作：GO→PASS；REWORK 限 prevRound<2 否则熔断；REJECT→FAILED。 */
    static String gateAction(String verdict, int prevRound) {
        return switch (verdict) {
            case "GO" -> "PASS";
            case "REJECT" -> "FAILED";
            case "REWORK" -> prevRound < 2 ? "REWORK" : "FAILED";
            default -> throw new BizException(ErrorCode.PARAM_ERROR, "未知 verdict: " + verdict);
        };
    }

    // ---- step_status.steps.GATE 读写（合并既有键，L2 层） ----

    static int gateRound(String stepStatus) {
        return readGate(stepStatus).path("round").asInt(0);
    }

    static boolean gatePassed(String stepStatus) {
        return "PASS".equals(readGate(stepStatus).path("status").asText(""));
    }

    static String withGate(String stepStatus, String status, int round, List<String> issues, String directive) {
        ObjectNode root = stepsRoot(stepStatus);
        ObjectNode gate = root.with("steps").putObject("GATE");
        gate.put("status", status);
        gate.put("round", round);
        gate.put("directive", directive == null ? "" : directive);
        ArrayNode arr = gate.putArray("issues");
        issues.forEach(arr::add);
        return root.toString();
    }

    /** ⑥b 回炉清稿时重置 GATE 状态——**保留 directive/issues**（重做必须按减法指令执行，2026-10-18 修）。 */
    static String gateReset(String stepStatus) {
        ObjectNode root = stepsRoot(stepStatus);
        ObjectNode gate = root.with("steps").withObject("GATE");
        int round = gate.path("round").asInt(0);
        String directive = gate.path("directive").asText("");
        List<String> issues = new ArrayList<>();
        gate.path("issues").forEach(n -> issues.add(n.asText("")));
        return withGate(root.toString(), "PENDING", round, issues, directive);
    }

    /** 回炉重做时要喂回模型的减法指令（无则空串）。 */
    static String gateDirective(String stepStatus) {
        return readGate(stepStatus).path("directive").asText("");
    }

    private static JsonNode readGate(String stepStatus) {
        return stepsRoot(stepStatus).path("steps").path("GATE");
    }

    private static ObjectNode stepsRoot(String stepStatus) {
        JsonNode root;
        try {
            root = STATIC_MAPPER.readTree(stepStatus == null || stepStatus.isBlank() ? "{}" : stepStatus);
        } catch (Exception e) {
            root = STATIC_MAPPER.createObjectNode();
        }
        if (!root.isObject()) {
            root = STATIC_MAPPER.createObjectNode();
        }
        ObjectNode obj = (ObjectNode) root;
        if (!obj.path("steps").isObject()) {
            obj.putObject("steps");
        }
        if (!obj.has("v")) {
            obj.put("v", 1);
        }
        return obj;
    }
}
