package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.QcReportMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.WriteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑦ 分章生成正文（v3.2 §⑦ + harness 化，2026-10-13）。
 *
 * <p><b>harness 三件套</b>（把 agent 在环时天然具备的状态工程化）：
 * <ul>
 *   <li>状态包注入：全书 cast 表（⑥b 落 story_assets）+ 前章正文尾段 + 全书章纲概览
 *       + ⑧ 上轮 issues——每次调用等价于"看过前文再写"，孤立重写不再孤立；</li>
 *   <li>字数修整轮：首稿超带不再整章重掷，改为"带上一稿+目标带+差值"定向增删
 *       （对应 agent 当场数字数增删，治篇幅惯性不收敛）；</li>
 *   <li>输出 gate：字数带/章注/禁词/主角名出现，带结构化原因喂回重试 ≤3 轮，
 *       耗尽取最优稿落库交⑧硬判。</li>
 * </ul>
 * 防复刻开关（run.anti_repro，默认关）：关=连续创作会话（注入前情）；
 * 开=九轮原口径独立干净会话（不给前章原文，只给章纲概览）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveWriteService {

    public static final String NODE_WRITE = "derive_write";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    /** 章注：<!-- 本章1234字，挂拍：1,2,3 --> */
    static final Pattern COMMENT_PATTERN = Pattern.compile("<!--\\s*本章(\\d+)字[^>]*?挂拍[:：]\\s*([\\d,，\\s]*)\\s*-->");
    /** 禁词：素材编号与工作流痕迹（工程卫生，与防复刻开关无关，恒开） */
    static final Pattern FORBIDDEN = Pattern.compile("O-P\\d|S-P\\d|W-P\\d|O-F\\d|素材来源|抽取稿|标签层");

    /** 剧透禁令（v3.6 方案A）：揭示章前每章必注入——反暗示 + 无知之幕。 */
    static final String SPOILER_BAN =
            "本章处于揭示章之前，真相未揭晓。禁止以任何方式写出或暗示真相——"
            + "禁止给关键名词加引号/着重号/破折号强调，禁止“其实/仿佛/不知道的是”式旁白反讽，"
            + "禁止任何角色提前知情或言及；写主角视角必须按主角当前认知写（无知之幕）："
            + "他不知道的事就是不知道。线索只按埋设表以「现象」自然呈现，不加任何指向性修辞。";

    /** 九轮原口径：独立干净会话（anti_repro=ON 时用）。v3.6：文化语感/剧透禁令/密度/文风卡。 */
    static final String WRITE_PROMPT_INDEPENDENT = """
            你在按章节计划写一章小说正文。独立干净会话：只依据给出的全局设定与本章材料，不参考其他章节原文。
            全局：作品名、域格、类别与目标节奏、结局形状、世界观条目、标签层主线、
            创作前提（若有）、真相卡埋设表或完整真相卡（若有）、文风卡/目标文风（若有）。
            本章：章号/章题/预算字数/计划节奏、本章拍实例（seq + 具体情节点）。
            要求：
            1. 正文字数 = 预算 ±10%（按中文字数计，不含章注）；
            2. 必须兑现本章全部拍实例（情节按 seq 顺序展开，可丰满不可丢失）；
            3. 类别核心元素本章至少出现 1 次；节奏指令按计划节奏执行；
            4. 事件在域格内成立；人名与称谓必须沿用"全书人名锚点"与具体稿，禁止同人异名/换性/换视角；
            系列单卷卷末留钩子、独立短篇闭合；
            5. 禁止出现真实作品名、素材编号（O-P/S-P/W-P/O-F）、"素材/抽取/标签层"等来源痕迹；
            6. 『世界观前提』决定文化取向：人名/称谓/器物/服饰/建筑/礼俗符合该体系，禁止异文化元素；
            语言肌理同禁：不堆中文成语、不用文言句式与诗词化旁白，对话按人物身份与该世界观语感，
            环境描写用该文化感官库；
            7. 若状态含『创作前提』：人物设定/氛围/描写红线以此为准，与章纲冲突以章纲为骨架、意图为血肉；
            若含『结局段』：真相相关按其还原，不引入卡外事实；
            8. 真相卡（若有）：clues 按 planted_chapter 埋为「现象」，不加指向性修辞；anchors 数字全书一致；
            若同时含『剧透禁令』：揭示前章节按在场角色认知边界写作（无知之幕）——禁止给关键名词加
            引号/着重号、禁止"其实/仿佛"式旁白暗示、禁止任何角色提前知情；
            9. 叙事密度优先：每段须推进情节/人物/信息之一；前情仅供事实对齐，禁止复述与回顾式过渡；
            单处环境描写 ≤2 句；慢热=铺垫节奏，不等于注水；
            10. 若状态含『文风卡』或『目标文风』：句式/对话比/叙事密度/用语按卡执行，no_go 逐条规避；
            11. 只输出正文本身；正文最后单独一行输出章注：
               <!-- 本章X字，挂拍：seq,seq -->
            不要输出任何解释、标题行或 markdown 围栏。
            """;

    /** 连续创作会话（anti_repro=OFF 默认，harness）。v3.6：文化语感/剧透禁令/密度/文风卡。 */
    static final String WRITE_PROMPT_CONTINUOUS = """
            你在按章节计划写一章小说正文。输入含全书状态包：cast 人物表（含人设卡）、前情（前章正文尾段）、
            全书章纲概览、本章拍实例、创作前提/真相卡（若有）——你已"读过前文"，必须保持同一部书的连续性。
            要求：
            1. 正文字数 = 预算 ±10%（按中文字数计，不含章注）；
            2. 必须兑现本章全部拍实例（情节按 seq 顺序展开，可丰满不可丢失）；
            3. 人名、性别、身份、称谓严格沿用 cast 表，行为必须符合其 persona 行为准则与欲望，
            禁止同人异名/换性/换视角/中途换称谓/行为与人设相悖；
            重要配角一旦在前文出场，本章称呼必须与前文一致；
            4. 已在前情确定的事实（关键物件、已发生事件、人物关系）不得推翻或漂移；
            5. 类别核心元素本章至少出现 1 次；事件在域格内成立；
            6. 禁止出现真实作品名、素材编号（O-P/S-P/W-P/O-F）、"素材/抽取/标签层"等来源痕迹；
            7. 『世界观前提』决定文化取向：人名/称谓/器物/服饰/建筑/礼俗符合该体系，禁止异文化元素；
            语言肌理同禁：不堆中文成语、不用文言句式与诗词化旁白，对话按人物身份与该世界观语感，
            环境描写用该文化感官库；
            8. 若状态含『创作前提』：人物设定/氛围/描写红线以此为准；若含『结局段』：真相按其还原，
            不引入卡外事实；
            9. 真相卡（若有）：clues 按 planted_chapter 埋为「现象」；anchors 数字全书一致；
            若同时含『剧透禁令』：揭示前章节按在场角色认知边界写作（无知之幕）——禁止给关键名词加
            引号/着重号、禁止"其实/仿佛"式旁白暗示、禁止任何角色提前知情；
            10. 叙事密度优先：每段须推进情节/人物/信息之一；前情仅供事实对齐，禁止复述与回顾式过渡；
            单处环境描写 ≤2 句；慢热=铺垫节奏，不等于注水；
            11. 若状态含『文风卡』或『目标文风』：句式/对话比/叙事密度/用语按卡执行，no_go 逐条规避；
            12. 只输出正文本身；正文最后单独一行输出章注：
               <!-- 本章X字，挂拍：seq,seq -->
            不要输出任何解释、标题行或 markdown 围栏。
            """;

    /** 字数修整轮（不重掷情节）：带上稿定向增删到带内。 */
    static final String REPAIR_PROMPT = """
            你是篇幅修整编辑。给定上一稿正文与目标字数带，只做增删使其落在带内：
            1. 保持情节走向、拍兑现顺序、人名与 cast 称谓、叙事视角完全不变；
            2. 不足则扩写细节/动作/环境描写，超出则删冗余描写与重复句，不得删任何拍对应情节；
            3. 中文字数 = 预算 ±10%（不含章注）；
            4. 只输出修改后全文，最后单独一行章注：<!-- 本章X字，挂拍：seq,seq -->
            不要输出任何解释、标题行或 markdown 围栏。
            """;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final QcReportMapper qcReportMapper;
    private final LlmPort llm;

    /** ⑦：写下一章（无未写章则幂等返回 allDone）。 */
    public WriteVO writeNext(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"DRAFTING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 状态应为 DRAFTING，当前 " + run.getStatus());
        }
        long novelId = run.getNovelId();
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        ChapterDO target = chapters.stream()
                .filter(c -> c.getContent() == null || c.getContent().isBlank())
                .findFirst()
                .orElse(null);
        if (target == null) {
            runMapper.updateStatus(runId, "QC", null);
            return new WriteVO(runId, novelId, -1, 0, 0, 0, true);
        }

        NovelDO novel = novelMapper.selectById(novelId);
        List<BeatInstanceDO> chBeats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .eq(BeatInstanceDO::getChapterNo, target.getChapterNo())
                .eq(BeatInstanceDO::getStatus, "PLANNED")
                .orderByAsc(BeatInstanceDO::getSeq));

        boolean independent = Boolean.TRUE.equals(run.getAntiRepro());
        String user = buildUser(run, novel, target, chBeats, chapters, independent);
        int budget = target.getBudget();
        int lo = (int) Math.floor(budget * 0.9);
        int hi = (int) Math.ceil(budget * 1.1);
        String protagonist = DeriveQcService.readProtagonist(novel);
        String qcIssues = latestQcIssues(runId);

        String best = null;
        String lastIssue = "";
        boolean lastWasLen = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            List<LlmPort.Message> msgs = new ArrayList<>();
            String u;
            if (attempt > 0 && lastWasLen && best != null) {
                // harness·字数修整轮：不重掷情节，带稿定向增删（agent 当场增删的工程化）
                msgs.add(LlmPort.Message.system(REPAIR_PROMPT));
                int n = len(extractContent(best));
                u = "目标字数带 [" + lo + "," + hi + "]（预算" + budget + "），上一稿 " + n + " 字，"
                        + (n < lo ? "差 " + (lo - n) + " 字需扩写" : "超出 " + (n - hi) + " 字需压缩")
                        + "。上一稿：\n" + best;
            } else {
                msgs.add(LlmPort.Message.system(independent ? WRITE_PROMPT_INDEPENDENT : WRITE_PROMPT_CONTINUOUS));
                StringBuilder b = new StringBuilder(user);
                if (qcIssues != null && !qcIssues.isBlank()) {
                    b.append("\n\n上轮质检问题（全局约束，本章严禁再犯）：\n").append(qcIssues);
                }
                if (attempt > 0) {
                    b.append("\n\n上一稿问题（必须修复）：").append(lastIssue);
                }
                u = b.toString();
            }
            msgs.add(LlmPort.Message.user(u));
            LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(NODE_WRITE, runId, novelId, msgs, 0.7));
            String text = r.content() == null ? "" : r.content();
            if (best == null || Math.abs(len(extractContent(text)) - budget)
                    < Math.abs(len(extractContent(best)) - budget)) {
                best = text;
            }
            String issue = validate(text, lo, hi, protagonist);
            if (issue == null) {
                persist(target, chBeats, text);
                int remaining = (int) chapters.stream()
                        .filter(c -> c.getContent() == null || c.getContent().isBlank()).count();
                boolean allDone = remaining == 0;
                if (allDone) {
                    runMapper.updateStatus(runId, "QC", null);
                }
                log.info("⑦正文：novelId={} 第{}章 {}字（预算{}/±10%=[{},{}]）剩余{}章",
                        novelId, target.getChapterNo(), len(extractContent(text)), budget, lo, hi, remaining);
                return new WriteVO(runId, novelId, target.getChapterNo(),
                        len(extractContent(text)), budget, remaining, allDone);
            }
            lastIssue = issue;
            lastWasLen = issue.startsWith("正文字数");
        }
        // 3 轮都没过：采用最优稿落库（⑧ 会硬判并给 REWORK），不吞正文
        persist(target, chBeats, best);
        int remaining = (int) chapters.stream()
                .filter(c -> c.getContent() == null || c.getContent().isBlank()).count();
        log.warn("⑦正文：第{}章 3 轮未过 gate（末次：{}），按最优稿落库交⑧硬判", target.getChapterNo(), lastIssue);
        return new WriteVO(runId, novelId, target.getChapterNo(),
                len(extractContent(best)), budget, remaining, false);
    }

    // ---- 输入（状态包） ----

    /** 从意图里抽『世界观前提』句（含关键词的句子）；无则 null——朴素匹配（v3.4），不做 NLP。 */
    static String extractWorldviewPremise(String intent) {
        if (intent == null || intent.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : intent.split("[。；\\n！？]")) {
            String t = s.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.contains("世界观") || t.contains("为前提") || t.contains("大前提")) {
                if (sb.length() > 0) {
                    sb.append('；');
                }
                sb.append(t);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    String buildUser(RunDO run, NovelDO novel, ChapterDO ch, List<BeatInstanceDO> beats,
                     List<ChapterDO> allChapters, boolean independent) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("作品名", novel.getTitle());
        root.set("域", readTreeSafe(run.getDomainSnapshot()));
        root.set("类别", readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        JsonNode assets = readTreeSafe(novel.getStoryAssets());

        // ---- 创作前提/结局段（v3.6 brief 拆分：premise 全程、ending 仅揭示章起；老 run 回退 intent 全文+警示）----
        JsonNode brief = assets.path("brief");
        int revealCh = assets.path("truth").path("reveal_chapter").asInt(Integer.MAX_VALUE);
        boolean beforeReveal = ch.getChapterNo() < revealCh;
        if (brief.path("premise").asText("").trim().isEmpty()) {
            if (run.getParamIntent() != null && !run.getParamIntent().isBlank()) {
                root.put("题材意图（含结局真相：揭示前只取人物/氛围/红线，严禁暗示结局）",
                        run.getParamIntent());
            }
        } else {
            root.put("创作前提（人物设定/世界观/氛围/描写红线以此为准；与章纲冲突以章纲为骨架、意图为血肉）",
                    brief.path("premise").asText(""));
            if (!beforeReveal && !brief.path("ending").asText("").trim().isEmpty()) {
                root.put("结局段（真相相关，本章起可用）", brief.path("ending").asText(""));
            }
        }
        String wv = extractWorldviewPremise(run.getParamIntent());
        if (wv != null) {
            root.put("世界观前提（文化取向硬约束：人名/称谓/器物/服饰/建筑/礼俗符合该体系，禁止异文化元素）", wv);
        }
        // ---- 文风卡（v3.6）：param_style 目标文风 > 源书 style_profile ----
        if (run.getParamStyle() != null && !run.getParamStyle().isBlank()) {
            root.put("目标文风（优先于源书文风卡；冲突处以此为准）", run.getParamStyle());
        } else {
            JsonNode sp = readTreeSafe(novel.getStorySettings()).path("style_profile");
            if (sp.isObject()) {
                root.set("文风卡（源书画像；句式/对话比/密度/用语按此执行，no_go 逐条规避）", sp);
            }
        }
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());

        // ---- 状态包：cast 人物表（harness 核心，任何模式都给——它不是"其他章原文"）----
        root.put("主角名", assets.path("protagonist").asText(""));
        root.set("cast人物表", assets.path("cast"));

        // ---- 真相卡按章分段注入（v3.6 剧透门禁核心：揭示前只给埋设表+禁令，solution 不下凡）----
        JsonNode truth = assets.path("truth");
        if (truth.path("solution").isObject() || truth.path("culprit").asText("").length() > 0) {
            if (beforeReveal) {
                ObjectNode sched = root.putObject("真相卡·埋设表");
                sched.put("reveal_chapter", revealCh == Integer.MAX_VALUE ? -1 : revealCh);
                sched.set("clues", truth.path("clues"));
                sched.set("timeline_anchors", truth.path("timeline_anchors"));
                root.put("剧透禁令", SPOILER_BAN);
            } else {
                root.set("真相卡（含 solution，本章起可还原真相）", truth);
            }
        }

        var c = root.putObject("本章");
        c.put("no", ch.getChapterNo());
        c.put("title", ch.getTitle());
        c.put("budget", ch.getBudget());
        c.put("pace_plan", ch.getPacePlan() == null ? "" : ch.getPacePlan().toPlainString());
        c.put("draft_outline", ch.getDraftOutline() == null ? "" : ch.getDraftOutline());
        var arr = c.putArray("拍实例");
        for (BeatInstanceDO b : beats) {
            var n = arr.addObject();
            n.put("seq", b.getSeq());
            n.put("beat_type", b.getBeatType());
            n.put("filled", b.getFilledText() == null ? "" : b.getFilledText());
        }

        // ---- 全书章纲概览（情节与人名锚点，任何模式都给）----
        var o = root.putArray("全书章纲概览");
        for (ChapterDO other : allChapters) {
            if (other.getChapterNo() == ch.getChapterNo()) {
                continue;
            }
            var n = o.addObject();
            n.put("no", other.getChapterNo());
            n.put("title", other.getTitle());
            n.put("outline", other.getDraftOutline() == null ? "" : other.getDraftOutline());
        }

        // ---- 前情：已写成稿章的正文尾段（仅 anti_repro=OFF；"独立干净会话"口径下不给原文）----
        if (!independent) {
            var prev = root.putArray("前情（已写章正文尾段，事实与称谓以此为准）");
            for (ChapterDO other : allChapters) {
                if (other.getChapterNo() >= ch.getChapterNo()
                        || other.getContent() == null || other.getContent().isBlank()) {
                    continue;
                }
                var n = prev.addObject();
                n.put("no", other.getChapterNo());
                n.put("title", other.getTitle());
                String body = other.getContent();
                n.put("tail", body.length() <= 300 ? body : body.substring(body.length() - 300));
            }
        }
        return root.toString();
    }

    /** 最新一轮 qc_report 的 issues（回炉约束）；无则 null。 */
    String latestQcIssues(long runId) {
        List<QcReportDO> rows = qcReportMapper.selectList(new LambdaQueryWrapper<QcReportDO>()
                .eq(QcReportDO::getRunId, runId)
                .orderByDesc(QcReportDO::getRoundNo)
                .last("LIMIT 1"));
        if (rows.isEmpty() || rows.get(0).getIssues() == null) {
            return null;
        }
        try {
            JsonNode arr = STATIC_MAPPER.readTree(rows.get(0).getIssues());
            StringBuilder sb = new StringBuilder();
            arr.forEach(i -> sb.append("- ").append(i.asText()).append('\n'));
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 校验与落库 ----

    /** 返回 null=通过；否则是喂回给 LLM 的问题描述（"正文字数"开头=字数问题，走修整轮）。 */
    static String validate(String text, int lo, int hi, String protagonist) {
        if (text == null || text.isBlank()) {
            return "正文为空";
        }
        Matcher m = COMMENT_PATTERN.matcher(text);
        if (!m.find()) {
            return "缺章注行（<!-- 本章X字，挂拍：seq --> 格式，置于正文最后）";
        }
        String body = extractContent(text);
        int n = len(body);
        if (n < lo || n > hi) {
            return "正文字数 " + n + " 超出预算带 [" + lo + "," + hi + "]（预算±10%），调整篇幅";
        }
        Matcher f = FORBIDDEN.matcher(body);
        if (f.find()) {
            return "正文含禁词「" + f.group() + "」（素材编号/来源痕迹），删除";
        }
        // 输出 gate：主角名必须出现（称谓漂移的最廉价硬信号；主角可被称为"他"的章也几乎必点名）
        if (protagonist != null && !protagonist.isBlank() && !body.contains(protagonist)) {
            return "正文未出现主角名「" + protagonist + "」（cast 锚点），人名与前文不一致，修正";
        }
        return null;
    }

    /** 剥掉章注行与代码围栏，得到纯正文。 */
    static String extractContent(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("```[a-zA-Z]*", "").trim();
        Matcher m = COMMENT_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            last = m.end();
        }
        sb.append(s.substring(last));
        return sb.toString().replaceAll("(?m)^\\s*$\\n?", "").strip();
    }

    static int len(String s) {
        return s == null ? 0 : s.replaceAll("\\s", "").length();
    }

    void persist(ChapterDO ch, List<BeatInstanceDO> beats, String rawText) {
        Matcher m = COMMENT_PATTERN.matcher(rawText == null ? "" : rawText);
        String comment = "";
        if (m.find()) {
            comment = m.group().trim();
        }
        String body = extractContent(rawText);
        ch.setContent(body);
        ch.setCharCount(len(body));
        ch.setComment(comment);
        ch.setStatus("DRAFTED");
        chapterMapper.updateById(ch);
        for (BeatInstanceDO b : beats) {
            b.setStatus("WRITTEN");
            beatInstanceMapper.updateById(b);
        }
    }

    static JsonNode readTreeSafe(String json) {
        try {
            return STATIC_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return STATIC_MAPPER.createObjectNode();
        }
    }
}
