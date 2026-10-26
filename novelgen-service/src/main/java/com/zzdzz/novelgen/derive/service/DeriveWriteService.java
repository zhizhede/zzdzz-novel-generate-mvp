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
import com.zzdzz.novelgen.derive.mapper.WorldStateMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.WorldStateDO;
import com.zzdzz.novelgen.model.vo.derive.WriteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑦ 分章生成正文（v3.2 §⑦ + harness 化，2026-10-13）。
 *
 * <p><b>harness 四件套</b>（把 agent 在环时天然具备的状态工程化）：
 * <ul>
 *   <li>状态包注入：全书 cast 表（⑥b 落 story_assets）+ 前章正文尾段 + 全书章纲概览
 *       + ⑧ 上轮 issues + <b>世界状态账</b>（V119 world_states，⑦每章写完用 world_state
 *       节点抽取 upsert，下一章注入——治跨章时间/位置/同一事件写两次）——每次调用等价于
 *       "看过前文再写"，孤立重写不再孤立；</li>
 *   <li>字数修整轮：首稿超带不再整章重掷，改为"带上一稿+目标带+差值"定向增删
 *       （对应 agent 当场数字数增删，治篇幅惯性不收敛）；</li>
 *   <li>输出 gate：字数带/禁词/主角名出现，带结构化原因喂回重试 ≤3 轮，
 *       耗尽取最优稿落库交⑧硬判。<b>章注不再由 LLM 输出</b>（P0，2026-10-16）——
 *       字数与挂拍 seq 系统可算，persist 时机器合成，避免 run10/run12 实测的
 *       「缺章注打满 3 轮」重发损耗（12 章 9 章打满 ≈1h 白烧）。</li>
 * </ul>
 * 防复刻开关（run.anti_repro，默认关）：关=连续创作会话（注入前情）；
 * 开=九轮原口径独立干净会话（不给前章原文，只给章纲概览）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveWriteService {

    public static final String NODE_WRITE = "derive_write";

    /** 世界状态抽取节点（V102 种子行，可路由快模型；V119 起真接⑦）。 */
    public static final String NODE_WORLD_STATE = "world_state";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    /** 章注：<!-- 本章1234字，挂拍：1,2,3 --> */
    static final Pattern COMMENT_PATTERN = Pattern.compile("<!--\\s*本章(\\d+)字[^>]*?挂拍[:：]\\s*([\\d,，\\s]*)\\s*-->");
    /** 禁词：素材编号与工作流痕迹（工程卫生，与防复刻开关无关，恒开） */
    static final Pattern FORBIDDEN = Pattern.compile("O-P\\d|S-P\\d|W-P\\d|O-F\\d|素材来源|抽取稿|标签层");
    /** 过程话泄漏（审稿实锤：字数自控笔记、「第十一章——」出戏台词）——恒开 gate */
    static final Pattern META_LEAK = Pattern.compile(
            "估算字数|目标字数带|正文字数|本章\\d+字|挂拍|第[一二三四五六七八九十百千零两\\d]+章[——\\-]"
            + "|请提供第[零一二三四五六七八九十百千两\\d]+章|我需要先确认第[零一二三四五六七八九十百千两\\d]+章"
            + "|我先确认第[零一二三四五六七八九十百千两\\d]+章|上一稿|状态包|拍实例|evidence 从正文|章纲明确记载");
    /** 内容红线自检（禁词/过程话/主角名，不含契约与字数）——⑦ 三轮兜底取稿时，
     *  内容干净的稿优先于字数更贴的占位话（run45 实弹：163 字助手话按字数距离赢过 2500 字真章）。 */
    static boolean contentClean(String text, String protagonist) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String body = extractContent(text);
        if (FORBIDDEN.matcher(body).find() || META_LEAK.matcher(body).find()) {
            return false;
        }
        return protagonist == null || protagonist.isBlank() || body.contains(protagonist);
    }

    /** 输出层章契约：每拍必须给出正文原句证据，末章闭合时必须交出收束表。 */
    static final Pattern CONTRACT_PATTERN = Pattern.compile(
            "<!--\\s*拍兑现契约\\s*[:：]\\s*(\\{.*?\\})\\s*-->", Pattern.DOTALL);

    /** 世界状态抽取（V119）：只认正文明确时间线索，禁反推总历时；失败 fail-open 不炸章。 */
    static final String WORLD_STATE_PROMPT = """
            你在从一章小说正文抽取「世界状态账」，供后续章节防时序/位置/人物矛盾。
            只依据本章正文与上一章状态，禁止编造正文未写的事实。
            规则：
            1. time：只认本章正文明确写出的时间线索（钟点/次日/数日后等）；
               局部时长（如「这半月掉的肉」）严禁反推总历时；
               本章无线索则原样沿用上一章 time；上一章也无则写「未推进」；
            2. characters：本章末角色状态对象。若输入含 characterKey 映射，以 key 为键；
               否则以正式姓名为键。每人包含 name/alive/presence/location/knowledge/
               relationships/lastSeenChapter；
            3. locations：本章末仍主要在场的具名人物→地点（兼容旧读路径）；
            4. present：本章末仍在一起的具名人物数组（兼容旧读路径）；
            5. key_facts：本章确立且后续不得推翻的事实 ≤8 条（物件下落/已发生事件/关系变化）；
            6. open_loops：未闭合事件/悬念 ≤8 条（脱困与否、未找到的人或物）；
            7. 上一章状态里已有、且本章未推翻的条目必须保留，禁止清空账本。
            输出 JSON 对象：
            {"time":"…","characters":{},"locations":{},"present":[],
             "key_facts":[],"open_loops":[]}
            只输出 JSON 对象，无其他文字。
            """;

    /** 剧透禁令（v3.6 方案A）：揭示章前每章必注入——反暗示 + 无知之幕。 */
    static final String SPOILER_BAN =
            "本章处于揭示章之前，真相未揭晓。禁止以任何方式写出或暗示真相——"
            + "禁止给关键名词加引号/着重号/破折号强调，禁止“其实/仿佛/不知道的是”式旁白反讽，"
            + "禁止任何角色提前知情或言及；写主角视角必须按主角当前认知写（无知之幕）："
            + "他不知道的事就是不知道。线索只按埋设表以「现象」自然呈现，不加任何指向性修辞。";

    /** 九轮原口径：独立干净会话（anti_repro=ON 时用）。v3.6：文化语感/剧透禁令/密度/文风卡。 */
    static final String WRITE_PROMPT_INDEPENDENT = """
            你在按章节计划写一章小说正文。独立干净会话：只依据给出的全局设定与本章材料，不参考其他章节原文。
            全局：作品名、域格、类别与目标节奏、结局形状、世界观条目、标签层主线、TruthContract（若有，唯一真相合同）、
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
            10b. 语感冲突时的优先级：目标文风 > 文风卡 > 创作前提里的『描写红线/氛围』描述。
                前提中的氛围词只约束"写什么可信"（题材内成立），不约束"用什么腔调写"——
                若前提写成冷硬卷宗感而目标文风是轻小说口语体，一律写轻小说口语体；
            11. 若状态含『世界状态账』：time/地点/在场人物/未闭合事件以此为准——
               禁止推翻已确立事实，禁止把已结束的脱困/已闭合事件再演一遍；
            12. 只输出正文本身；正文最后单独一行输出章注：
               <!-- 本章X字，挂拍：seq,seq -->
            13. 章注之后必须单独输出一行机器可读的「拍兑现契约」：
               <!-- 拍兑现契约：{"beats":[{"seq":1,"evidence":"正文原句"}],"open_threads":["未解决悬念"],"closed_threads":["本章解决的悬念"]} -->
               beats 必须与输入拍实例完全一致（不多不少）；每个 evidence 必须从本章正文逐字摘录一句，
               不得改写、概括或编造；open_threads/closed_threads 没有时必须给空数组。
            正文由你在本回合从零写出，所有材料已在输入中——禁止反问、禁止索要上一稿或既有正文。
            禁止输出字数估算、预算核对、"第N章——"式出戏旁白等任何过程话。
            不要输出任何解释、标题行或 markdown 围栏。
            """;

    /** 连续创作会话（anti_repro=OFF 默认，harness）。v3.6：文化语感/剧透禁令/密度/文风卡。 */
    static final String WRITE_PROMPT_CONTINUOUS = """
            你在按章节计划写一章小说正文。输入含全书状态包：cast 人物表（含人设卡）、前情（前章正文尾段）、
            全书章纲概览、本章拍实例、创作前提/真相卡（若有）——你已"读过前文"，必须保持同一部书的连续性；
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
            11b. 语感冲突时的优先级：目标文风 > 文风卡 > 创作前提里的『描写红线/氛围』描述。
                前提中的氛围词只约束"写什么可信"（题材内成立），不约束"用什么腔调写"——
                若前提写成冷硬卷宗感而目标文风是轻小说口语体，一律写轻小说口语体；
            12. 若状态含『世界状态账』：time/地点/在场人物/未闭合事件以此为准——
               禁止推翻已确立事实，禁止把已结束的脱困/已闭合事件再演一遍；
            13. 只输出正文本身；正文最后单独一行输出章注：
               <!-- 本章X字，挂拍：seq,seq -->
            14. 章注之后必须单独输出一行机器可读的「拍兑现契约」：
               <!-- 拍兑现契约：{"beats":[{"seq":1,"evidence":"正文原句"}],"open_threads":[],"closed_threads":[]} -->
               beats 与输入拍实例完全一致；evidence 从正文逐字摘录；无悬念时数组给空。
            正文由你在本回合从零写出，所有材料已在输入中——禁止反问、禁止索要上一稿或既有正文。
            禁止输出字数估算、预算核对、"第N章——"式出戏旁白等任何过程话。
            不要输出任何解释、标题行或 markdown 围栏。
            """;

    /** 字数修整轮（不重掷情节）：带上稿定向增删到带内。 */
    static final String REPAIR_PROMPT = """
            你是篇幅修整编辑。给定上一稿正文与目标字数带，只做增删使其落在带内：
            1. 保持情节走向、拍兑现顺序、人名与 cast 称谓、叙事视角完全不变；
            2. 不足则扩写细节/动作/环境描写，超出则删冗余描写与重复句，不得删任何拍对应情节；
            3. 中文字数 = 预算 ±10%（不含章注）；
            4. 只输出修改后全文，最后单独一行章注：<!-- 本章X字，挂拍：seq,seq -->
            5. 保留并重新输出拍兑现契约：<!-- 拍兑现契约：{"beats":[{"seq":1,"evidence":"正文原句"}],"open_threads":[],"closed_threads":[]} -->
               evidence 仍须逐字来自修改后的正文，拍号不得增删。
            不要输出任何解释、标题行或 markdown 围栏。
            """;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final QcReportMapper qcReportMapper;
    private final LlmPort llm;
    private final WorldStateMapper worldStateMapper;
    private final LlmJson llmJson;

    /** ⑦：写下一章（无未写章则幂等返回 allDone）。 */
    public WriteVO writeNext(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"DRAFTING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 状态应为 DRAFTING，当前 " + run.getStatus());
        }
        if (!DeriveGateService.gatePassed(run.getStepStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "立项闸未过：先 POST /api/derive/runs/" + runId + "/gate 审大纲（GO 后才开写）");
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
            return new WriteVO(runId, novelId, -1, 0, 0, 0, true, true, "");
        }

        NovelDO novel = novelMapper.selectById(novelId);
        List<BeatInstanceDO> chBeats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .eq(BeatInstanceDO::getChapterNo, target.getChapterNo())
                .eq(BeatInstanceDO::getStatus, "PLANNED")
                .orderByAsc(BeatInstanceDO::getSeq));

        boolean independent = Boolean.TRUE.equals(run.getAntiRepro());
        boolean lenCheck = Boolean.TRUE.equals(run.getLenCheck()); // V115：默认关=不做字数带校验（无修整轮）
        WorldStateDO prevWs = worldStateMapper == null ? null
                : worldStateMapper.findLatestBefore(novelId, target.getChapterNo());
        String user = buildUser(run, novel, target, chBeats, chapters, independent,
                prevWs == null ? null : prevWs.getState());
        int budget = target.getBudget();
        int lo = (int) Math.floor(budget * 0.9);
        int lastNo = chapters.isEmpty() ? target.getChapterNo() : chapters.get(chapters.size() - 1).getChapterNo();
        boolean requireClosure = "闭合".equals(run.getParamOutcome()) && target.getChapterNo() == lastNo;
        int hi = (int) Math.ceil(budget * 1.1);
        String protagonist = DeriveQcService.readProtagonist(novel);
        String qcIssues = latestQcIssues(runId);

        String best = null;
        boolean bestClean = false;
        String lastIssue = "";
        String lastText = "";
        boolean lastWasLen = false;
        DeriveActivity.begin(runId, "⑦ 第 " + target.getChapterNo() + " 章正文"
                + (target.getTitle() == null || target.getTitle().isBlank() ? "" : "《" + target.getTitle() + "》"));
        try {
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
                    if (lastText != null && !lastText.isBlank()) {
                        b.append("\n上一稿全文（在其上修复；禁止反问、禁止索要材料，直接输出修复后的完整正文）：\n")
                                .append(lastText);
                    }
                }
                u = b.toString();
            }
            msgs.add(LlmPort.Message.user(u));
            // 2026-10-18：⑦ 切流式——非流式干等 12 分钟会被链路 ~720s 绝对超时 RST_STREAM 掐死
            //（run21 第3章 727s×2 实弹）；流式有增量保活，顺带把实时字数推进度。
            LlmPort.ChatResult r = llm.chatStream(
                    new LlmPort.ChatRequest(NODE_WRITE, novelId, null, runId, msgs, 0.7),
                    (think, piece) -> DeriveActivity.touch(runId, piece));
            String text = r.content() == null ? "" : r.content();
            lastText = text;
            boolean clean = contentClean(text, protagonist);
            if (best == null || (clean && !bestClean)
                    || (clean == bestClean && Math.abs(len(extractContent(text)) - budget)
                    < Math.abs(len(extractContent(best)) - budget))) {
                best = text;
                bestClean = clean;
            }
            String issue = validate(text, lo, hi, protagonist, lenCheck, chBeats, requireClosure);
            if (issue == null) {
                persist(target, chBeats, text);
                refreshWorldState(runId, novelId, target, text);
                int remaining = (int) chapters.stream()
                        .filter(c -> c.getContent() == null || c.getContent().isBlank()).count();
                boolean allDone = remaining == 0;
                if (allDone) {
                    runMapper.updateStatus(runId, "QC", null);
                }
                log.info("⑦正文：novelId={} 第{}章 {}字（预算{}/±10%=[{},{}]）剩余{}章",
                        novelId, target.getChapterNo(), len(extractContent(text)), budget, lo, hi, remaining);
                return new WriteVO(runId, novelId, target.getChapterNo(),
                        len(extractContent(text)), budget, remaining, allDone, true, "");
            }
            lastIssue = issue;
            lastWasLen = issue.startsWith("正文字数");
        }
        } finally {
            DeriveActivity.end(runId);
        }
        // 3 轮都没过：采用最优稿落库（⑧ 会硬判并给 REWORK），不吞正文
        persist(target, chBeats, best);
        refreshWorldState(runId, novelId, target, best);
        int remaining = (int) chapters.stream()
                .filter(c -> c.getContent() == null || c.getContent().isBlank()).count();
        log.warn("⑦正文：第{}章 3 轮未过 gate（末次：{}），按最优稿落库交⑧硬判", target.getChapterNo(), lastIssue);
        return new WriteVO(runId, novelId, target.getChapterNo(),
                len(extractContent(best)), budget, remaining, false, false, lastIssue);
    }

    // ================= ⑦ 服务端连跑（2026-10-18 根修：按一次跑到底，关页面不断） =================

    private static final java.util.concurrent.ConcurrentHashMap<Long, WriteLoop> LOOPS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static final class WriteLoop {
        final long runId;
        final long startedMs = System.currentTimeMillis();
        volatile boolean stop;
        volatile boolean running = true;
        volatile boolean allDone;
        volatile int chaptersDone;
        volatile Integer lastChapterNo;
        volatile long lastChapterMs;
        volatile String lastError = "";

        WriteLoop(long runId) {
            this.runId = runId;
        }
    }

    /** 启动服务端连跑（单飞：同一 run 同时只许一个循环；全流程主控在跑时不许另起）。 */
    public synchronized com.zzdzz.novelgen.model.vo.derive.WriteLoopVO startLoop(long runId) {
        WriteLoop cur = LOOPS.get(runId);
        if (cur != null && cur.running) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该 run 的服务端连跑已在运行中（已写 " + cur.chaptersDone + " 章）——看进度或先停止");
        }
        if (DeriveAutoFlowService.autoRunning(runId)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该 run 的全流程无人值守正在运行——⑦ 由它推进，无需另起连跑");
        }
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"DRAFTING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 状态应为 DRAFTING，当前 " + run.getStatus());
        }
        if (!DeriveGateService.gatePassed(run.getStepStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "立项闸未过：先 POST /api/derive/runs/" + runId + "/gate 审大纲（GO 后才开写）");
        }
        WriteLoop loop = new WriteLoop(runId);
        LOOPS.put(runId, loop);
        Thread t = new Thread(() -> runLoop(loop), "derive-write-loop-" + runId);
        t.setDaemon(true);
        t.start();
        log.info("⑦ 服务端连跑启动：runId={}", runId);
        return loopVO(loop);
    }

    /** ⑦ 连跑是否在跑（全流程主控启动前的互斥检查用）。 */
    static boolean loopRunning(long runId) {
        WriteLoop l = LOOPS.get(runId);
        return l != null && l.running;
    }

    /** 请求停止：当前章写完即停（不打断进行中的 LLM 调用）。 */
    public com.zzdzz.novelgen.model.vo.derive.WriteLoopVO stopLoop(long runId) {
        WriteLoop l = LOOPS.get(runId);
        if (l == null || !l.running) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该 run 没有正在运行的服务端连跑");
        }
        l.stop = true;
        log.info("⑦ 服务端连跑收到停止请求：runId={}（当前章写完即停）", runId);
        return loopVO(l);
    }

    /** 连跑状态快照（含当前章实时心跳）。 */
    public com.zzdzz.novelgen.model.vo.derive.WriteLoopVO loopStatus(long runId) {
        WriteLoop l = LOOPS.get(runId);
        if (l == null) {
            var act = DeriveActivity.snapshot(runId);
            return new com.zzdzz.novelgen.model.vo.derive.WriteLoopVO(runId, false, false, false,
                    0, null, 0, "", 0, act.phase(), act.chars(), act.idleMs());
        }
        return loopVO(l);
    }

    void runLoop(WriteLoop loop) {
        int transportRetries = 0;
        try {
            while (!loop.stop) {
                long chStart = System.currentTimeMillis();
                try {
                    WriteVO w = writeNext(loop.runId);
                    transportRetries = 0;
                    loop.lastChapterMs = System.currentTimeMillis() - chStart;
                    if (w.allDone() || w.chapterNo() < 0) {
                        loop.allDone = true;
                        log.info("⑦ 服务端连跑完成：runId={} 共写 {} 章 → 已转质检", loop.runId, loop.chaptersDone);
                        return;
                    }
                    loop.chaptersDone++;
                    loop.lastChapterNo = w.chapterNo();
                } catch (BizException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("run 状态应为 DRAFTING")) {
                        // 中途被外部改状态（如已转 QC）＝视同完成
                        loop.allDone = true;
                        return;
                    }
                    loop.lastError = msg;
                    log.warn("⑦ 服务端连跑停止：runId={} 原因={}", loop.runId, msg);
                    return;
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    // 传输类错误（RST_STREAM/连接被掐）自动重试 1 次——链路偶发，不该整跑即死
                    if (isTransportError(msg) && transportRetries < 1) {
                        transportRetries++;
                        log.warn("⑦ 传输类错误，连跑自动重试 {}/1：runId={} 原因={}",
                                transportRetries, loop.runId, msg);
                        continue;
                    }
                    loop.lastError = msg;
                    log.warn("⑦ 服务端连跑中断：runId={} 原因={}", loop.runId, msg);
                    return;
                }
            }
            log.info("⑦ 服务端连跑按停止请求收尾：runId={} 已写 {} 章", loop.runId, loop.chaptersDone);
        } finally {
            loop.running = false;
        }
    }

    /** 传输层偶发（HTTP/2 RST、连接重置）——区别于业务/解析类失败，允许连跑原地重试。 */
    static boolean isTransportError(String msg) {
        return msg.contains("RST_STREAM")
                || msg.contains("传输失败")
                || msg.contains("IOException")
                || msg.contains("Connection reset")
                || msg.contains("ConnectionReset");
    }

    com.zzdzz.novelgen.model.vo.derive.WriteLoopVO loopVO(WriteLoop l) {
        var act = DeriveActivity.snapshot(l.runId);
        return new com.zzdzz.novelgen.model.vo.derive.WriteLoopVO(l.runId, l.running, l.stop, l.allDone,
                l.chaptersDone, l.lastChapterNo, l.lastChapterMs, l.lastError,
                System.currentTimeMillis() - l.startedMs, act.phase(), act.chars(), act.idleMs());
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
        return buildUser(run, novel, ch, beats, allChapters, independent, null);
    }

    String buildUser(RunDO run, NovelDO novel, ChapterDO ch, List<BeatInstanceDO> beats,
                     List<ChapterDO> allChapters, boolean independent, String worldStateJson) {
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
        JsonNode truthContract = readTreeSafe(run.getStepStatus())
                .path("steps").path("TRUTH_CONTRACT");
        if (truthContract.isObject() && !truthContract.path("finalTruth").asText("").isBlank()) {
            root.put("主线", truthContract.path("finalTruth").asText(""));
            root.set("TruthContract（唯一真相合同，冲突时以此为准）", truthContract);
        } else {
            root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        }

        // ---- 状态包：cast 人物表（harness 核心，任何模式都给——它不是"其他章原文"）----
        root.put("主角名", assets.path("protagonist").asText(""));
        root.set("cast人物表", assets.path("cast"));

        // ---- 世界状态账（V119）：任何模式都给——结构化事实，不是前章原文 ----
        if (worldStateJson != null && !worldStateJson.isBlank()) {
            root.set("世界状态账（上一章快照；time/位置/在场/未闭合——禁止推翻、禁止同一事件写两次）",
                    readTreeSafe(worldStateJson));
        }

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
        // 剧透分段（修1，2026-10-16）：揭示前未来章（no>本章）只给章号+章题，不给纲——
        // run10 实证 ch12 章纲含完整解法，概览注入=剧透通道；已写章与本章纲照常（本章是写作指令）。
        var o = root.putArray("全书章纲概览");
        for (ChapterDO other : allChapters) {
            if (other.getChapterNo() == ch.getChapterNo()) {
                continue;
            }
            var n = o.addObject();
            n.put("no", other.getChapterNo());
            n.put("title", other.getTitle());
            if (beforeReveal && other.getChapterNo() > ch.getChapterNo()) {
                n.put("outline", "（剧透禁令：揭示章之前不提供未来章纲，只知章号与章题）");
            } else {
                n.put("outline", other.getDraftOutline() == null ? "" : other.getDraftOutline());
            }
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

    /** 返回 null=通过；否则是喂回给 LLM 的问题描述（"正文字数"开头=字数问题，走修整轮）。
     *  章注存在性不再校验（P0：persist 机器合成，LLM 输出与否均剥离重拼）。
     *  lenCheck（V115，默认关）：关=跳过字数带校验（不做修整轮），禁词/主角名仍硬校验。 */
    static String validate(String text, int lo, int hi, String protagonist, boolean lenCheck) {
        return validate(text, lo, hi, protagonist, lenCheck, List.of(), false);
    }

    static String validate(String text, int lo, int hi, String protagonist, boolean lenCheck,
                           List<BeatInstanceDO> beats, boolean requireClosure) {
        if (text == null || text.isBlank()) {
            return "正文为空";
        }
        String body = extractContent(text);
        // 内容红线先行（run45 实弹）：占位助手话（"请提供第N章正文"）既缺契约也常缺主角名，
        // 旧顺序先报「缺拍兑现契约/evidence 不在正文」会把模型推向反问索稿的死循环。
        Matcher f = FORBIDDEN.matcher(body);
        if (f.find()) {
            return "正文含禁词「" + f.group() + "」（素材编号/来源痕迹），删除";
        }
        Matcher m = META_LEAK.matcher(body);
        if (m.find()) {
            return "正文含过程话「" + m.group() + "」（字数估算/助手反问/章号出戏旁白），删除只留故事本身，直接输出正文";
        }
        // 输出 gate：主角名必须出现（称谓漂移的最廉价硬信号；主角可被称为"他"的章也几乎必点名）
        if (protagonist != null && !protagonist.isBlank() && !body.contains(protagonist)) {
            return "正文未出现主角名「" + protagonist + "」（cast 锚点），人名与前文不一致，修正";
        }
        if (beats != null && !beats.isEmpty()) {
            String contractIssue = validateContract(text, body, beats, requireClosure);
            if (contractIssue != null) {
                return contractIssue;
            }
        }
        int n = len(body);
        if (lenCheck && (n < lo || n > hi)) {
            return "正文字数 " + n + " 超出预算带 [" + lo + "," + hi + "]（预算±10%），调整篇幅";
        }
        return null;
    }

    /** 输出层契约校验：章注之外必须交出拍兑现表，evidence 必须能在正文里逐字找到。 */
    static String validateContract(String rawText, String body, List<BeatInstanceDO> beats,
                                   boolean requireClosure) {
        Matcher m = CONTRACT_PATTERN.matcher(rawText == null ? "" : rawText);
        if (!m.find()) {
            return "缺拍兑现契约（beats/evidence/open_threads/closed_threads）——按提示词输出契约行";
        }
        JsonNode contract;
        try {
            contract = STATIC_MAPPER.readTree(m.group(1));
        } catch (Exception e) {
            return "拍兑现契约 JSON 解析失败：" + e.getMessage();
        }
        JsonNode arr = contract.path("beats");
        if (!arr.isArray()) {
            return "拍兑现契约 beats 必须是数组";
        }
        Set<Integer> expected = new HashSet<>();
        for (BeatInstanceDO b : beats) {
            expected.add(b.getSeq());
        }
        Set<Integer> seen = new HashSet<>();
        for (JsonNode n : arr) {
            int seq = n.path("seq").asInt(0);
            if (!expected.contains(seq) || !seen.add(seq)) {
                return "拍兑现契约 beats 含非法或重复 seq: " + seq;
            }
            String evidence = n.path("evidence").asText("").trim();
            if (evidence.length() < 4) {
                return "拍 seq " + seq + " 缺 evidence（必须从正文逐字摘录）";
            }
            if (!body.contains(evidence)) {
                return "拍 seq " + seq + " 的 evidence 不在正文原文中——必须逐字摘录，不许改写或编造";
            }
        }
        if (!seen.equals(expected)) {
            return "拍兑现契约 beats 必须与本章拍实例完全一致（缺 "
                    + expected.stream().filter(x -> !seen.contains(x)).toList() + "）";
        }
        if (!contract.path("open_threads").isArray() || !contract.path("closed_threads").isArray()) {
            return "拍兑现契约 open_threads/closed_threads 必须是数组（没有就给空数组）";
        }
        if (requireClosure) {
            JsonNode closure = contract.path("closure");
            if (!closure.path("resolved_threads").isArray()
                    || closure.path("resolved_threads").isEmpty()
                    || closure.path("final_state").asText("").trim().isEmpty()) {
                return "结局形状=闭合：末章契约必须给非空 closure.resolved_threads 与 final_state";
            }
        }
        return null;
    }

    /** 剥掉章注行与代码围栏，得到纯正文。 */
    static String extractContent(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("```[a-zA-Z]*", "").trim();
        s = CONTRACT_PATTERN.matcher(s).replaceAll("");
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

    /** P0 章注机器合成：字数=纯正文实数，挂拍=本章 PLANNED 拍 seq（⑧ 拍兑现按此对账）。 */
    static String buildComment(String body, List<BeatInstanceDO> beats) {
        StringBuilder seqs = new StringBuilder();
        for (BeatInstanceDO b : beats) {
            if (seqs.length() > 0) {
                seqs.append(',');
            }
            seqs.append(b.getSeq());
        }
        return "<!-- 本章" + len(body) + "字，挂拍：" + seqs + " -->";
    }

    void persist(ChapterDO ch, List<BeatInstanceDO> beats, String rawText) {
        String body = extractContent(rawText);
        ch.setContent(body);
        ch.setCharCount(len(body));
        ch.setComment(buildComment(body, beats));
        ch.setStatus("DRAFTED");
        chapterMapper.updateContent(
                ch.getId(), ch.getContent(), ch.getCharCount(), ch.getComment(), ch.getStatus());
        for (BeatInstanceDO b : beats) {
            b.setStatus("WRITTEN");
            beatInstanceMapper.updateStatus(b.getId(), b.getStatus());
        }
    }

    /** ⑦ 落稿后抽取世界状态账（world_state 节点）；LLM 失败 fail-open，不拖垮正文。 */
    void refreshWorldState(long runId, long novelId, ChapterDO ch, String rawText) {
        if (worldStateMapper == null || llmJson == null) {
            return;
        }
        try {
            String body = extractContent(rawText);
            WorldStateDO prev = worldStateMapper.findLatestBefore(novelId, ch.getChapterNo());
            ObjectNode user = STATIC_MAPPER.createObjectNode();
            user.put("chapter_no", ch.getChapterNo());
            user.put("title", ch.getTitle() == null ? "" : ch.getTitle());
            user.put("content", body.length() > 8000 ? body.substring(0, 8000) : body);
            if (prev != null && prev.getState() != null && !prev.getState().isBlank()) {
                user.set("prev_state", readTreeSafe(prev.getState()));
            } else {
                user.putObject("prev_state");
            }
            String state = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_WORLD_STATE, novelId, null, runId,
                            List.of(LlmPort.Message.system(WORLD_STATE_PROMPT),
                                    LlmPort.Message.user(user.toString())), 0.2),
                    root -> {
                        if (!root.isObject()) {
                            throw new LlmJson.Bad("世界状态必须是 JSON 对象");
                        }
                        try {
                            return STATIC_MAPPER.writeValueAsString(root);
                        } catch (Exception e) {
                            throw new LlmJson.Bad("世界状态序列化失败：" + e.getMessage());
                        }
                    }, 2);
            WorldStateDO row = new WorldStateDO();
            row.setNovelId(novelId);
            row.setChapterNo(ch.getChapterNo());
            row.setState(state);
            worldStateMapper.upsert(row);
            log.info("世界状态账：novelId={} ch{} 已 upsert", novelId, ch.getChapterNo());
        } catch (Exception e) {
            log.warn("世界状态抽取失败（fail-open）：novelId={} ch{} 原因={}",
                    novelId, ch.getChapterNo(), e.getMessage());
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
