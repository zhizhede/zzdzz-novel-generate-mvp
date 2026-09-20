package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.SceneDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ChapterStepDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import com.zzdzz.novelgen.model.enums.ChapterStatus;
import com.zzdzz.novelgen.model.enums.PlanMode;
import com.zzdzz.novelgen.model.enums.SceneGateStatus;
import com.zzdzz.novelgen.model.enums.StepStatus;
import static com.zzdzz.novelgen.service.StageLog.Phase.*;
import static com.zzdzz.novelgen.service.StageLog.Stage.*;

/**
 * 单章管线编排。步骤清单见 {@link Step}（章纲→场景→拼章门禁→读者评审→AI 审校→审批→digest），
 * runChapter 只做顺序编排与卫语句跳转；每个步骤向 chapter_steps 落状态行（契约②）。
 * 中断语义（流 0）：stopCheck 在每个步骤/场景边界检查，命中即章节 INTERRUPTED、已完成产物保留。
 * 自愈梯子在 {@link #runChapterWithHeal}：直跑 → 重试 → 换目标重写；INTERRUPTED 不参与自愈。
 * 人工打回（流 A）：{@link #reject}/{@link #rejectOutline} 清场落意见重排队；意见在章纲重出时注入并清零。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChapterPipelineService {


    private final NovelDataService novelData;
    private final ChapterDataService chapterData;
    private final SceneDataService sceneData;
    private final ChapterStepDataService stepData;
    private final DigestDataService digestData;
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
    private final PromptTemplateService promptTemplates;
    private final ObjectMapper mapper;


    /** 进度回调：队列服务据此回写任务进度；shouldStop 支持运行中协作取消（章与章之间检查）。 */
    public interface ProgressSink {
        void onProgress(int doneChapters, int nextChapter, String message);

        boolean shouldStop();

        /** 插队权：当前章完成后暂停（④ 批）。默认无插队。 */
        default boolean pauseRequested() { return false; }

        /** 暂停命中回调：队列层负责把任务置 PAUSED。 */
        default void onPauseHit() {}
    }

    /** 单章一次尝试的结果：DONE 正常走完（含 manual 停等）；FAILED 可自愈；INTERRUPTED 用户终止。 */
    enum ChapterOutcome { DONE, FAILED, INTERRUPTED, PENDING }

    /** 兼容旧入口：按作品标题连跑，无进度回调。 */
    public int runChapters(String novelTitle, int from, int to) {
        Long novelId = novelData.findIdByTitle(novelTitle);
        if (novelId == null) throw new BizException(ErrorCode.NOT_FOUND, "作品不存在: " + novelTitle);
        return runChapters(novelId, novelTitle, from, to, null);
    }

    /** 连跑 [from, to] 章；返回通过章数。失败/异常/中断即停止（状态保留，断点重跑）。 */
    public int runChapters(long novelId, String novelTitle, int from, int to, ProgressSink sink) {
        String mode = novelData.findApprovalMode(novelId);
        log.info("连跑开始 {} 第 {}–{} 章（审批模式 {}）", novelTitle, from, to, mode);

        int okChapters = 0;
        for (int no = from; no <= to; no++) {
            if (sink != null && sink.shouldStop()) {
                log.warn("连跑被取消（已完成 {} 章）", okChapters);
                break;
            }
            long t0 = System.currentTimeMillis();
            ChapterOutcome outcome = runChapterWithHeal(novelId, no, mode, sink);
            switch (outcome) {
                case DONE, PENDING -> {
                    okChapters++;
                    log.info("=== 第 {} 章完成（{}），耗时 {}s ===", no, outcome, (System.currentTimeMillis() - t0) / 1000);
                }
                case INTERRUPTED -> {
                    log.warn("=== 第 {} 章被用户终止，停止连跑（已完成 {} 章）===", no, okChapters);
                    return okChapters;
                }
                case FAILED -> {
                    log.error("=== 第 {} 章自愈后仍失败，停止连跑（已完成 {} 章）===", no, okChapters);
                    return okChapters;
                }
            }
            if (sink != null && sink.pauseRequested()) {
                sink.onPauseHit();
                log.warn("插队暂停命中：第 {} 章后暂停（已完成 {} 章）", no, okChapters);
                return okChapters;
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
     * 次数走 tuning（heal_retry_times / heal_replan_times）；每次尝试共享场景级断点缓存；INTERRUPTED 不自愈。
     */
    private ChapterOutcome runChapterWithHeal(long novelId, int chapterNo, String approvalMode, ProgressSink sink) {
        BooleanSupplier stopCheck = sink == null ? () -> false : sink::shouldStop;
        int attempt = 1;
        ChapterOutcome outcome = attemptChapter(novelId, chapterNo, approvalMode, attempt, stopCheck);
        if (outcome != ChapterOutcome.FAILED) return outcome;
        int retries = tuning.i("heal_retry_times", TuningDefaults.HEAL_RETRY_TIMES);
        for (int r = 1; r <= retries && outcome == ChapterOutcome.FAILED; r++) {
            stageLog.emit(novelId, chapterNo, HEAL, RETRY,
                    Map.of("message", retries == 1 ? "第一次失败，自动重试"
                            : "失败，自动重试（第 " + r + "/" + retries + " 次）"));
            log.warn("第 {} 章失败，自愈：直接重试（{}/{}）", chapterNo, r, retries);
            outcome = attemptChapter(novelId, chapterNo, approvalMode, attempt++, stopCheck);
        }
        if (outcome != ChapterOutcome.FAILED) return outcome;
        String reason = failureReason(novelId, chapterNo);
        stageLog.emit(novelId, chapterNo, HEAL, REPLAN,
                Map.of("message", "重试仍败，重写卷纲目标后再试", "reason", reason));
        log.warn("第 {} 章重试仍败，自愈：重写卷纲目标后再试一次（原因：{}）", chapterNo, reason);
        volumePlanService.replanChapter(novelId, chapterNo, reason);
        int replans = tuning.i("heal_replan_times", TuningDefaults.HEAL_REPLAN_TIMES);
        for (int r = 0; r < replans && outcome == ChapterOutcome.FAILED; r++) {
            outcome = attemptChapter(novelId, chapterNo, approvalMode, attempt++, stopCheck);
        }
        return outcome;
    }

    /** 单章尝试：异常收敛为 FAILED；用户停止引发的异常（硬中断打断在飞调用）收敛为 INTERRUPTED。 */
    private ChapterOutcome attemptChapter(long novelId, int chapterNo, String approvalMode,
                                          int attempt, BooleanSupplier stopCheck) {
        try {
            return runChapter(novelId, chapterNo, approvalMode, attempt, stopCheck);
        } catch (Exception e) {
            boolean stopped = stopCheck.getAsBoolean();
            Thread.interrupted(); // 清中断标志，避免殃及该 worker 线程的后续 JDBC/HTTP 操作
            log.error("第 {} 章尝试异常（{}）：{}", chapterNo, stopped ? "用户终止" : "失败", e.getMessage());
            ChapterDO ch = chapterData.find(novelId, chapterNo).orElse(null);
            if (ch != null) {
                stepData.finishRunningInterrupted(ch.getId());
                if (stopped) {
                    chapterData.updateStatus(ch.getId(), ChapterStatus.INTERRUPTED.wire());
                    stageLog.emit(novelId, chapterNo, CHAPTER, STOPPED,
                            Map.of("reason", "用户终止（硬中断）", "step", "IN_FLIGHT"));
                    return ChapterOutcome.INTERRUPTED;
                }
                chapterData.updateStatus(ch.getId(), ChapterStatus.FAILED.wire());
            }
            return stopped ? ChapterOutcome.INTERRUPTED : ChapterOutcome.FAILED;
        }
    }

    private ChapterOutcome runChapter(long novelId, int chapterNo, String approvalMode,
                                      int attempt, BooleanSupplier stopCheck) {
        ChapterDO ch = outlineService.loadChapter(novelId, chapterNo);
        chapterData.updateReviewConfig(ch.getId(), gateService.readerStandardsJson(novelId)); // 评审标准随章快照，回看当时口径
        stageLog.emit(novelId, chapterNo, CHAPTER, START, Map.of("title", String.valueOf(ch.getTitle())));

        ChapterOutcome outlineOutcome = outlineStep(novelId, ch, attempt, stopCheck);
        if (outlineOutcome != ChapterOutcome.DONE) return outlineOutcome;
        ch = reload(ch.getId());

        // 流 A：manual 模式章纲卡点——章纲就绪即停等人工批准/打回（已批准过则继续）
        if (PlanMode.MANUAL.is(approvalMode) && !ChapterStatus.OUTLINE_APPROVED.is(ch.getStatus())) {
            stageLog.emit(novelId, chapterNo, APPROVE, PENDING, Map.of("reason", "outline_pending"));
            log.info("第 {} 章章纲待人工批准（manual）", chapterNo);
            return ChapterOutcome.PENDING;
        }

        ChapterOutcome scenesOutcome = scenesStep(novelId, ch, attempt, stopCheck);
        if (scenesOutcome != ChapterOutcome.DONE) return scenesOutcome;
        ChapterOutcome assembleOutcome = assembleGateStep(novelId, ch, attempt, stopCheck);
        if (assembleOutcome != ChapterOutcome.DONE) return assembleOutcome;
        ch = reload(ch.getId());
        String fullText = ch.getFullText() == null ? "" : ch.getFullText();

        ReviewStepResult reader = reviewStep(novelId, ch, fullText, StageLog.Stage.READER, attempt, stopCheck);
        if (reader.outcome() != ChapterOutcome.DONE) return reader.outcome();
        fullText = reader.fullText();
        ReviewStepResult review = reviewStep(novelId, ch, fullText, StageLog.Stage.REVIEW, attempt, stopCheck);
        if (review.outcome() != ChapterOutcome.DONE) return review.outcome();
        fullText = review.fullText();

        // 4) 审批（auto 直过；manual 停在 PENDING_APPROVAL 等人）
        if (PlanMode.MANUAL.is(approvalMode)) {
            chapterData.updateStatus(ch.getId(), ChapterStatus.PENDING_APPROVAL.wire());
            log.info("第 {} 章待人工审批", chapterNo);
            stageLog.emit(novelId, chapterNo, APPROVE, PENDING, Map.of());
            return ChapterOutcome.PENDING;
        }

        // 5) digest
        Long digestStep = stepData.start(novelId, ch.getId(), chapterNo, Step.DIGEST.name(), null, attempt);
        try {
            digestService.digest(novelId, ch.getId(), chapterNo, fullText);
            stepData.finish(digestStep, StepStatus.DONE.wire(), json(Map.of("chars", fullText.length())));
        } catch (Exception e) {
            stepData.finish(digestStep, StepStatus.FAILED.wire(), json(Map.of("reason", String.valueOf(e.getMessage()))));
            throw e;
        }
        stageLog.emit(novelId, chapterNo, DIGEST, DONE, Map.of());
        stageLog.emit(novelId, chapterNo, CHAPTER, DONE, Map.of("chars", fullText.length()));
        return ChapterOutcome.DONE;
    }

    private ChapterDO reload(long chapterId) {
        return chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
    }

    /** 自愈用失败原因：优先取最近一次门禁失败清单，取不到给兜底文案。 */
    private String failureReason(long novelId, int chapterNo) {
        try {
            ChapterDO ch = chapterData.find(novelId, chapterNo).orElse(null);
            if (ch != null) {
                String s = gateService.failedChecksText(ch.getId());
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Exception ignore) {
            // 门禁报告缺失不影响自愈流程
        }
        return "生成异常或审校未过（详见 gate_reports / llm_call_log）";
    }

    // ===== Web 触发已迁移至 GenerationQueueService（DB 队列 + worker 异步执行） =====

    /** 强制重出章纲：清掉旧场景与门禁报告，按当前卷纲目标/大纲/前情重新生成场景拆解（同步调用，约 1-2 分钟）。 */
    public List<OutlineService.SceneSpec> regenerateOutline(long novelId, int chapterNo) {
        ChapterDO ch = outlineService.loadChapter(novelId, chapterNo);
        if (ch.getFullText() != null && !ch.getFullText().isBlank()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "第 " + chapterNo + " 章已有正文，禁止重出章纲");
        }
        List<String> digests = packer.recentDigests(novelId, chapterNo, 3);
        outlineService.generate(novelId, ch, packer.world(novelId), packer.characters(novelId),
                packer.foreshadowDirectives(novelId, chapterNo), digests,
                packer.prevTail(novelId, chapterNo), packer.prevChapterBrief(novelId, chapterNo));
        return outlineService.loadSpecs(ch.getId());
    }

    /** 人工审批：仅 PENDING_APPROVAL 可过审；过审即生成 digest，终点状态 DIGESTED（与 auto 流一致；
     * 库约束不含 APPROVED，历史版本在此必撞约束导致「digest 成功、接口报错」）。
     * 条件状态推进防并发重复审批。 */
    public void approve(long chapterId) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (!ChapterStatus.PENDING_APPROVAL.is(ch.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "章 " + chapterId + " 状态为 " + ch.getStatus() + "，不在待审批");
        }
        // 先原子占位（PENDING_APPROVAL → DIGESTED，占位必须用合法状态值；库约束无 DIGESTING/APPROVED），
        // 并发第二次点击立刻被拒；digest 异常则回退原状态
        chapterData.updateStatusIf(chapterId, ChapterStatus.PENDING_APPROVAL.wire(), ChapterStatus.DIGESTED.wire());
        try {
            digestService.digest(ch.getNovelId(), ch.getId(), ch.getChapterNo(), ch.getFullText());
        } catch (Exception e) {
            chapterData.updateStatus(chapterId, ChapterStatus.PENDING_APPROVAL.wire());
            throw e;
        }
    }

    /** 人工审批异步版：占位后立即返回，digest 后台跑（单线程串行）；失败回退待审批。 */
    public void approveAsync(long chapterId) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (!ChapterStatus.PENDING_APPROVAL.is(ch.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "章 " + chapterId + " 状态为 " + ch.getStatus() + "，不在待审批");
        }
        chapterData.updateStatusIf(chapterId, ChapterStatus.PENDING_APPROVAL.wire(), ChapterStatus.DIGESTED.wire());
        digestExecutor.submit(() -> runDigestQuietly(chapterId));
    }

    /** 异步 digest 主体：失败回退 PENDING_APPROVAL 并记事件（接口早已返回，只能靠日志与状态回退暴露）。 */
    private void runDigestQuietly(long chapterId) {
        ChapterDO ch = chapterData.findById(chapterId).orElse(null);
        if (ch == null) {
            log.warn("后台 digest 目标章 {} 不存在，跳过", chapterId);
            return;
        }
        Long stepId = stepData.start(ch.getNovelId(), ch.getId(), ch.getChapterNo(), Step.DIGEST.name(), null, 1);
        try {
            digestService.digest(ch.getNovelId(), ch.getId(), ch.getChapterNo(), ch.getFullText());
            stepData.finish(stepId, StepStatus.DONE.wire(), null);
        } catch (Exception e) {
            stepData.finish(stepId, StepStatus.FAILED.wire(), json(Map.of("reason", String.valueOf(e.getMessage()))));
            log.warn("章 {} 后台 digest 失败，回退待审批：{}", ch.getChapterNo(), e.getMessage());
            chapterData.updateStatusIf(chapterId, ChapterStatus.DIGESTED.wire(), ChapterStatus.PENDING_APPROVAL.wire());
            stageLog.emit(ch.getNovelId(), ch.getChapterNo(), StageLog.Stage.APPROVE, StageLog.Phase.FAILED,
                    Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 启动自愈：异步审批占位后若进程重启，章会停留在「DIGESTED 但无事实账」——这里统一补跑。
     * 管线内自动审批保持同步（下一章上下文依赖本章 digest），仅人工审批走异步，故缺口只会来自人工路径。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void healOrphanedApproved() {
        List<ChapterDataService.ApprovedNoDigest> orphans = chapterData.findApprovedWithoutDigest();
        for (ChapterDataService.ApprovedNoDigest o : orphans) {
            log.warn("启动自愈：章 {}（novel {}）状态 DIGESTED 但无事实账，补跑后台 digest", o.chapterNo(), o.novelId());
            digestExecutor.submit(() -> runDigestQuietly(o.id()));
        }
    }

    // ===== 流 A：打回 / 章纲卡点 =====

    /** 打回目标：队列层据此重新入队。 */
    public record RejectTarget(long novelId, String novelTitle, int chapterNo) {}

    /** 终稿打回：仅 PENDING_APPROVAL 可打回；清场落意见重排队，意见注入下次章纲。 */
    public RejectTarget reject(long chapterId, String reason) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (!ChapterStatus.PENDING_APPROVAL.is(ch.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "章 " + chapterId + " 状态为 " + ch.getStatus() + "，仅待审批章可打回");
        }
        chapterData.rejectReset(chapterId, reason);
        stageLog.emit(ch.getNovelId(), ch.getChapterNo(), APPROVE, REJECTED,
                Map.of("reason", truncate(reason), "flow", "final_reject"));
        log.info("第 {} 章被人工打回：{}", ch.getChapterNo(), truncate(reason));
        return new RejectTarget(ch.getNovelId(), novelTitle(ch.getNovelId()), ch.getChapterNo());
    }

    /** 章纲打回：OUTLINED（manual 卡点）可打回；清场落意见，重出章纲时注入。 */
    public RejectTarget rejectOutline(long chapterId, String reason) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (!ChapterStatus.OUTLINED.is(ch.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "章 " + chapterId + " 状态为 " + ch.getStatus() + "，仅待批章纲可打回");
        }
        chapterData.rejectReset(chapterId, reason);
        stageLog.emit(ch.getNovelId(), ch.getChapterNo(), OUTLINE, REJECTED,
                Map.of("reason", truncate(reason), "flow", "outline_reject"));
        log.info("第 {} 章章纲被人工打回：{}", ch.getChapterNo(), truncate(reason));
        return new RejectTarget(ch.getNovelId(), novelTitle(ch.getNovelId()), ch.getChapterNo());
    }

    /** 人工编辑场景草稿（Q5a）：仅未在生成中的章；保存后立即重过该场景机械门禁。
     * 续跑时 PASSED 场景复用编辑稿，FAILED 场景继续人工改或重生成。 */
    public boolean editSceneDraft(long sceneId, String draftText) {
        SceneDO scene = sceneData.getById(sceneId);
        if (scene == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + sceneId);
        }
        ChapterDO ch = chapterData.findById(scene.getChapterId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在"));
        String st = ch.getStatus();
        if (List.of(ChapterStatus.GATE_MECHANICAL.wire(), ChapterStatus.GATE_AI_REVIEW.wire(), ChapterStatus.REVISING.wire(), ChapterStatus.PENDING_APPROVAL.wire(), ChapterStatus.DIGESTED.wire())
                .contains(st)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "章状态为 " + st + "，场景编辑仅限生成前（NEW/OUTLINED/OUTLINE_APPROVED/FAILED/INTERRUPTED）");
        }
        sceneData.applyRevise(sceneId, draftText);
        int words = outlineService.loadSpecs(ch.getId()).stream()
                .filter(spec -> spec.sceneNo() == scene.getSceneNo())
                .findFirst().map(OutlineService.SceneSpec::words).orElse(900);
        boolean passed = gateService.checkScene(ch.getNovelId(), ch.getId(), sceneId, scene.getSceneNo(),
                draftText, words);
        sceneData.updateGateStatus(sceneId, passed ? SceneGateStatus.PASSED.wire() : SceneGateStatus.FAILED.wire());
        stageLog.emit(ch.getNovelId(), ch.getChapterNo(), SCENE, DRAFT,
                Map.of("sceneNo", scene.getSceneNo(), "edited", true, "gatePassed", passed));
        return passed;
    }

    /** 人工编辑正文（Q5b）：仅 PENDING_APPROVAL/DIGESTED（生成后静态态，不会被管线覆盖）。
     * DIGESTED 编辑后事实账作废、状态回 PENDING_APPROVAL，重新审批即重算。 */
    public void editFullText(long chapterId, String fullText) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        String st = ch.getStatus();
        if (!ChapterStatus.PENDING_APPROVAL.is(st) && !ChapterStatus.DIGESTED.is(st)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "章状态为 " + st + "，正文编辑仅限待审批/已 digest 章");
        }
        chapterData.saveFullText(chapterId, fullText);
        if (ChapterStatus.DIGESTED.is(st)) {
            digestData.deleteByChapter(chapterId);
            chapterData.updateStatus(chapterId, ChapterStatus.PENDING_APPROVAL.wire());
            stageLog.emit(ch.getNovelId(), ch.getChapterNo(), APPROVE, PENDING,
                    Map.of("reason", "manual_edit", "chars", fullText.length()));
        } else {
            stageLog.emit(ch.getNovelId(), ch.getChapterNo(), CHAPTER, DRAFT,
                    Map.of("chars", fullText.length(), "edited", true));
        }
        log.info("第 {} 章正文被人工编辑（{} 字符）", ch.getChapterNo(), fullText.length());
    }

    /** 事后否决（流 A 扩展，依赖 digest 解耦）：DIGESTED 章打回；清除本章事实账（摘要+facts），
     * 重生成末尾 digest 步骤重建。世界状态快照不删（重算 upsert 覆盖）；伏笔 flips 保留（后续章可能引用）。 */
    public RejectTarget veto(long chapterId, String reason) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (!ChapterStatus.DIGESTED.is(ch.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "章 " + chapterId + " 状态为 " + ch.getStatus() + "，仅已 digest 章可否决");
        }
        digestData.deleteByChapter(chapterId);
        chapterData.rejectReset(chapterId, reason);
        stageLog.emit(ch.getNovelId(), ch.getChapterNo(), DIGEST, REJECTED,
                Map.of("reason", truncate(reason), "flow", "veto"));
        log.info("第 {} 章被人工否决（digest 已清除，重算待重生成）：{}", ch.getChapterNo(), truncate(reason));
        return new RejectTarget(ch.getNovelId(), novelTitle(ch.getNovelId()), ch.getChapterNo());
    }

    /** 章纲批准（manual 卡点放行）：OUTLINED → OUTLINE_APPROVED，续跑入队后从场景步开始。 */
    public RejectTarget approveOutline(long chapterId) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (!chapterData.updateStatusIf(chapterId, ChapterStatus.OUTLINED.wire(), ChapterStatus.OUTLINE_APPROVED.wire())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "章 " + chapterId + " 状态为 " + ch.getStatus() + "，不在待批章纲");
        }
        stageLog.emit(ch.getNovelId(), ch.getChapterNo(), OUTLINE, ADOPTED, Map.of("flow", "outline_approve"));
        return new RejectTarget(ch.getNovelId(), novelTitle(ch.getNovelId()), ch.getChapterNo());
    }

    private String novelTitle(long novelId) {
        var novel = novelData.getById(novelId);
        return novel == null ? String.valueOf(novelId) : novel.getTitle();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private final java.util.concurrent.ExecutorService digestExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "digest-async");
                t.setDaemon(true);
                return t;
            });

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
        String feedback = gateService.failureSummary(ch.getId());
        int curWords = ((Number) GateService.computeMetrics(fullText).get("cjk")).intValue();
        int cap = (int) (ch.getBudgetMax() * 1.05);
        // 严重超长时 ±10% 的温和约束数学上救不回来，改为给明确压缩目标
        String lengthRule = curWords > cap
                ? "当前正文约 %d 字，超出预算上限：请把篇幅压缩到 %d–%d 字（删冗余描写与重复信息，情节与对白全保留）"
                        .formatted(curWords, ch.getBudgetMin(), cap)
                : "总字数变化控制在 ±10%% 内，且不得超过 %d 字".formatted(cap);
        String user = promptTemplates.format(LlmNode.CHAPTER_REVISE, "user", """
                任务：修订第 %d 章全文。门禁检测出以下问题：
                %s
                要求：只针对被点名的问题做最小修改（例如破折号超标：把「——」改写为逗号、句号、拆句或直接删除）；
                除被点名的指标外，其余风格特征必须原样保留——破折号「——」与省略号「……」的数量不得增加，分行节奏不得重排；
                严禁改动情节、人物与对话内容；%s。
                直接输出修订后的完整正文，不要输出思考过程。

                【第 %d 章全文（在此版本上修改）】
                %s
                """, ch.getChapterNo(), feedback, lengthRule, ch.getChapterNo(), fullText);
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                LlmNode.CHAPTER_REVISE, novelId, ch.getId(),
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.CHAPTER_REVISE, "system",
                                "你是执行门禁修订的网文编辑，只做被点名的最小修改。")),
                        LlmPort.Message.user(user)),
                LlmTemps.CHAPTER_REVISE));
        return SceneService.cleanDraft(r.content());
    }

    /** 单章管线步骤清单：一个枚举看全流程（新增步骤=加一行+一个 step 方法），亦作 chapter_steps.step 字典。 */
    enum Step {
        OUTLINE("AI 章纲"),
        SCENE("场景生成+门禁"),
        ASSEMBLE("拼章+章级门禁+修订"),
        READER("读者评审"),
        AI_REVIEW("AI 语义审校"),
        APPROVE("审批"),
        DIGEST("事实账");

        final String label;

        Step(String label) { this.label = label; }
    }

    private boolean streamLongText() {
        return tuning.i("stream_long_text", TuningDefaults.STREAM_LONG_TEXT) == 1;
    }

    /**
     * 场景流式增量转发（SCENE/CHUNK，纯 SSE 不落库）：思考/正文各自缓冲、约 200ms 合并一批，
     * 避免每秒数帧的 delta 直推前端；close() 冲刷尾部。事件负载 {sceneNo,type:text|think,delta}。
     */
    final class SceneChunkRelay implements LlmPort.StreamDelta {
        private static final long FLUSH_INTERVAL_MS = 200;

        private final long novelId;
        private final int chapterNo;
        private final int sceneNo;
        private final StringBuilder thinkBuf = new StringBuilder();
        private final StringBuilder textBuf = new StringBuilder();
        private long lastFlush = System.currentTimeMillis();

        SceneChunkRelay(long novelId, int chapterNo, int sceneNo) {
            this.novelId = novelId;
            this.chapterNo = chapterNo;
            this.sceneNo = sceneNo;
        }

        @Override
        public void accept(boolean think, String piece) {
            (think ? thinkBuf : textBuf).append(piece);
            if (System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS) flush();
        }

        void flush() {
            if (thinkBuf.length() > 0) {
                stageLog.emitLive(novelId, chapterNo, SCENE, CHUNK,
                        Map.of("sceneNo", sceneNo, "type", "think", "delta", thinkBuf.toString()));
                thinkBuf.setLength(0);
            }
            if (textBuf.length() > 0) {
                stageLog.emitLive(novelId, chapterNo, SCENE, CHUNK,
                        Map.of("sceneNo", sceneNo, "type", "text", "delta", textBuf.toString()));
                textBuf.setLength(0);
            }
            lastFlush = System.currentTimeMillis();
        }

        void close() {
            flush();
        }
    }

    /** 步骤 1：AI 章纲。已物化则跳过（场景级断点续跑）。 */
    private ChapterOutcome outlineStep(long novelId, ChapterDO ch, int attempt, BooleanSupplier stopCheck) {
        if (sceneData.countByChapter(ch.getId()) > 0) return ChapterOutcome.DONE;
        if (stopCheck.getAsBoolean()) return interrupt(novelId, ch, Step.OUTLINE.name(), null);
        int chapterNo = ch.getChapterNo();
        Long stepId = stepData.start(novelId, ch.getId(), chapterNo, Step.OUTLINE.name(), null, attempt);
        stageLog.emit(novelId, chapterNo, OUTLINE, START, Map.of());
        List<String> digests = packer.recentDigests(novelId, chapterNo, 3);
        outlineService.generate(novelId, ch, packer.world(novelId), packer.characters(novelId),
                packer.foreshadowDirectives(novelId, chapterNo), digests,
                packer.prevTail(novelId, chapterNo), packer.prevChapterBrief(novelId, chapterNo));
        int sceneCount = outlineService.loadSpecs(ch.getId()).size();
        stepData.finish(stepId, StepStatus.DONE.wire(), json(Map.of("sceneCount", sceneCount)));
        stageLog.emit(novelId, chapterNo, OUTLINE, DONE, Map.of("sceneCount", sceneCount));
        return ChapterOutcome.DONE;
    }

    /** 步骤 2：逐场景生成 + 场景门禁（带意见重写≤2 轮）；已 PASSED 场景直接复用。全过返回 DONE。 */
    private ChapterOutcome scenesStep(long novelId, ChapterDO ch, int attempt, BooleanSupplier stopCheck) {
        int chapterNo = ch.getChapterNo();
        var specs = outlineService.loadSpecs(ch.getId());
        Map<Integer, SceneDO> doneScenes = sceneData.findByChapter(ch.getId()).stream()
                .collect(Collectors.toMap(SceneDO::getSceneNo, s -> s));
        List<String> digests = packer.recentDigests(novelId, chapterNo, 3);
        String prevTail = packer.prevTail(novelId, chapterNo);
        List<String> directives = packer.foreshadowDirectives(novelId, chapterNo);
        int passedScenes = 0;
        String prevScene = null;
        for (var spec : specs) {
            if (stopCheck.getAsBoolean()) return interrupt(novelId, ch, Step.SCENE.name(), String.valueOf(spec.sceneNo()));
            SceneDO done = doneScenes.get(spec.sceneNo());
            if (done != null && SceneGateStatus.PASSED.is(done.getGateStatus())) {
                passedScenes++;
                prevScene = done.getDraftText();
                stageLog.emit(novelId, chapterNo, SCENE, REUSED,
                        Map.of("sceneNo", spec.sceneNo(), "text", String.valueOf(done.getDraftText())));
                continue;
            }
            Long sceneId = sceneData.findId(ch.getId(), spec.sceneNo());
            Long stepId = stepData.start(novelId, ch.getId(), chapterNo, Step.SCENE.name(),
                    String.valueOf(spec.sceneNo()), attempt);
            var pack = packer.packScene(novelId, chapterNo, ch, spec, digests, prevTail, directives, prevScene);
            stageLog.emit(novelId, chapterNo, SCENE, START,
                    Map.of("sceneNo", spec.sceneNo(), "goal", String.valueOf(spec.goal())));
            SceneChunkRelay relay = streamLongText()
                    ? new SceneChunkRelay(novelId, chapterNo, spec.sceneNo()) : null;
            String draft;
            try {
                draft = sceneService.generate(novelId, ch.getId(), chapterNo, pack, spec.sceneNo(), relay);
            } finally {
                if (relay != null) relay.close(); // 冲刷节流缓冲的尾部增量
            }
            stageLog.emit(novelId, chapterNo, SCENE, DRAFT,
                    Map.of("sceneNo", spec.sceneNo(), "text", String.valueOf(draft)));
            boolean ok = gateService.checkScene(novelId, ch.getId(), sceneId, spec.sceneNo(), draft, spec.words());
            // 带意见重写（密度类指标一轮修订常按下葫芦浮起瓢），轮数走 tuning
            int maxRewrites = tuning.i("scene_revise_rounds", TuningDefaults.SCENE_REVISE_ROUNDS);
            for (int round = 1; round <= maxRewrites && !ok; round++) {
                if (stopCheck.getAsBoolean()) {
                    stepData.finish(stepId, StepStatus.INTERRUPTED.wire(), json(Map.of("sceneNo", spec.sceneNo(), "round", round)));
                    return interrupt(novelId, ch, Step.SCENE.name(), String.valueOf(spec.sceneNo()));
                }
                log.warn("场景 {} 门禁未过，带意见重写（第 {} 轮）", spec.sceneNo(), round);
                stageLog.emit(novelId, chapterNo, SCENE_GATE, NONE,
                        Map.of("sceneNo", spec.sceneNo(), "passed", false, "rewrite", true, "round", round,
                                "reason", gateService.failedChecksText(ch.getId(), sceneId)));
                draft = sceneService.revise(novelId, ch.getId(), sceneId, spec.sceneNo(),
                        draft, gateService.failureSummary(ch.getId(), sceneId), pack);
                stageLog.emit(novelId, chapterNo, SCENE, DRAFT,
                        Map.of("sceneNo", spec.sceneNo(), "text", String.valueOf(draft)));
                ok = gateService.checkScene(novelId, ch.getId(), sceneId, spec.sceneNo(), draft, spec.words());
            }
            stageLog.emit(novelId, chapterNo, SCENE_GATE, NONE,
                    Map.of("sceneNo", spec.sceneNo(), "passed", ok,
                            "reason", ok ? "" : gateService.failedChecksText(ch.getId(), sceneId)));
            sceneData.updateGateStatus(sceneId, ok ? SceneGateStatus.PASSED.wire() : SceneGateStatus.FAILED.wire());
            if (ok) {
                stepData.finish(stepId, StepStatus.DONE.wire(), json(Map.of("sceneNo", spec.sceneNo())));
                passedScenes++;
            } else {
                stepData.finish(stepId, StepStatus.FAILED.wire(),
                        json(Map.of("sceneNo", spec.sceneNo(), "reason",
                                gateService.failedChecksText(ch.getId(), sceneId))));
            }
            prevScene = draft;
        }
        if (passedScenes < specs.size()) {
            log.error("第 {} 章场景通过 {}/{}，中止", chapterNo, passedScenes, specs.size());
            stageLog.emit(novelId, chapterNo, CHAPTER, FAILED,
                    Map.of("reason", "场景通过 " + passedScenes + "/" + specs.size()));
            chapterData.updateStatus(ch.getId(), ChapterStatus.FAILED.wire());
            return ChapterOutcome.FAILED;
        }
        return ChapterOutcome.DONE;
    }

    /** 步骤 3：拼章 + 章级门禁（复用已修订正文或带意见修订≤2 轮）。过检 DONE，失败 FAILED，终止 INTERRUPTED。 */
    private ChapterOutcome assembleGateStep(long novelId, ChapterDO ch, int attempt, BooleanSupplier stopCheck) {
        int chapterNo = ch.getChapterNo();
        if (stopCheck.getAsBoolean()) return interrupt(novelId, ch, Step.ASSEMBLE.name(), null);
        Long stepId = stepData.start(novelId, ch.getId(), chapterNo, Step.ASSEMBLE.name(), null, attempt);
        StringJoiner joiner = new StringJoiner("\n\n");
        sceneData.findPassedDrafts(ch.getId()).forEach(joiner::add);
        String fullText = stripTitleLine(joiner.toString(), ch.getTitle());
        chapterData.saveFullText(ch.getId(), fullText);
        chapterData.updateStatus(ch.getId(), ChapterStatus.GATE_MECHANICAL.wire());
        stageLog.emit(novelId, chapterNo, ASSEMBLE, NONE, Map.of("chars", fullText.length()));
        if (gateService.checkChapter(novelId, ch.getId(), chapterNo, fullText, ch.getBudgetMin(), ch.getBudgetMax())) {
            stepData.finish(stepId, StepStatus.DONE.wire(), json(Map.of("chars", fullText.length())));
            stageLog.emit(novelId, chapterNo, CHAPTER_GATE, NONE, Map.of("passed", true));
            return ChapterOutcome.DONE;
        }
        stageLog.emit(novelId, chapterNo, CHAPTER_GATE, NONE,
                Map.of("passed", false, "reason", gateService.failedChecksText(ch.getId())));
        // 断点重跑场景：库里已有上一轮修订过、且能过检的正文，直接复用，省一轮修订调用
        String stored = ch.getFullText();
        if (stored != null && !stored.equals(fullText)
                && gateService.checkChapter(novelId, ch.getId(), chapterNo, stored, ch.getBudgetMin(), ch.getBudgetMax())) {
            log.info("第 {} 章复用已修订正文（{} 字符）", chapterNo, stored.length());
            stageLog.emit(novelId, chapterNo, REVISE, REUSE, Map.of("chars", stored.length()));
            chapterData.saveFullText(ch.getId(), stored);
            stepData.finish(stepId, StepStatus.DONE.wire(), json(Map.of("reuse", true)));
            stageLog.emit(novelId, chapterNo, CHAPTER_GATE, NONE, Map.of("passed", true));
            return ChapterOutcome.DONE;
        }
        // 修订迭代制：单轮修订常「修甲伤乙」（补对话密度时狂加破折号、撑爆字数），
        // 最多 N 轮（tuning），每轮以最新失败清单喂回；修订稿长度异常直接弃用本轮防风格雪崩
        int maxRounds = tuning.i("chapter_revise_rounds", TuningDefaults.CHAPTER_REVISE_ROUNDS);
        double lenMin = tuning.d("chapter_revise_len_min", TuningDefaults.CHAPTER_REVISE_LEN_MIN);
        double lenMax = tuning.d("chapter_revise_len_max", TuningDefaults.CHAPTER_REVISE_LEN_MAX);
        String failReason = "修订后门禁仍未过";
        for (int round = 1; round <= maxRounds; round++) {
            if (stopCheck.getAsBoolean()) {
                stepData.finish(stepId, StepStatus.INTERRUPTED.wire(), json(Map.of("round", round)));
                return interrupt(novelId, ch, Step.ASSEMBLE.name(), null);
            }
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
            fullText = stripTitleLine(revised, ch.getTitle());
            stageLog.emit(novelId, chapterNo, REVISE, DONE,
                    Map.of("round", round, "chars", fullText.length()));
            chapterData.saveFullText(ch.getId(), fullText);
            if (gateService.checkChapter(novelId, ch.getId(), chapterNo, fullText, ch.getBudgetMin(), ch.getBudgetMax())) {
                stepData.finish(stepId, StepStatus.DONE.wire(), json(Map.of("chars", fullText.length(), "rounds", round)));
                stageLog.emit(novelId, chapterNo, CHAPTER_GATE, NONE, Map.of("passed", true));
                return ChapterOutcome.DONE;
            }
        }
        log.error("第 {} 章修订两轮后仍未过章级门禁（报告见 gate_reports）", chapterNo);
        stepData.finish(stepId, StepStatus.FAILED.wire(), json(Map.of("reason", failReason)));
        stageLog.emit(novelId, chapterNo, CHAPTER, FAILED, Map.of("reason", failReason));
        chapterData.updateStatus(ch.getId(), ChapterStatus.FAILED.wire());
        return ChapterOutcome.FAILED;
    }

    /** 评审步骤结果：fullText 为修订后正文（可能未改）；outcome 非 DONE 表示转人工/终止。 */
    private record ReviewStepResult(String fullText, ChapterOutcome outcome) {}

    /**
     * 步骤 3.4/3.5 共用评审模板：读者评审与 AI 语义审校同构——评审 → BLOCKER 带清单自动修一轮 → 复审；
     * 复审仍 BLOCKER 转人工（auto 也不过稿）。调用异常 fail-open（机械门禁已过的正文不因评审故障而废）。
     */
    private ReviewStepResult reviewStep(long novelId, ChapterDO ch, String fullText,
                                        StageLog.Stage stage, int attempt, BooleanSupplier stopCheck) {
        boolean isReader = stage == StageLog.Stage.READER;
        String stepName = isReader ? Step.READER.name() : Step.AI_REVIEW.name();
        if (stopCheck.getAsBoolean()) {
            return new ReviewStepResult(fullText, interrupt(novelId, ch, stepName, null));
        }
        chapterData.updateStatus(ch.getId(), "GATE_AI_REVIEW");
        Long stepId = stepData.start(novelId, ch.getId(), ch.getChapterNo(), stepName, null, attempt);
        stageLog.emit(novelId, ch.getChapterNo(), stage, START, Map.of());
        ReviewService.Outcome outcome;
        try {
            outcome = isReader ? reviewService.readerReviewAndFix(novelId, ch, fullText)
                    : reviewService.reviewAndFix(novelId, ch, fullText);
        } catch (Exception e) {
            log.warn("第 {} 章{}调用异常，fail-open 放行：{}", ch.getChapterNo(), stage.label(), e.getMessage());
            stageLog.emit(novelId, ch.getChapterNo(), stage, ERROR, Map.of("message", String.valueOf(e.getMessage())));
            outcome = new ReviewService.Outcome(null, "skipped", false);
            stepData.finish(stepId, StepStatus.DONE.wire(), json(Map.of("verdict", "skipped", "failOpen", true)));
        }
        if (outcome.revised() != null) {
            fullText = outcome.revised();
            chapterData.saveFullText(ch.getId(), fullText);
            stageLog.emit(novelId, ch.getChapterNo(), REVISE, isReader ? READER_FIX : REVIEW_FIX,
                    Map.of("chars", fullText.length()));
        }
        stepData.finish(stepId, StepStatus.DONE.wire(), json(Map.of("verdict", outcome.verdict(), "blocked", outcome.blocked())));
        stageLog.emit(novelId, ch.getChapterNo(), stage, DONE,
                Map.of("verdict", outcome.verdict(), "blocked", outcome.blocked()));
        if (outcome.blocked()) {
            log.warn("第 {} 章{}复审仍 BLOCKER，转人工审批", ch.getChapterNo(), stage.label());
            chapterData.updateStatus(ch.getId(), ChapterStatus.PENDING_APPROVAL.wire());
            stageLog.emit(novelId, ch.getChapterNo(), APPROVE, PENDING, Map.of("reason", stage.wire() + "_blocker"));
            return new ReviewStepResult(fullText, ChapterOutcome.PENDING);
        }
        return new ReviewStepResult(fullText, ChapterOutcome.DONE);
    }

    /** 流 0 中断落地：当前章 INTERRUPTED，已完成产物保留，事件流水可回放终止位置。 */
    private ChapterOutcome interrupt(long novelId, ChapterDO ch, String step, String subKey) {
        stepData.finishRunningInterrupted(ch.getId());
        chapterData.updateStatus(ch.getId(), ChapterStatus.INTERRUPTED.wire());
        stageLog.emit(novelId, ch.getChapterNo(), CHAPTER, STOPPED,
                Map.of("reason", "用户终止", "step", step,
                        "subKey", subKey == null ? "" : subKey));
        log.warn("第 {} 章被用户终止于 {}（{}）", ch.getChapterNo(), step, subKey == null ? "-" : subKey);
        return ChapterOutcome.INTERRUPTED;
    }

    private String json(Map<String, Object> data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }
}
