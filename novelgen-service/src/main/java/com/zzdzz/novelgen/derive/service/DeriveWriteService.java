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
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.WriteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑦ 分章生成正文（v3.2 §⑦，独立干净会话）。
 *
 * <p>**每次调用只写一章**（写完下一章再调，全部完成时 run→QC）——单章 LLM 长达 1~3 分钟，
 * 逐章端点天然断点续跑，不怕客户端超时打断。
 * 校验：budget±10%（带原因重试 ≤2 轮）、禁词（真实作品名/素材编号 O-P/S-P/W-P/O-F/来源痕迹）、
 * 章注 <!-- 本章X字，挂拍：seq… --> 解析进 chapters.comment 并从正文剥离。
 * 产出：chapters.content/char_count/comment/status=DRAFTED、本章拍实例→WRITTEN、全书完成→run=QC。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveWriteService {

    public static final String NODE_WRITE = "derive_write";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    /** 章注：<!-- 本章1234字，挂拍：1,2,3 --> */
    static final Pattern COMMENT_PATTERN = Pattern.compile("<!--\\s*本章(\\d+)字[^>]*?挂拍[:：]\\s*([\\d,，\\s]*)\\s*-->");
    /** 禁词：素材编号与工作流痕迹 */
    static final Pattern FORBIDDEN = Pattern.compile("O-P\\d|S-P\\d|W-P\\d|O-F\\d|素材来源|抽取稿|标签层");

    static final String WRITE_PROMPT = """
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
            // 全部写完 → run→QC（幂等）
            runMapper.updateStatus(runId, "QC", null);
            return new WriteVO(runId, novelId, -1, 0, 0, 0, true);
        }

        NovelDO novel = novelMapper.selectById(novelId);
        List<BeatInstanceDO> chBeats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .eq(BeatInstanceDO::getChapterNo, target.getChapterNo())
                .eq(BeatInstanceDO::getStatus, "PLANNED")
                .orderByAsc(BeatInstanceDO::getSeq));

        String user = buildUser(run, novel, target, chBeats, chapters);
        int budget = target.getBudget();
        int lo = (int) Math.floor(budget * 0.9);
        int hi = (int) Math.ceil(budget * 1.1);

        // 回炉时带上一轮质检意见（⑧ REWORK → ⑦ 必须知道改什么）
        String qcIssues = latestQcIssues(runId);

        String best = null;
        String lastIssue = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            List<LlmPort.Message> msgs = new ArrayList<>();
            msgs.add(LlmPort.Message.system(WRITE_PROMPT));
            StringBuilder u = new StringBuilder(user);
            if (qcIssues != null && !qcIssues.isBlank()) {
                u.append("\n\n上轮质检问题（全局约束，本章严禁再犯）：").append(qcIssues);
            }
            if (attempt > 0) {
                u.append("\n\n上一稿问题（必须修复）：").append(lastIssue);
            }
            msgs.add(LlmPort.Message.user(u.toString()));
            LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(NODE_WRITE, runId, novelId, msgs, 0.7));
            String text = r.content() == null ? "" : r.content();
            if (best == null || Math.abs(len(text) - budget) < Math.abs(len(best) - budget)) {
                best = text;
            }
            String issue = validate(text, lo, hi);
            if (issue == null) {
                persist(target, chBeats, text);
                // persist 已改写 target（chapters 列表内对象）——剩余数=仍无正文的章数，不再减 1
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
        }
        // 3 轮都没过：采用最优稿落库（⑧ 会硬判并给 REWORK），不吞正文
        persist(target, chBeats, best);
        int remaining = (int) chapters.stream()
                .filter(c -> c.getContent() == null || c.getContent().isBlank()).count();
        log.warn("⑦正文：第{}章 3 轮未达预算带（末次：{}），按最优稿落库交⑧硬判", target.getChapterNo(), lastIssue);
        return new WriteVO(runId, novelId, target.getChapterNo(),
                len(extractContent(best)), budget, remaining, false);
    }

    // ---- 输入 ----

    String buildUser(RunDO run, NovelDO novel, ChapterDO ch, List<BeatInstanceDO> beats,
                     List<ChapterDO> allChapters) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("作品名", novel.getTitle());
        root.set("域", readTreeSafe(run.getDomainSnapshot()));
        root.set("类别", readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        // 人名锚点：主角名（⑥b 落库）+ 全书各章具体稿里出现的人名（防同人异名）
        root.put("主角名", DeriveQcService.readProtagonist(novel));
        root.set("全书人名锚点", STATIC_MAPPER.valueToTree(extractNames(allChapters, ch.getChapterNo())));
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
        // 其余章的章纲概览（只知道情节与人名，不给正文——保持独立会话）
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
        return root.toString();
    }

    /**
     * 从全书具体稿（拍 filled + 章纲）提取 2~4 字人名候选——只做锚点提示，不做 NER。
     * 口径：CJK 人名常见姓氏表 + 全书文本里"姓+1/2字"的出现形式。
     */
    static List<String> extractNames(List<ChapterDO> chapters, int currentNo) {
        // 具体稿人名已在 ⑥b 输出中收敛；此处退化为章纲人名扫描
        Set<String> found = new LinkedHashSet<>();
        Pattern p = Pattern.compile("[赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛范彭郎鲁韦昌马苗凤花方俞任袁柳][一-龥]{1,2}");
        for (ChapterDO c : chapters) {
            String text = (c.getDraftOutline() == null ? "" : c.getDraftOutline())
                    + " " + (c.getTitle() == null ? "" : c.getTitle());
            Matcher m = p.matcher(text);
            while (m.find()) {
                String name = m.group();
                if (name.length() >= 2 && name.length() <= 3) {
                    found.add(name);
                }
            }
        }
        return List.copyOf(found);
    }

    /** 最新一轮 qc_report 的 issues（回炉约束）；无则 null。 */
    String latestQcIssues(long runId) {
        var rows = qcReportMapper.selectList(new LambdaQueryWrapper<com.zzdzz.novelgen.model.entity.derive.QcReportDO>()
                .eq(com.zzdzz.novelgen.model.entity.derive.QcReportDO::getRunId, runId)
                .orderByDesc(com.zzdzz.novelgen.model.entity.derive.QcReportDO::getRoundNo)
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

    /** 返回 null=通过；否则是喂回给 LLM 的问题描述。 */
    static String validate(String text, int lo, int hi) {
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
        // 去掉围栏残留空行
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

    static com.fasterxml.jackson.databind.JsonNode readTreeSafe(String json) {
        try {
            return STATIC_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return STATIC_MAPPER.createObjectNode();
        }
    }
}
