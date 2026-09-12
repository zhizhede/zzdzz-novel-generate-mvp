package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.CanonDocDAO;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.ForeshadowDAO;
import com.zzdzz.novelgen.dao.NovelDAO;
import com.zzdzz.novelgen.dao.PipelineEventDAO;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 卷纲规划 Agent：读大纲+四本账（已有卷纲/事实账/世界状态/伏笔账）→ 产出整卷规划行。
 * 闭环：生成 → 确定性结构校验 → AI 规划审校（BLOCKER 带清单重写，≤3 轮）→
 * auto 模式直接落库 / manual 模式返回草稿。落库时把引用到的 proposed 伏笔升为 planned、
 * planted 伏笔排期回收——伏笔自动提议由本 Agent 完成采纳闭环，不再等人工。
 */
@Service
public class VolumePlanService {

    private static final Logger log = LoggerFactory.getLogger(VolumePlanService.class);

    public record PlanRow(int chapterNo, String title, String goal, String hook, String timeNote,
                          List<FsRef> foreshadows, int budgetMin, int budgetMax) {}

    /** 伏笔引用：code 空 = 新伏笔（content 必填，采纳时自动建账）；action=plant 埋设 / recover 回收。 */
    public record FsRef(String code, String action, String content) {}

    public record PlanDraft(String arc, String brief, List<PlanRow> rows) {}

    public record AdoptResult(int chapters, List<String> adoptedForeshadows, List<String> warnings) {}

    public record PlanOutcome(String planMode, boolean adopted, PlanDraft draft, AdoptResult result) {}

    private record Replan(String title, String goal, String hook, String timeNote) {}

    private final LlmPort llm;
    private final LlmJson llmJson;
    private final ContextPackerService packer;
    private final ChapterDAO chapterDAO;
    private final ForeshadowDAO foreshadowDAO;
    private final NovelDAO novelDAO;
    private final CanonDocDAO canonDocDAO;
    private final StageLog stageLog;
    private final TuningService tuning;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public VolumePlanService(LlmPort llm, LlmJson llmJson, ContextPackerService packer,
                             ChapterDAO chapterDAO, ForeshadowDAO foreshadowDAO, NovelDAO novelDAO,
                             CanonDocDAO canonDocDAO, StageLog stageLog, TuningService tuning,
                             ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.llm = llm;
        this.llmJson = llmJson;
        this.packer = packer;
        this.chapterDAO = chapterDAO;
        this.foreshadowDAO = foreshadowDAO;
        this.novelDAO = novelDAO;
        this.canonDocDAO = canonDocDAO;
        this.stageLog = stageLog;
        this.tuning = tuning;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    // ===== 规划一卷 =====

    /**
     * 规划一卷：人只给卷号与起始章（结束章与种子大纲可省，AI 自决）。
     * auto 模式审校通过直接落库；manual 模式返回草稿等采纳。同步调用，约 2-10 分钟。
     */
    public PlanOutcome planVolume(long novelId, int volNo, int fromNo, Integer toNo, String seedOutline) {
        Integer maxText = chapterDAO.maxChapterWithText(novelId);
        if (maxText != null && fromNo <= maxText) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "起始章 " + fromNo + " 不得早于已有正文的最末章 " + maxText + "，卷纲必须接续正文之后");
        }
        if (toNo != null && toNo < fromNo) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结束章必须不小于起始章");
        }
        stageLog.emit(novelId, StageLog.Stage.VOLUME_PLAN, StageLog.Phase.START,
                Map.of("volNo", volNo, "from", fromNo, "to", Objects.toString(toNo, "auto")));
        PlanDraft draft = generateWithReview(novelId, volNo, fromNo, toNo, seedOutline);
        String planMode = novelDAO.findPlanMode(novelId);
        AdoptResult result = "auto".equals(planMode) ? adopt(novelId, volNo, draft) : null;
        stageLog.emit(novelId, StageLog.Stage.VOLUME_PLAN,
                result != null ? StageLog.Phase.ADOPTED : StageLog.Phase.DRAFT,
                Map.of("volNo", volNo, "arc", Objects.toString(draft.arc(), ""),
                        "chapters", draft.rows().size(),
                        "adoptedForeshadows", result == null ? List.of() : result.adoptedForeshadows()));
        return new PlanOutcome(planMode, result != null, draft, result);
    }

    /** manual 模式采纳：人工看过/改过的草稿，结构校验后直接落库（不再过 AI 审校）。 */
    public AdoptResult adoptDraft(long novelId, int volNo, PlanDraft draft) {
        if (draft.rows().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "草稿没有规划行");
        }
        int fromNo = draft.rows().get(0).chapterNo();
        int toNo = draft.rows().get(draft.rows().size() - 1).chapterNo();
        String structural = structuralCheck(draft, fromNo, toNo);
        if (structural != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "草稿结构不合规：" + structural);
        }
        return adopt(novelId, volNo, draft);
    }

    /** 生成+校验+审校闭环：结构校验与 AI 审校的失败原因统一喂回下一轮重写。轮数走 tuning。 */
    private PlanDraft generateWithReview(long novelId, int volNo, int fromNo, Integer toNo, String seedOutline) {
        String context = packer.packVolumePlan(novelId, fromNo, seedOutline);
        String feedback = "";
        int maxRounds = tuning.i("volume_plan_review_rounds", 3);
        for (int round = 1; round <= maxRounds; round++) {
            PlanDraft draft = askPlan(novelId, volNo, fromNo, toNo, context, feedback);
            String structural = structuralCheck(draft, fromNo, toNo);
            if (structural != null) {
                log.warn("卷纲第 {} 轮结构校验未过：{}", round, structural);
                feedback = "【结构校验】" + structural;
                continue;
            }
            String issues = reviewPlan(novelId, draft);
            if (issues != null) {
                log.warn("卷纲第 {} 轮 AI 审校 BLOCKER：{}", round, issues);
                feedback = "【规划审校 BLOCKER】" + issues;
                continue;
            }
            log.info("卷纲规划通过：第 {} 卷 {} 共 {} 章", volNo, draft.arc(), draft.rows().size());
            return draft;
        }
        throw new IllegalStateException("卷纲规划 3 轮未过结构校验/AI 审校，放弃落库（llm_call_log node=volume_plan 可回放）");
    }

    private PlanDraft askPlan(long novelId, int volNo, int fromNo, Integer toNo, String context, String feedback) {
        String span = toNo == null
                ? "章数 6-15 章由你定夺（决定本卷篇幅，在 no 字段连续编号体现）"
                : "到第 " + toNo + " 章结束，共 " + (toNo - fromNo + 1) + " 章";
        String user = """
                任务：规划第 %d 卷，从第 %d 章开始，%s。

                规划规则：
                1. brief 为 150-300 字卷简报，必须写清四个决策：本卷核心悬念与谜底展开节奏（人物身份/动机类问题的答案在本卷如何推进）、卷终点钩子（终章留给下一卷的最大悬念）、伏笔取舍（哪些回收、哪些继续悬置及理由）、节奏曲线（紧张章与舒缓章如何分布）。
                2. chapters.no 从 %d 开始连续编号；title 不超过 12 字；goal 100-200 字且按戏剧结构写四件套——欲望（本章谁想要什么）、阻碍（什么在阻止）、转折（章内如何升级或翻转）、情绪落点，供下游场景拆解器使用；hook 为一句话章末钩子；time_note 为本章距上一章的故事时间跨度（如「紧接」「次日清晨」「三天后」，不得与时间线矛盾）。
                3. foreshadows 只列本章要「埋设」或「回收」的伏笔：账本中 proposed/planned 的编码被引用即排期埋设，planted 的被引用即安排回收（action=recover）；账本里没有的新伏笔省略 code、必须给 content（一句话）且 action=plant，将自动建账；已 recovered 的不要引用（旧线呼应写进 goal 即可）；与本章无关的不要列。
                4. budget_min/budget_max 为单章字数预算，参考往卷实际水平 2800-4000。
                5. 卷尾必须留下强钩子；不得与已有卷纲重复桥段。

                只输出 JSON，格式：
                {"arc":"卷名（8字内）","brief":"…","chapters":[{"no":%d,"title":"…","goal":"…","hook":"…","time_note":"…","foreshadows":[{"code":"F4","action":"recover","content":""},{"code":"","action":"plant","content":"新伏笔一句话"}],"budget_min":2400,"budget_max":3400}]}
                字符串值内部禁止英文双引号，引用一律用「」。

                %s
                """.formatted(volNo, fromNo, span, fromNo, fromNo, context);
        return llmJson.ask(new LlmPort.ChatRequest(LlmNode.VOLUME_PLAN, novelId, null,
                        List.of(LlmPort.Message.system("你是网文主编，负责整卷卷纲规划。只输出合法 JSON，不要任何解释或 markdown 代码块。"
                                        + "字符串值内部禁止英文双引号，引用一律用「」。"),
                                LlmPort.Message.user(user)),
                        0.6),
                node -> {
                    String arc = node.path("arc").asText("");
                    String brief = node.path("brief").asText("");
                    JsonNode arr = node.path("chapters");
                    if (arc.isBlank()) throw new LlmJson.Bad("arc 为空");
                    if (brief.isBlank()) throw new LlmJson.Bad("brief 为空");
                    if (!arr.isArray() || arr.isEmpty()) throw new LlmJson.Bad("chapters 必须是非空数组");
                    List<PlanRow> rows = new ArrayList<>();
                    for (JsonNode c : arr) {
                        int no = c.path("no").asInt(0);
                        String title = c.path("title").asText("");
                        String goal = c.path("goal").asText("");
                        if (no <= 0 || title.isBlank() || goal.isBlank()) {
                            throw new LlmJson.Bad("chapters 存在缺 no/title/goal 的行");
                        }
                        List<FsRef> fss = new ArrayList<>();
                        for (JsonNode f : c.path("foreshadows")) {
                            if (f == null || !f.isObject()) continue;
                            String code = f.path("code").asText("").strip();
                            String content = f.path("content").asText("").strip();
                            if (code.isEmpty() && content.isEmpty()) continue;
                            String action = f.path("action").asText("plant").strip().toLowerCase();
                            fss.add(new FsRef(code, action, content));
                        }
                        String timeNote = c.path("time_note").asText("");
                        rows.add(new PlanRow(no, title, goal, c.path("hook").asText(""),
                                timeNote.isBlank() ? null : timeNote, fss,
                                c.path("budget_min").asInt(2400), c.path("budget_max").asInt(3400)));
                    }
                    return new PlanDraft(arc, brief, rows);
                }, 2);
    }

    /** 确定性结构校验：章号连续、章数、字段非空、预算区间。返回 null 即通过。 */
    private String structuralCheck(PlanDraft draft, int fromNo, Integer toNo) {
        List<PlanRow> rows = draft.rows();
        if (toNo != null && rows.size() != toNo - fromNo + 1) {
            return "章数应为 " + (toNo - fromNo + 1) + "，实际 " + rows.size();
        }
        if (toNo == null && (rows.size() < 6 || rows.size() > 15)) {
            return "章数须 6-15，实际 " + rows.size();
        }
        for (int i = 0; i < rows.size(); i++) {
            PlanRow r = rows.get(i);
            if (r.chapterNo() != fromNo + i) {
                return "章号必须从 " + fromNo + " 连续递增，第 " + (i + 1) + " 行却是 " + r.chapterNo();
            }
            if (r.goal() == null || r.goal().isBlank()) {
                return "第 " + r.chapterNo() + " 章 goal 为空";
            }
            if (r.budgetMin() < 600 || r.budgetMax() > 10000 || r.budgetMin() > r.budgetMax()) {
                return "第 " + r.chapterNo() + " 章字数预算不合理：" + r.budgetMin() + "-" + r.budgetMax();
            }
            for (FsRef ref : r.foreshadows()) {
                boolean noCode = ref.code() == null || ref.code().isBlank();
                boolean noContent = ref.content() == null || ref.content().isBlank();
                if (noCode && noContent) {
                    return "第 " + r.chapterNo() + " 章存在 code/content 双空的伏笔引用";
                }
                if (ref.action() != null && !"plant".equals(ref.action()) && !"recover".equals(ref.action())) {
                    return "第 " + r.chapterNo() + " 章伏笔 action 只支持 plant/recover：" + ref.action();
                }
            }
        }
        return null;
    }

    /**
     * AI 规划审校：对照账本查连续性/重复/伏笔悬空/节奏。返回 null 即 PASS，否则 BLOCKER 清单文本。
     * 审校调用异常 fail-open（放行）——规划行没有不可逆下游，正文生成端还有已验证的审校管线兜底。
     */
    private String reviewPlan(long novelId, PlanDraft draft) {
        StringBuilder plan = new StringBuilder("卷名：").append(draft.arc()).append("\n卷简报：").append(draft.brief()).append('\n');
        for (PlanRow r : draft.rows()) {
            plan.append("第").append(r.chapterNo()).append("章《").append(r.title())
                    .append("》目标：").append(r.goal())
                    .append(" 钩子：").append(Objects.toString(r.hook(), ""))
                    .append(" 时间：").append(Objects.toString(r.timeNote(), "紧接"))
                    .append(" 伏笔：").append(r.foreshadows().isEmpty() ? "无" : renderRefs(r.foreshadows()))
                    .append('\n');
        }
        String user = """
                【待审卷纲】
                %s
                【对照材料（人物设定卡 + 账本）】
                %s
                %s
                审校清单：① 连续性——是否与世界观/人物卡/世界状态/事实账矛盾（人物已死复活、物品凭空转移、时间倒流、凭空发明人物卡与账本中不存在的人名）；
                ② 重复——卷内相邻章目标是否雷同、是否与往卷炒冷饭；③ 伏笔——planted 未回收项是否被安排回收或给出悬置理由、proposed 取舍是否合理；
                ④ 节奏——张弛是否有曲线、卷尾钩子是否成立。
                只输出 JSON：{"verdict":"PASS"或"BLOCKER","issues":["问题（指明章号）"]}
                存在必须修复的硬伤才 BLOCKER；风格偏好类意见写进 issues 但给 PASS。
                """.formatted(plan, packer.characters(novelId),
                packer.packLedgers(novelId, draft.rows().get(0).chapterNo()));
        try {
            return llmJson.ask(new LlmPort.ChatRequest(LlmNode.VOLUME_PLAN_REVIEW, novelId, null,
                            List.of(LlmPort.Message.system("你是网文规划审校员，在卷纲落库前把关。只输出合法 JSON。"
                                            + "字符串值内部禁止英文双引号，引用一律用「」。"),
                                    LlmPort.Message.user(user)),
                            0.2),
                    node -> {
                        String verdict = node.path("verdict").asText("PASS").strip().toUpperCase();
                        if ("BLOCKER".equals(verdict)) {
                            List<String> issues = new ArrayList<>();
                            for (JsonNode i : node.path("issues")) {
                                String t = i.asText("").strip();
                                if (!t.isBlank()) issues.add(t);
                            }
                            if (issues.isEmpty()) return null; // BLOCKER 却说不出问题，视为 PASS
                            return String.join("；", issues);
                        }
                        return null;
                    }, 2);
        } catch (Exception e) {
            log.warn("卷纲审校调用异常，fail-open 放行：{}", e.getMessage());
            stageLog.emit(novelId, StageLog.Stage.VOLUME_PLAN_REVIEW, StageLog.Phase.ERROR,
                    Map.of("message", String.valueOf(e.getMessage())));
            return null;
        }
    }

    // ===== 落库与伏笔采纳 =====

    /** 落库：软删 fromNo 起旧规划行（前置校验保证无正文）→ 插入新行 → 伏笔采纳/建账 → 卷简报存 canon。 */
    private AdoptResult adopt(long novelId, int volNo, PlanDraft draft) {
        int fromNo = draft.rows().get(0).chapterNo();
        List<String> warnings = new ArrayList<>();
        List<String> adopted = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (ChapterDO c : chapterDAO.listSummariesByNovel(novelId)) {
                if (c.chapterNo() >= fromNo) {
                    if (c.fullText() != null && !c.fullText().isBlank()) {
                        throw new BizException(ErrorCode.PARAM_ERROR,
                                "第 " + c.chapterNo() + " 章已有正文，禁止覆盖其规划行");
                    }
                    chapterDAO.softDeletePlan(c.id());
                }
            }
            // 先逐章解析伏笔引用（可能自动建账），再插规划行——refs 写最终编码，下游指令查询才有据
            Map<Integer, List<String>> refsByRow = new LinkedHashMap<>();
            for (PlanRow r : draft.rows()) {
                List<String> codes = new ArrayList<>();
                for (FsRef ref : r.foreshadows()) {
                    String finalCode = resolveRef(novelId, ref, r.chapterNo(), adopted, warnings);
                    if (finalCode != null && !codes.contains(finalCode)) codes.add(finalCode);
                }
                refsByRow.put(r.chapterNo(), codes);
            }
            for (PlanRow r : draft.rows()) {
                chapterDAO.insertPlan(novelId, r.chapterNo(), volNo, draft.arc(), r.title(), r.goal(),
                        r.hook(), r.timeNote(), "[]", jsonRefs(refsByRow.get(r.chapterNo())),
                        r.budgetMin(), r.budgetMax());
            }
            upsertBrief(novelId, volNo, draft);
        });
        if (!warnings.isEmpty()) log.warn("卷纲落库告警：{}", warnings);
        log.info("第 {} 卷卷纲落库：{} 章，伏笔动态 {}", volNo, draft.rows().size(), adopted);
        return new AdoptResult(draft.rows().size(), adopted, warnings);
    }

    /**
     * 单条伏笔引用落账：proposed → 采纳排期（planned）；planted 未定回收 → 排期回收；
     * 不存在的编码（有 content）→ 自动建新账（planned）；无 content 的坏引用 → 忽略并告警。
     */
    private String resolveRef(long novelId, FsRef ref, int chapterNo,
                              List<String> adopted, List<String> warnings) {
        boolean hasCode = ref.code() != null && !ref.code().isBlank();
        boolean hasContent = ref.content() != null && !ref.content().isBlank();
        String action = "recover".equals(ref.action()) ? "回收" : "埋设";
        if (hasCode) {
            ForeshadowDO f = foreshadowDAO.findByCode(novelId, ref.code());
            if (f != null) {
                if ("proposed".equals(f.status())) {
                    foreshadowDAO.promoteProposal(f.id(), chapterNo);
                    adopted.add(ref.code() + "（采纳，第" + chapterNo + "章" + action + "）");
                } else if ("planted".equals(f.status()) && f.recoveredIn() == null
                        && "recover".equals(ref.action())) {
                    foreshadowDAO.scheduleRecovery(f.id(), chapterNo);
                    adopted.add(ref.code() + "（排期回收，第" + chapterNo + "章）");
                }
                return ref.code();
            }
            if (!hasContent) {
                warnings.add("第" + chapterNo + "章引用了不存在的伏笔编码 " + ref.code() + "（未给内容），已忽略");
                return null;
            }
            // 编码不存在但给了内容：按新伏笔建账，优先沿用给定编码
            return createForeshadow(novelId, ref.code(), ref.content(), chapterNo, adopted);
        }
        return createForeshadow(novelId, null, ref.content(), chapterNo, adopted);
    }

    /** 新伏笔建账：给过编码且形如 F数字 则沿用，否则取下一可用号；同内容去重。 */
    private String createForeshadow(long novelId, String wantedCode, String content, int chapterNo,
                                    List<String> adopted) {
        if (foreshadowDAO.contentExists(novelId, content)) {
            for (ForeshadowDO f : foreshadowDAO.listByNovel(novelId)) {
                if (f.content().equals(content)) return f.code();
            }
        }
        String code = wantedCode != null && wantedCode.matches("F\\d+")
                && foreshadowDAO.findByCode(novelId, wantedCode) == null ? wantedCode : foreshadowDAO.nextCode(novelId);
        foreshadowDAO.insertPlanned(novelId, code, content, chapterNo);
        adopted.add(code + "（新建账，第" + chapterNo + "章埋设：" + content + "）");
        return code;
    }

    private String renderRefs(List<FsRef> refs) {
        StringJoiner sj = new StringJoiner("、");
        for (FsRef ref : refs) {
            boolean noCode = ref.code() == null || ref.code().isBlank();
            sj.add(noCode ? "新埋：" + ref.content() : ref.code());
        }
        return sj.toString();
    }

    /** 卷简报存 canon（kind=misc），素材库与后续规划可查可改。 */
    private void upsertBrief(long novelId, int volNo, PlanDraft draft) {
        String name = "卷" + volNo + "简报";
        String content = "【卷名】" + draft.arc() + "\n\n" + draft.brief();
        Long id = canonDocDAO.findId(novelId, "misc", name);
        if (id == null) {
            canonDocDAO.insert(novelId, "misc", name, content);
        } else {
            canonDocDAO.updateContent(id, content);
        }
    }

    private String jsonRefs(List<String> codes) {
        try {
            return mapper.writeValueAsString(codes);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ===== 单章卷纲重写（失败自愈 / 人工纠偏共用） =====

    /** 换一条可行路径重写该章 title/goal/hook/time_note（预算沿用），并重置章纲待重出。 */
    public ChapterDO replanChapter(long novelId, int chapterNo, String failureReason) {
        ChapterDO ch = chapterDAO.find(novelId, chapterNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterNo));
        if (ch.fullText() != null && !ch.fullText().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "第 " + chapterNo + " 章已有正文，禁止重写其卷纲");
        }
        String user = """
                任务：第 %d 章《%s》按现有卷纲目标生成反复失败，需要换一个写法。失败原因：
                %s
                现目标：%s
                现钩子：%s
                请重写该章的 title/goal/hook/time_note：目标必须换一条可行路径完成本章在卷中的使命（可改事件、改场景、改信息揭示顺序），不得与相邻章（第 %d、%d 章）目标雷同。
                只输出 JSON：{"title":"…","goal":"…","hook":"…","time_note":"…"}
                字符串值内部禁止英文双引号，引用一律用「」。

                %s

                【账本上下文】
                %s
                """.formatted(chapterNo, Objects.toString(ch.title(), ""), failureReason,
                Objects.toString(ch.goal(), ""), Objects.toString(ch.hook(), ""),
                chapterNo - 1, chapterNo + 1, packer.characters(novelId),
                packer.packLedgers(novelId, chapterNo));
        Replan replan = llmJson.ask(new LlmPort.ChatRequest(LlmNode.CHAPTER_REPLAN, novelId, ch.id(),
                        List.of(LlmPort.Message.system("你是网文主编，只输出合法 JSON，字符串内禁英文双引号，引用一律用「」。"),
                                LlmPort.Message.user(user)),
                        0.6),
                node -> {
                    String title = node.path("title").asText("");
                    String goal = node.path("goal").asText("");
                    if (title.isBlank() || goal.isBlank()) throw new LlmJson.Bad("title/goal 为空");
                    String timeNote = node.path("time_note").asText("");
                    return new Replan(title, goal, node.path("hook").asText(""),
                            timeNote.isBlank() ? null : timeNote);
                }, 2);
        chapterDAO.updatePlan(ch.id(), ch.volumeNo(), ch.arc(),
                replan.title(), replan.goal(),
                replan.hook().isBlank() ? ch.hook() : replan.hook(),
                replan.timeNote() == null ? ch.timeNote() : replan.timeNote(),
                ch.budgetMin(), ch.budgetMax());
        // 清场景与门禁报告：章纲将按新目标重出（runChapter 见场景数为 0 自动重生成）
        chapterDAO.resetForReoutline(ch.id(), null);
        stageLog.emit(novelId, chapterNo, StageLog.Stage.VOLUME_PLAN, StageLog.Phase.CHAPTER_REPLAN,
                Map.of("goal", replan.goal()));
        log.info("第 {} 章卷纲已重写：{}", chapterNo, replan.goal());
        return chapterDAO.findById(ch.id()).orElseThrow();
    }
}
