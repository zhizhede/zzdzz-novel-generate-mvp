package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.SceneDO;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.NovelDAO;
import com.zzdzz.novelgen.dao.PipelineEventDAO;
import com.zzdzz.novelgen.dao.SceneDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * 单章管线编排：AI 章纲 → 逐场景生成（场景门禁，失败带意见重写 1 次）→ 拼章 → 章级门禁
 * → AI 语义审校（BLOCKER 带清单修订一轮+复审，复审不过转人工）→ 审批（auto 直过 / manual 停在 PENDING_APPROVAL）→ digest。
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
    private final LlmPort llm;
    private final PipelineSseService sse;
    private final PipelineEventDAO eventDAO;

    public ChapterPipelineService(NovelDAO novelDAO, ChapterDAO chapterDAO,
                                  SceneDAO sceneDAO, OutlineService outlineService,
                                  ContextPackerService packer, SceneService sceneService,
                                  GateService gateService, DigestService digestService,
                                  ReviewService reviewService, LlmPort llm, PipelineSseService sse,
                                  PipelineEventDAO eventDAO) {
        this.novelDAO = novelDAO;
        this.chapterDAO = chapterDAO;
        this.sceneDAO = sceneDAO;
        this.outlineService = outlineService;
        this.packer = packer;
        this.sceneService = sceneService;
        this.gateService = gateService;
        this.digestService = digestService;
        this.reviewService = reviewService;
        this.llm = llm;
        this.sse = sse;
        this.eventDAO = eventDAO;
    }

    /** 向 SSE 订阅者推管线事件并落库为事件流水（重跑轮次/失败原因可回放）；无订阅者时 SSE 静默。 */
    private void emit(Long novelId, String stage, Map<String, Object> data) {
        sse.send(stage, data);
        Object chapterNo = data.get("chapterNo");
        eventDAO.insert(novelId, chapterNo instanceof Number n ? n.intValue() : null,
                stage, String.valueOf(data.getOrDefault("phase", "")), data);
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
                if (runChapter(novelId, no, mode)) {
                    okChapters++;
                    log.info("=== 第 {} 章完成，耗时 {}s ===", no, (System.currentTimeMillis() - t0) / 1000);
                } else {
                    log.error("=== 第 {} 章失败，停止连跑（已完成 {} 章）===", no, okChapters);
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
        String user = """
                任务：修订第 %d 章全文。门禁检测出以下问题：
                %s
                要求：只针对被点名的问题做最小修改（例如破折号超标：把「——」改写为逗号、句号、拆句或直接删除）；
                除被点名的指标外，其余风格特征必须原样保留——破折号「——」与省略号「……」的数量不得增加，分行节奏不得重排；
                严禁改动情节、人物与对话内容；总字数变化控制在 ±10%% 内，且不得超过 %d 字。
                直接输出修订后的完整正文，不要输出思考过程。

                【第 %d 章全文（在此版本上修改）】
                %s
                """.formatted(ch.chapterNo(), feedback, (int) (ch.budgetMax() * 1.1), ch.chapterNo(), fullText);
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                "chapter_revise", novelId, ch.id(),
                List.of(LlmPort.Message.system("你是执行门禁修订的网文编辑，只做被点名的最小修改。"),
                        LlmPort.Message.user(user)),
                0.5));
        return SceneService.cleanDraft(r.content());
    }

    private boolean runChapter(long novelId, int chapterNo, String approvalMode) {
        ChapterDO ch = outlineService.loadChapter(novelId, chapterNo);
        emit(novelId, "chapter", Map.of("phase", "start", "chapterNo", chapterNo, "title", String.valueOf(ch.title())));

        // 1) AI 章纲（已物化则跳过——场景级断点续跑）
        if (sceneDAO.countByChapter(ch.id()) == 0) {
            emit(novelId, "outline", Map.of("chapterNo", chapterNo, "phase", "start"));
            List<String> digests = packer.recentDigests(novelId, chapterNo, 3);
            outlineService.generate(novelId, ch, packer.world(novelId), packer.characters(novelId),
                    packer.foreshadowDirectives(novelId, chapterNo), digests,
                    packer.prevTail(novelId, chapterNo));
            emit(novelId, "outline", Map.of("chapterNo", chapterNo, "phase", "done",
                    "sceneCount", outlineService.loadSpecs(ch.id()).size()));
        }

        // 2) 逐场景生成 + 场景门禁（失败带意见重写一次）；已 PASSED 的场景直接复用（场景级断点续跑）
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
                emit(novelId, "scene", Map.of("chapterNo", chapterNo, "sceneNo", spec.sceneNo(),
                        "phase", "reused", "text", String.valueOf(done.draftText())));
                continue;
            }
            Long sceneId = sceneDAO.findId(ch.id(), spec.sceneNo());
            var pack = packer.packScene(novelId, chapterNo, ch, spec, digests, prevTail, directives, prevScene);
            emit(novelId, "scene", Map.of("chapterNo", chapterNo, "sceneNo", spec.sceneNo(),
                    "phase", "start", "goal", String.valueOf(spec.goal())));
            String draft = sceneService.generate(novelId, ch.id(), chapterNo, pack, spec.sceneNo());
            emit(novelId, "scene", Map.of("chapterNo", chapterNo, "sceneNo", spec.sceneNo(),
                    "phase", "draft", "text", String.valueOf(draft)));
            boolean ok = gateService.checkScene(novelId, ch.id(), sceneId, spec.sceneNo(), draft);
            // 带意见重写，最多 2 轮（密度类指标一轮修订常按下葫芦浮起瓢）
            for (int round = 1; round <= 2 && !ok; round++) {
                log.warn("场景 {} 门禁未过，带意见重写（第 {} 轮）", spec.sceneNo(), round);
                emit(novelId, "gate", Map.of("chapterNo", chapterNo, "sceneNo", spec.sceneNo(),
                        "passed", false, "rewrite", true, "round", round,
                        "reason", gateService.failedChecksText(ch.id(), sceneId)));
                draft = sceneService.revise(novelId, ch.id(), sceneId, spec.sceneNo(),
                        draft, gateService.failureSummary(ch.id(), sceneId), pack);
                emit(novelId, "scene", Map.of("chapterNo", chapterNo, "sceneNo", spec.sceneNo(),
                        "phase", "draft", "text", String.valueOf(draft)));
                ok = gateService.checkScene(novelId, ch.id(), sceneId, spec.sceneNo(), draft);
            }
            emit(novelId, "gate", Map.of("chapterNo", chapterNo, "sceneNo", spec.sceneNo(), "passed", ok,
                    "reason", ok ? "" : gateService.failedChecksText(ch.id(), sceneId)));
            sceneDAO.updateGateStatus(sceneId, ok ? "PASSED" : "FAILED");
            if (ok) passedScenes++;
            prevScene = draft;
        }
        if (passedScenes < specs.size()) {
            log.error("第 {} 章场景通过 {}/{}，中止", chapterNo, passedScenes, specs.size());
            emit(novelId, "chapter", Map.of("phase", "failed", "chapterNo", chapterNo,
                    "reason", "场景通过 " + passedScenes + "/" + specs.size()));
            chapterDAO.updateStatus(ch.id(), "FAILED");
            return false;
        }

        // 3) 拼章 + 章级门禁（未过带意见修订一轮，复检通过才放行）
        StringJoiner joiner = new StringJoiner("\n\n");
        sceneDAO.findPassedDrafts(ch.id()).forEach(joiner::add);
        String fullText = stripTitleLine(joiner.toString(), ch.title());
        chapterDAO.saveFullText(ch.id(), fullText);
        chapterDAO.updateStatus(ch.id(), "GATE_MECHANICAL");
        emit(novelId, "assemble", Map.of("chapterNo", chapterNo, "chars", fullText.length()));
        if (!gateService.checkChapter(novelId, ch.id(), chapterNo, fullText, ch.budgetMin(), ch.budgetMax())) {
            emit(novelId, "chapter_gate", Map.of("chapterNo", chapterNo, "passed", false,
                    "reason", gateService.failedChecksText(ch.id())));
            // 断点重跑场景：库里已有上一轮修订过、且能过检的正文，直接复用，省一轮修订调用
            String stored = ch.fullText();
            if (stored != null && !stored.equals(fullText)
                    && gateService.checkChapter(novelId, ch.id(), chapterNo, stored, ch.budgetMin(), ch.budgetMax())) {
                log.info("第 {} 章复用已修订正文（{} 字符）", chapterNo, stored.length());
                emit(novelId, "revise", Map.of("chapterNo", chapterNo, "phase", "reuse", "chars", stored.length()));
                fullText = stored;
                chapterDAO.saveFullText(ch.id(), fullText);
            } else {
                // 修订迭代制：单轮修订常「修甲伤乙」（补对话密度时狂加破折号、撑爆字数），
                // 最多两轮，每轮以最新失败清单喂回；修订稿长度异常直接弃用本轮防风格雪崩
                boolean gatePassed = false;
                for (int round = 1; round <= 2; round++) {
                    log.warn("第 {} 章章级门禁未过，带意见修订（第 {}/2 轮）", chapterNo, round);
                    emit(novelId, "revise", Map.of("chapterNo", chapterNo, "phase", "start", "round", round));
                    String revised = reviseChapter(novelId, ch, fullText);
                    if (revised == null || revised.length() < fullText.length() * 0.6
                            || revised.length() > fullText.length() * 1.15) {
                        log.error("第 {} 章修订稿长度异常（{} 字符），弃用本轮", chapterNo,
                                revised == null ? 0 : revised.length());
                        emit(novelId, "revise", Map.of("chapterNo", chapterNo, "phase", "rejected", "round", round));
                        if (round == 2) {
                            emit(novelId, "chapter", Map.of("phase", "failed", "chapterNo", chapterNo, "reason", "修订稿长度异常"));
                            chapterDAO.updateStatus(ch.id(), "FAILED");
                            return false;
                        }
                        continue;
                    }
                    fullText = stripTitleLine(revised, ch.title());
                    emit(novelId, "revise", Map.of("chapterNo", chapterNo, "phase", "done", "round", round,
                            "chars", fullText.length()));
                    chapterDAO.saveFullText(ch.id(), fullText);
                    gatePassed = gateService.checkChapter(novelId, ch.id(), chapterNo, fullText, ch.budgetMin(), ch.budgetMax());
                    if (gatePassed) {
                        break;
                    }
                    if (round == 2) {
                        log.error("第 {} 章修订两轮后仍未过章级门禁（报告见 gate_reports）", chapterNo);
                        emit(novelId, "chapter", Map.of("phase", "failed", "chapterNo", chapterNo, "reason", "修订后门禁仍未过"));
                        chapterDAO.updateStatus(ch.id(), "FAILED");
                        return false;
                    }
                }
            }
        }
        emit(novelId, "chapter_gate", Map.of("chapterNo", chapterNo, "passed", true));

        // 3.5) AI 语义审校（连续性/逻辑/错字）；BLOCKER 带清单修订一轮并复审。
        // 审校调用异常同样 fail-open——机械门禁已过的正文不能因审校故障而废。
        chapterDAO.updateStatus(ch.id(), "GATE_AI_REVIEW");
        emit(novelId, "review", Map.of("chapterNo", chapterNo, "phase", "start"));
        ReviewService.Outcome review;
        try {
            review = reviewService.reviewAndFix(novelId, ch, fullText);
        } catch (Exception e) {
            log.warn("第 {} 章审校调用异常，fail-open 放行：{}", chapterNo, e.getMessage());
            emit(novelId, "review", Map.of("chapterNo", chapterNo, "phase", "error",
                    "message", String.valueOf(e.getMessage())));
            review = new ReviewService.Outcome(null, "skipped", false);
        }
        if (review.revised() != null) {
            fullText = review.revised();
            chapterDAO.saveFullText(ch.id(), fullText);
            emit(novelId, "revise", Map.of("chapterNo", chapterNo, "phase", "review_fix", "chars", fullText.length()));
        }
        emit(novelId, "review", Map.of("chapterNo", chapterNo, "phase", "done",
                "verdict", review.verdict(), "blocked", review.blocked()));
        if (review.blocked()) {
            // auto 模式也不过稿：审校硬伤未清，转人工看报告定夺
            log.warn("第 {} 章审校复审仍 BLOCKER，转人工审批", chapterNo);
            chapterDAO.updateStatus(ch.id(), "PENDING_APPROVAL");
            emit(novelId, "approve", Map.of("chapterNo", chapterNo, "phase", "pending", "reason", "review_blocker"));
            return true;
        }

        // 4) 审批（auto 直过；manual 停在 PENDING_APPROVAL 等人）
        if ("manual".equals(approvalMode)) {
            chapterDAO.updateStatus(ch.id(), "PENDING_APPROVAL");
            log.info("第 {} 章待人工审批", chapterNo);
            emit(novelId, "approve", Map.of("chapterNo", chapterNo, "phase", "pending"));
            return true;
        }

        // 5) digest
        digestService.digest(novelId, ch.id(), chapterNo, fullText);
        emit(novelId, "digest", Map.of("chapterNo", chapterNo, "phase", "done"));
        emit(novelId, "chapter", Map.of("phase", "done", "chapterNo", chapterNo, "chars", fullText.length()));
        return true;
    }
}
