package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.SceneDO;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.NovelDAO;
import com.zzdzz.novelgen.dao.SceneDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import static com.zzdzz.novelgen.service.StageLog.Phase.*;
import static com.zzdzz.novelgen.service.StageLog.Stage.*;

/**
 * 单章管线编排。步骤清单见 {@link Step}（章纲→场景→拼章门禁→读者评审→AI 审校→审批→digest），
 * 每步一个 step 方法，runChapter 只做顺序编排与卫语句跳转。
 * 自愈梯子在 {@link #runChapterWithHeal}：直跑 → 重试 → 换目标重写。
 * 场景级缓存：已物化章纲/已过门禁场景的章可断点续跑。
 */
@Service
public class ChapterPipelineService {

    private static final Logger log = LoggerFactory.getLogger(ChapterPipelineService.class);

    private final NovelDAO novelDAO;
    private final ChapterDAO chapterDAO;
    private final SceneDAO sceneDAO;
    private final OutlineService outlineService;
    private final ContextPackerService packer;
    private final SceneService sceneService;
    private final GateService gateService;
    private final DigestService digestService;
    private final ReviewService reviewService;
    private final VolumePlanService volumePlanService;
    private final LlmPort llm;
    private final StageLog stageLog;
    private final TuningService tuning;

    public ChapterPipelineService(NovelDAO novelDAO, ChapterDAO chapterDAO,
                                  SceneDAO sceneDAO, OutlineService outlineService,
                                  ContextPackerService packer, SceneService sceneService,
                                  GateService gateService, DigestService digestService,
                                  ReviewService reviewService, VolumePlanService volumePlanService,
                                  LlmPort llm, StageLog stageLog, TuningService tuning) {
        this.novelDAO = novelDAO;
        this.chapterDAO = chapterDAO;
        this.sceneDAO = sceneDAO;
        this.outlineService = outlineService;
        this.packer = packer;
        this.sceneService = sceneService;
        this.gateService = gateService;
        this.digestService = digestService;
        this.reviewService = reviewService;
        this.volumePlanService = volumePlanService;
        this.llm = llm;
        this.stageLog = stageLog;
        this.tuning = tuning;
    }

    /** 进度回调：队列服务据此回写任务进度；shouldStop 支持运行中协作取消（章与章之间检查）。 */
    public interface ProgressSink {
        void onProgress(int doneChapters, int nextChapter, String message);

        boolean shouldStop();
    }

    /** 兼容旧入口：按作品标题连跑，无进度回调。 */
    public int runChapters(String novelTitle, int from, int to) {
        Long novelId = novelDAO.findIdByTitle(novelTitle);
        if (novelId == null) throw new IllegalStateException("作品不存在: " + novelTitle);
        return runChapters(novelId, novelTitle, from, to, null);
    }

    /** 连跑 [from, to] 章；返回通过章数。任一章失败/异常即停止（状态保留，断点重跑）。 */
    public int runChapters(long novelId, String novelTitle, int from, int to, ProgressSink sink) {
        String mode = novelDAO.findApprovalMode(novelId);
        log.info("连跑开始 {} 第 {}–{} 章（审批模式 {}）", novelTitle, from, to, mode);

        int okChapters = 0;
        for (int no = from; no <= to; no++) {
            if (sink != null && sink.shouldStop()) {
                log.warn("连跑被取消（已完成 {} 章）", okChapters);
                break;
            }
            long t0 = System.currentTimeMillis();
            try {
                if (runChapterWithHeal(novelId, no, mode)) {
                    okChapters++;
                    log.info("=== 第 {} 章完成，耗时 {}s ===", no, (System.currentTimeMillis() - t0) / 1000);
                } else {
                    log.error("=== 第 {} 章自愈后仍失败，停止连跑（已完成 {} 章）===", no, okChapters);
                    break;
                }
            } catch (Exception e) {
                log.error("=== 第 {} 章异常：{}；状态保留，可断点重跑 ===", no, e.getMessage());
                chapterDAO.updateStatusByNo(novelId, no, "FAILED");
                break;
            }
            if (sink != null) {
                sink.onProgress(okChapters, Math.min(no + 1, to), "第 " + no + " 章完成");
            }
        }
        log.info("连跑结束：{}/{} 章通过", okChapters, to - from + 1);
        return okChapters;
    }

    /**
     * 失败自愈梯子：直跑 → 直接重试 N1 次（瞬态故障）→ 规划 Agent 换目标重写该章卷纲再试 N2 次 → 放弃转人工。
     * 次数走 tuning（heal_retry_times / heal_replan_times）；每次尝试共享场景级断点缓存。
     */
    private boolean runChapterWithHeal(long novelId, int chapterNo, String approvalMode) {
        if (attemptChapter(novelId, chapterNo, approvalMode)) return true;
        int retries = tuning.i("heal_retry_times", 1);
        for (int r = 1; r <= retries; r++) {
            stageLog.emit(novelId, chapterNo, HEAL, RETRY,
                    Map.of("message", retries == 1 ? "第一次失败，自动重试"
                            : "失败，自动重试（第 " + r + "/" + retries + " 次）"));
            log.warn("第 {} 章失败，自愈：直接重试（{}/{}）", chapterNo, r, retries);
            if (attemptChapter(novelId, chapterNo, approvalMode)) return true;
        }
        String reason = failureReason(novelId, chapterNo);
        stageLog.emit(novelId, chapterNo, HEAL, REPLAN,
                Map.of("message", "重试仍败，重写卷纲目标后再试", "reason", reason));
        log.warn("第 {} 章重试仍败，自愈：重写卷纲目标后再试一次（原因：{}）", chapterNo, reason);
        volumePlanService.replanChapter(novelId, chapterNo, reason);
        int replans = tuning.i("heal_replan_times", 1);
        for (int r = 0; r < replans; r++) {
            if (attemptChapter(novelId, chapterNo, approvalMode)) return true;
        }
        return false;
    }

    private boolean attemptChapter(long novelId, int chapterNo, String approvalMode) {
        try {
            return runChapter(novelId, chapterNo, approvalMode);
        } catch (Exception e) {
            log.error("第 {} 章尝试异常：{}", chapterNo, e.getMessage());
            chapterDAO.updateStatusByNo(novelId, chapterNo, "FAILED");
            return false;
        }
    }

    /** 自愈用失败原因：优先取最近一次门禁失败清单，取不到给兜底文案。 */
    private String failureReason(long novelId, int chapterNo) {
        try {
            ChapterDO ch = chapterDAO.find(novelId, chapterNo).orElse(null);
            if (ch != null) {
                String s = gateService.failedChecksText(ch.id());
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Exception ignore) {
            // 门禁报告缺失不影响自愈流程
        }
        return "生成异常或审校未过（详见 gate_reports / llm_call_log）";
    }

    // ===== Web 触发已迁移至 GenerationQueueService（DB 队列 + 单 worker 异步执行） =====

    /** 强制重出章纲：清掉旧场景与门禁报告，按当前卷纲目标/大纲/前情重新生成场景拆解（同步调用，约 1-2 分钟）。 */
    public List<OutlineService.SceneSpec> regenerateOutline(long novelId, int chapterNo) {
        ChapterDO ch = outlineService.loadChapter(novelId, chapterNo);
        if (ch.fullText() != null && !ch.fullText().isBlank()) {
            throw new IllegalStateException("第 " + chapterNo + " 章已有正文，禁止重出章纲");
        }
        List<String> digests = packer.recentDigests(novelId, chapterNo, 3);
        outlineService.generate(novelId, ch, packer.world(novelId), packer.characters(novelId),
                packer.foreshadowDirectives(novelId, chapterNo), digests,
                packer.prevTail(novelId, chapterNo));
        return outlineService.loadSpecs(ch.id());
    }

    /** 人工审批：仅 PENDING_APPROVAL 可过审；过审即生成 digest。 */
    public void approve(long chapterId) {        ChapterDO ch = chapterDAO.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("章不存在: " + chapterId));
        if (!"PENDING_APPROVAL".equals(ch.status())) {
            throw new IllegalStateException("章 " + chapterId + " 状态为 " + ch.status() + "，不在待审批");
        }
        digestService.digest(ch.novelId(), ch.id(), ch.chapterNo(), ch.fullText());
        chapterDAO.updateStatus(ch.id(), "APPROVED");
    }

    /** 模型偶发把章题当正文首行（无 # 前缀，cleanDraft 剥不掉）：拼章与修订后各剥一次。 */
    static String stripTitleLine(String fullText, String title) {
        if (fullText == null || fullText.isBlank() || title == null || title.isBlank()) {
            return fullText;
        }
        String[] parts = fullText.split("\n", 2);
        String first = parts[0].strip();
        if (first.equals(title) || first.replaceAll("[。．.!！?？]", "").equals(title)) {
            return parts.length > 1 ? parts[1].strip() : "";
        }
        return fullText;
    }

    /** 章级机械门禁未过时的修订轮：外科手术式——只修被点名的指标，不动情节与分行节奏。 */
    private String reviseChapter(long novelId, ChapterDO ch, String fullText) {
        String feedback = gateService.failureSummary(ch.id());
        int curWords = ((Number) GateService.computeMetrics(fullText).get("cjk")).intValue();
        int cap = (int) (ch.budgetMax() * 1.05);
        // 严重超长时 ±10% 的温和约束数学上救不回来，改为给明确压缩目标
        String lengthRule = curWords > cap
                ? "当前正文约 %d 字，超出预算上限：请把篇幅压缩到 %d–%d 字（删冗余描写与重复信息，情节与对白全保留）"
                        .formatted(curWords, ch.budgetMin(), cap)
                : "总字数变化控制在 ±10%% 内，且不得超过 %d 字".formatted(cap);
        String user = """
                任务：修订第 %d 章全文。门禁检测出以下问题：
                %s
                要求：只针对被点名的问题做最小修改（例如破折号超标：把「——」改写为逗号、句号、拆句或直接删除）；
                除被点名的指标外，其余风格特征必须原样保留——破折号「——」与省略号「……」的数量不得增加，分行节奏不得重排；
                严禁改动情节、人物与对话内容；%s。
                直接输出修订后的完整正文，不要输出思考过程。

                【第 %d 章全文（在此版本上修改）】
                %s
                """.formatted(ch.chapterNo(), feedback, lengthRule, ch.chapterNo(), fullText);
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                LlmNode.CHAPTER_REVISE, novelId, ch.id(),
                List.of(LlmPort.Message.system("你是执行门禁修订的网文编辑，只做被点名的最小修改。"),
                        LlmPort.Message.user(user)),
                0.5));
        return SceneService.cleanDraft(r.content());
    }

    /** 单章管线步骤清单：一个枚举看全流程（新增步骤=加一行+一个 step 方法），亦作 Tuning 调参作用域键。 */
    enum Step {
        OUTLINE("AI 章纲"),
        SCENES("逐场景生成+场景门禁"),
        ASSEMBLE_GATE("拼章+章级门禁"),
        READER_REVIEW("读者评审"),
        AI_REVIEW("AI 语义审校"),
        APPROVE("审批"),
        DIGEST("事实账");

        final String label;

        Step(String label) { this.label = label; }
    }

    private boolean runChapter(long novelId, int chapterNo, String approvalMode) {
        ChapterDO ch = outlineService.loadChapter(novelId, chapterNo);
        stageLog.emit(novelId, chapterNo, CHAPTER, START, Map.of("title", String.valueOf(ch.title())));

        outlineStep(novelId, ch);                            // 1) AI 章纲
        if (!scenesStep(novelId, ch)) return false;          // 2) 逐场景生成+门禁
        String fullText = assembleGateStep(novelId, ch);     // 3) 拼章+章级门禁
        if (fullText == null) return false;

        ReviewStepResult reader = reviewStep(novelId, ch, fullText, StageLog.Stage.READER);   // 3.4
        fullText = reader.fullText();
        if (reader.pending()) return true;
        ReviewStepResult review = reviewStep(novelId, ch, fullText, StageLog.Stage.REVIEW);   // 3.5
        fullText = review.fullText();
        if (review.pending()) return true;

        // 4) 审批（auto 直过；manual 停在 PENDING_APPROVAL 等人）
        if ("manual".equals(approvalMode)) {
            chapterDAO.updateStatus(ch.id(), "PENDING_APPROVAL");
            log.info("第 {} 章待人工审批", chapterNo);
            stageLog.emit(novelId, chapterNo, APPROVE, PENDING, Map.of());
            return true;
        }

        // 5) digest
        digestService.digest(novelId, ch.id(), chapterNo, fullText);
        stageLog.emit(novelId, chapterNo, DIGEST, DONE, Map.of());
        stageLog.emit(novelId, chapterNo, CHAPTER, DONE, Map.of("chars", fullText.length()));
        return true;
    }

    /** 步骤 1：AI 章纲。已物化则跳过（场景级断点续跑）。 */
    private void outlineStep(long novelId, ChapterDO ch) {
        if (sceneDAO.countByChapter(ch.id()) > 0) return;
        int chapterNo = ch.chapterNo();
        stageLog.emit(novelId, chapterNo, OUTLINE, START, Map.of());
        List<String> digests = packer.recentDigests(novelId, chapterNo, 3);
        outlineService.generate(novelId, ch, packer.world(novelId), packer.characters(novelId),
                packer.foreshadowDirectives(novelId, chapterNo), digests,
                packer.prevTail(novelId, chapterNo));
        stageLog.emit(novelId, chapterNo, OUTLINE, DONE,
                Map.of("sceneCount", outlineService.loadSpecs(ch.id()).size()));
    }

    /** 步骤 2：逐场景生成 + 场景门禁（带意见重写≤2 轮）；已 PASSED 场景直接复用。全过返回 true。 */
    private boolean scenesStep(long novelId, ChapterDO ch) {
        int chapterNo = ch.chapterNo();
        var specs = outlineService.loadSpecs(ch.id());
        Map<Integer, SceneDO> doneScenes = sceneDAO.findByChapter(ch.id()).stream()
                .collect(Collectors.toMap(SceneDO::sceneNo, s -> s));
        List<String> digests = packer.recentDigests(novelId, chapterNo, 3);
        String prevTail = packer.prevTail(novelId, chapterNo);
        List<String> directives = packer.foreshadowDirectives(novelId, chapterNo);
        int passedScenes = 0;
        String prevScene = null;
        for (var spec : specs) {
            SceneDO done = doneScenes.get(spec.sceneNo());
            if (done != null && "PASSED".equals(done.gateStatus())) {
                passedScenes++;
                prevScene = done.draftText();
                stageLog.emit(novelId, chapterNo, SCENE, REUSED,
                        Map.of("sceneNo", spec.sceneNo(), "text", String.valueOf(done.draftText())));
                continue;
            }
            Long sceneId = sceneDAO.findId(ch.id(), spec.sceneNo());
            var pack = packer.packScene(novelId, chapterNo, ch, spec, digests, prevTail, directives, prevScene);
            stageLog.emit(novelId, chapterNo, SCENE, START,
                    Map.of("sceneNo", spec.sceneNo(), "goal", String.valueOf(spec.goal())));
            String draft = sceneService.generate(novelId, ch.id(), chapterNo, pack, spec.sceneNo());
            stageLog.emit(novelId, chapterNo, SCENE, DRAFT,
                    Map.of("sceneNo", spec.sceneNo(), "text", String.valueOf(draft)));
            boolean ok = gateService.checkScene(novelId, ch.id(), sceneId, spec.sceneNo(), draft, spec.words());
            // 带意见重写（密度类指标一轮修订常按下葫芦浮起瓢），轮数走 tuning
            int maxRewrites = tuning.i("scene_revise_rounds", 2);
            for (int round = 1; round <= maxRewrites && !ok; round++) {
                log.warn("场景 {} 门禁未过，带意见重写（第 {} 轮）", spec.sceneNo(), round);
                stageLog.emit(novelId, chapterNo, SCENE_GATE, NONE,
                        Map.of("sceneNo", spec.sceneNo(), "passed", false, "rewrite", true, "round", round,
                                "reason", gateService.failedChecksText(ch.id(), sceneId)));
                draft = sceneService.revise(novelId, ch.id(), sceneId, spec.sceneNo(),
                        draft, gateService.failureSummary(ch.id(), sceneId), pack);
                stageLog.emit(novelId, chapterNo, SCENE, DRAFT,
                        Map.of("sceneNo", spec.sceneNo(), "text", String.valueOf(draft)));
                ok = gateService.checkScene(novelId, ch.id(), sceneId, spec.sceneNo(), draft, spec.words());
            }
            stageLog.emit(novelId, chapterNo, SCENE_GATE, NONE,
                    Map.of("sceneNo", spec.sceneNo(), "passed", ok,
                            "reason", ok ? "" : gateService.failedChecksText(ch.id(), sceneId)));
            sceneDAO.updateGateStatus(sceneId, ok ? "PASSED" : "FAILED");
            if (ok) passedScenes++;
            prevScene = draft;
        }
        if (passedScenes < specs.size()) {
            log.error("第 {} 章场景通过 {}/{}，中止", chapterNo, passedScenes, specs.size());
            stageLog.emit(novelId, chapterNo, CHAPTER, FAILED,
                    Map.of("reason", "场景通过 " + passedScenes + "/" + specs.size()));
            chapterDAO.updateStatus(ch.id(), "FAILED");
            return false;
        }
        return true;
    }

    /** 步骤 3：拼章 + 章级门禁（复用已修订正文或带意见修订≤2 轮）。返回过检正文；失败返回 null。 */
    private String assembleGateStep(long novelId, ChapterDO ch) {
        int chapterNo = ch.chapterNo();
        StringJoiner joiner = new StringJoiner("\n\n");
        sceneDAO.findPassedDrafts(ch.id()).forEach(joiner::add);
        String fullText = stripTitleLine(joiner.toString(), ch.title());
        chapterDAO.saveFullText(ch.id(), fullText);
        chapterDAO.updateStatus(ch.id(), "GATE_MECHANICAL");
        stageLog.emit(novelId, chapterNo, ASSEMBLE, NONE, Map.of("chars", fullText.length()));
        if (gateService.checkChapter(novelId, ch.id(), chapterNo, fullText, ch.budgetMin(), ch.budgetMax())) {
            stageLog.emit(novelId, chapterNo, CHAPTER_GATE, NONE, Map.of("passed", true));
            return fullText;
        }
        stageLog.emit(novelId, chapterNo, CHAPTER_GATE, NONE,
                Map.of("passed", false, "reason", gateService.failedChecksText(ch.id())));
        // 断点重跑场景：库里已有上一轮修订过、且能过检的正文，直接复用，省一轮修订调用
        String stored = ch.fullText();
        if (stored != null && !stored.equals(fullText)
                && gateService.checkChapter(novelId, ch.id(), chapterNo, stored, ch.budgetMin(), ch.budgetMax())) {
            log.info("第 {} 章复用已修订正文（{} 字符）", chapterNo, stored.length());
            stageLog.emit(novelId, chapterNo, REVISE, REUSE, Map.of("chars", stored.length()));
            chapterDAO.saveFullText(ch.id(), stored);
            stageLog.emit(novelId, chapterNo, CHAPTER_GATE, NONE, Map.of("passed", true));
            return stored;
        }
        // 修订迭代制：单轮修订常「修甲伤乙」（补对话密度时狂加破折号、撑爆字数），
        // 最多 N 轮（tuning），每轮以最新失败清单喂回；修订稿长度异常直接弃用本轮防风格雪崩
        int maxRounds = tuning.i("chapter_revise_rounds", 2);
        double lenMin = tuning.d("chapter_revise_len_min", 0.5);
        double lenMax = tuning.d("chapter_revise_len_max", 1.15);
        String failReason = "修订后门禁仍未过";
        for (int round = 1; round <= maxRounds; round++) {
            log.warn("第 {} 章章级门禁未过，带意见修订（第 {}/{} 轮）", chapterNo, round, maxRounds);
            stageLog.emit(novelId, chapterNo, REVISE, START, Map.of("round", round));
            String revised = reviseChapter(novelId, ch, fullText);
            if (revised == null || revised.length() < fullText.length() * lenMin
                    || revised.length() > fullText.length() * lenMax) {
                log.error("第 {} 章修订稿长度异常（{} 字符），弃用本轮", chapterNo,
                        revised == null ? 0 : revised.length());
                stageLog.emit(novelId, chapterNo, REVISE, REJECTED, Map.of("round", round));
                failReason = "修订稿长度异常";
                continue;
            }
            fullText = stripTitleLine(revised, ch.title());
            stageLog.emit(novelId, chapterNo, REVISE, DONE,
                    Map.of("round", round, "chars", fullText.length()));
            chapterDAO.saveFullText(ch.id(), fullText);
            if (gateService.checkChapter(novelId, ch.id(), chapterNo, fullText, ch.budgetMin(), ch.budgetMax())) {
                stageLog.emit(novelId, chapterNo, CHAPTER_GATE, NONE, Map.of("passed", true));
                return fullText;
            }
        }
        log.error("第 {} 章修订两轮后仍未过章级门禁（报告见 gate_reports）", chapterNo);
        stageLog.emit(novelId, chapterNo, CHAPTER, FAILED, Map.of("reason", failReason));
        chapterDAO.updateStatus(ch.id(), "FAILED");
        return null;
    }

    /** 评审步骤结果：fullText 为修订后正文（可能未改）；pending=true 表示转人工，管线到此为止。 */
    private record ReviewStepResult(String fullText, boolean pending) {}

    /**
     * 步骤 3.4/3.5 共用评审模板：读者评审与 AI 语义审校同构——评审 → BLOCKER 带清单自动修一轮 → 复审；
     * 复审仍 BLOCKER 转人工（auto 也不过稿）。调用异常 fail-open（机械门禁已过的正文不因评审故障而废）。
     */
    private ReviewStepResult reviewStep(long novelId, ChapterDO ch, String fullText, StageLog.Stage stage) {
        boolean isReader = stage == StageLog.Stage.READER;
        chapterDAO.updateStatus(ch.id(), "GATE_AI_REVIEW");
        stageLog.emit(novelId, ch.chapterNo(), stage, START, Map.of());
        ReviewService.Outcome outcome;
        try {
            outcome = isReader ? reviewService.readerReviewAndFix(novelId, ch, fullText)
                    : reviewService.reviewAndFix(novelId, ch, fullText);
        } catch (Exception e) {
            log.warn("第 {} 章{}调用异常，fail-open 放行：{}", ch.chapterNo(), stage.label(), e.getMessage());
            stageLog.emit(novelId, ch.chapterNo(), stage, ERROR, Map.of("message", String.valueOf(e.getMessage())));
            outcome = new ReviewService.Outcome(null, "skipped", false);
        }
        if (outcome.revised() != null) {
            fullText = outcome.revised();
            chapterDAO.saveFullText(ch.id(), fullText);
            stageLog.emit(novelId, ch.chapterNo(), REVISE, isReader ? READER_FIX : REVIEW_FIX,
                    Map.of("chars", fullText.length()));
        }
        stageLog.emit(novelId, ch.chapterNo(), stage, DONE,
                Map.of("verdict", outcome.verdict(), "blocked", outcome.blocked()));
        if (outcome.blocked()) {
            log.warn("第 {} 章{}复审仍 BLOCKER，转人工审批", ch.chapterNo(), stage.label());
            chapterDAO.updateStatus(ch.id(), "PENDING_APPROVAL");
            stageLog.emit(novelId, ch.chapterNo(), APPROVE, PENDING, Map.of("reason", stage.wire() + "_blocker"));
            return new ReviewStepResult(fullText, true);
        }
        return new ReviewStepResult(fullText, false);
    }
}
