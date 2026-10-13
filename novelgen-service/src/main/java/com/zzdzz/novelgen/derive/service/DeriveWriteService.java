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

    /** 九轮原口径：独立干净会话（anti_repro=ON 时用）。 */
    static final String WRITE_PROMPT_INDEPENDENT = """
            你在按章节计划写一章小说正文。独立干净会话：只依据给出的全局设定与本章材料，不参考其他章节原文。
            全局：作品名、域格、类别与目标节奏、结局形状、世界观条目、标签层主线。
            本章：章号/章题/预算字数/计划节奏、本章拍实例（seq + 具体情节点）。
            要求：
            1. 正文字数 = 预算 ±10%（按中文字数计，不含章注）；
            2. 必须兑现本章全部拍实例（情节按 seq 顺序展开，可丰满不可丢失）；
            3. 类别核心元素本章至少出现 1 次；节奏指令按计划节奏执行；
            4. 事件在域格内成立；人名与称谓必须沿用"全书人名锚点"与具体稿，禁止同人异名/换性/换视角；
            系列单卷卷末留钩子、独立短篇闭合；
            5. 禁止出现真实作品名、素材编号（O-P/S-P/W-P/O-F）、"素材/抽取/标签层"等来源痕迹；
            6. 只输出正文本身；正文最后单独一行输出章注：
               <!-- 本章X字，挂拍：seq,seq -->
            不要输出任何解释、标题行或 markdown 围栏。
            """;

    /** 连续创作会话（anti_repro=OFF 默认，harness）：前情+cast 是称谓与事实的锚。 */
    static final String WRITE_PROMPT_CONTINUOUS = """
            你在按章节计划写一章小说正文。输入含全书状态包：cast 人物表、前情（前章正文尾段）、
            全书章纲概览、本章拍实例——你已"读过前文"，必须与前文保持同一部书的连续性。
            要求：
            1. 正文字数 = 预算 ±10%（按中文字数计，不含章注）；
            2. 必须兑现本章全部拍实例（情节按 seq 顺序展开，可丰满不可丢失）；
            3. 人名、性别、身份、称谓严格沿用 cast 表，禁止同人异名/换性/换视角/中途换称谓；
            重要配角一旦在前文出场，本章称呼必须与前文一致；
            4. 已在前情确定的事实（关键物件、已发生事件、人物关系）不得推翻或漂移；
            5. 类别核心元素本章至少出现 1 次；事件在域格内成立；
            6. 禁止出现真实作品名、素材编号（O-P/S-P/W-P/O-F）、"素材/抽取/标签层"等来源痕迹；
            7. 只输出正文本身；正文最后单独一行输出章注：
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

    String buildUser(RunDO run, NovelDO novel, ChapterDO ch, List<BeatInstanceDO> beats,
                     List<ChapterDO> allChapters, boolean independent) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("作品名", novel.getTitle());
        root.set("域", readTreeSafe(run.getDomainSnapshot()));
        root.set("类别", readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());

        // ---- 状态包：cast 人物表（harness 核心，任何模式都给——它不是"其他章原文"）----
        JsonNode assets = readTreeSafe(novel.getStoryAssets());
        root.put("主角名", assets.path("protagonist").asText(""));
        root.set("cast人物表", assets.path("cast"));

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
