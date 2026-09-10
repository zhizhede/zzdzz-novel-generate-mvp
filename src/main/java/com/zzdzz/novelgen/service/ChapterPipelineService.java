package com.zzdzz.novelgen.service;

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

/**
 * 单章管线编排：AI 章纲 → 逐场景生成（场景门禁，失败带意见重写 1 次）→ 拼章 → 章级门禁
 * → 审批（auto 直过 / manual 停在 PENDING_APPROVAL）→ digest。
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
    private final LlmPort llm;

    public ChapterPipelineService(NovelDAO novelDAO, ChapterDAO chapterDAO,
                                  SceneDAO sceneDAO, OutlineService outlineService,
                                  ContextPackerService packer, SceneService sceneService,
                                  GateService gateService, DigestService digestService,
                                  LlmPort llm) {
        this.novelDAO = novelDAO;
        this.chapterDAO = chapterDAO;
        this.sceneDAO = sceneDAO;
        this.outlineService = outlineService;
        this.packer = packer;
        this.sceneService = sceneService;
        this.gateService = gateService;
        this.digestService = digestService;
        this.llm = llm;
    }

    /** 连跑 [from, to] 章；返回通过章数。任一章失败/异常即停止（状态保留，断点重跑）。 */
    public int runChapters(String novelTitle, int from, int to) {
        Long novelId = novelDAO.findIdByTitle(novelTitle);
        if (novelId == null) throw new IllegalStateException("作品不存在: " + novelTitle);
        String mode = novelDAO.findApprovalMode(novelId);
        log.info("连跑开始 {} 第 {}–{} 章（审批模式 {}）", novelTitle, from, to, mode);

        int okChapters = 0;
        for (int no = from; no <= to; no++) {
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
        }
        log.info("连跑结束：{}/{} 章通过", okChapters, to - from + 1);
        return okChapters;
    }

    // ===== Web 触发：单线程后台执行 + 状态查询 =====

    private final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String lastMessage = "尚未运行";
    private final java.util.concurrent.ExecutorService pipelineExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pipeline-runner");
                t.setDaemon(true);
                return t;
            });

    /** 异步连跑；已有任务在跑时返回 false（由调用方转 409）。 */
    public boolean tryRunAsync(String novelTitle, int from, int to) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        lastMessage = "运行中：" + novelTitle + " 第 " + from + "–" + to + " 章";
        pipelineExecutor.submit(() -> {
            try {
                int ok = runChapters(novelTitle, from, to);
                lastMessage = "完成：通过 " + ok + " 章";
            } catch (Exception e) {
                lastMessage = "异常：" + e.getMessage();
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public PipelineStatus status() {
        return new PipelineStatus(running.get(), lastMessage);
    }

    /** 管线运行状态（内部模型）。 */
    public record PipelineStatus(boolean running, String lastMessage) {
    }

    /** 人工审批：仅 PENDING_APPROVAL 可过审；过审即生成 digest。 */
    public void approve(long chapterId) {
        ChapterDO ch = chapterDAO.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("章不存在: " + chapterId));
        if (!"PENDING_APPROVAL".equals(ch.status())) {
            throw new IllegalStateException("章 " + chapterId + " 状态为 " + ch.status() + "，不在待审批");
        }
        digestService.digest(ch.novelId(), ch.id(), ch.chapterNo(), ch.fullText());
        chapterDAO.updateStatus(ch.id(), "APPROVED");
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        pipelineExecutor.shutdownNow();
    }

    /** 章级机械门禁未过时的修订轮：外科手术式——只修被点名的指标，不动情节与分行节奏。 */
    private String reviseChapter(long novelId, ChapterDO ch, String fullText) {
        String feedback = gateService.failureSummary(ch.id());
        String user = """
                任务：修订第 %d 章全文。门禁检测出以下问题：
                %s
                要求：只针对被点名的问题做最小修改（例如破折号超标：把「——」改写为逗号、句号、拆句或直接删除）；
                严禁改动情节、人物、对话内容、分行节奏，总字数变化控制在 ±10%% 内。
                直接输出修订后的完整正文，不要输出思考过程。

                【第 %d 章全文（在此版本上修改）】
                %s
                """.formatted(ch.chapterNo(), feedback, ch.chapterNo(), fullText);
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                "chapter_revise", novelId, ch.id(),
                List.of(LlmPort.Message.system("你是执行门禁修订的网文编辑，只做被点名的最小修改。"),
                        LlmPort.Message.user(user)),
                0.5));
        return SceneService.cleanDraft(r.content());
    }

    private boolean runChapter(long novelId, int chapterNo, String approvalMode) {
        ChapterDO ch = outlineService.loadChapter(novelId, chapterNo);

        // 1) AI 章纲（已物化则跳过——场景级断点续跑）
        if (sceneDAO.countByChapter(ch.id()) == 0) {
            List<String> digests = packer.recentDigests(novelId, chapterNo, 3);
            outlineService.generate(novelId, ch, packer.world(novelId), packer.characters(novelId),
                    packer.foreshadowDirectives(novelId, chapterNo), digests,
                    packer.prevTail(novelId, chapterNo));
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
                continue;
            }
            Long sceneId = sceneDAO.findId(ch.id(), spec.sceneNo());
            var pack = packer.packScene(novelId, chapterNo, ch, spec, digests, prevTail, directives, prevScene);
            String draft = sceneService.generate(novelId, ch.id(), chapterNo, pack, spec.sceneNo());
            boolean ok = gateService.checkScene(novelId, ch.id(), sceneId, spec.sceneNo(), draft);
            if (!ok) {
                log.warn("场景 {} 门禁未过，带意见重写一次", spec.sceneNo());
                draft = sceneService.revise(novelId, ch.id(), sceneId, spec.sceneNo(),
                        draft, gateService.failureSummary(ch.id()), pack);
                ok = gateService.checkScene(novelId, ch.id(), sceneId, spec.sceneNo(), draft);
            }
            sceneDAO.updateGateStatus(sceneId, ok ? "PASSED" : "FAILED");
            if (ok) passedScenes++;
            prevScene = draft;
        }
        if (passedScenes < specs.size()) {
            log.error("第 {} 章场景通过 {}/{}，中止", chapterNo, passedScenes, specs.size());
            chapterDAO.updateStatus(ch.id(), "FAILED");
            return false;
        }

        // 3) 拼章 + 章级门禁（未过带意见修订一轮，复检通过才放行）
        StringJoiner joiner = new StringJoiner("\n\n");
        sceneDAO.findPassedDrafts(ch.id()).forEach(joiner::add);
        String fullText = joiner.toString();
        chapterDAO.saveFullText(ch.id(), fullText);
        chapterDAO.updateStatus(ch.id(), "GATE_MECHANICAL");
        if (!gateService.checkChapter(novelId, ch.id(), chapterNo, fullText, ch.budgetMin(), ch.budgetMax())) {
            // 断点重跑场景：库里已有上一轮修订过、且能过检的正文，直接复用，省一轮修订调用
            String stored = ch.fullText();
            if (stored != null && !stored.equals(fullText)
                    && gateService.checkChapter(novelId, ch.id(), chapterNo, stored, ch.budgetMin(), ch.budgetMax())) {
                log.info("第 {} 章复用已修订正文（{} 字符）", chapterNo, stored.length());
                fullText = stored;
                chapterDAO.saveFullText(ch.id(), fullText);
            } else {
                log.warn("第 {} 章章级门禁未过，带意见修订一轮", chapterNo);
                String revised = reviseChapter(novelId, ch, fullText);
                if (revised == null || revised.length() < fullText.length() * 0.6) {
                    log.error("第 {} 章修订稿长度异常（{} 字符），判失败", chapterNo,
                            revised == null ? 0 : revised.length());
                    chapterDAO.updateStatus(ch.id(), "FAILED");
                    return false;
                }
                fullText = revised;
                chapterDAO.saveFullText(ch.id(), fullText);
                if (!gateService.checkChapter(novelId, ch.id(), chapterNo, fullText, ch.budgetMin(), ch.budgetMax())) {
                    log.error("第 {} 章修订后仍未过章级门禁（报告见 gate_reports）", chapterNo);
                    chapterDAO.updateStatus(ch.id(), "FAILED");
                    return false;
                }
            }
        }

        // 4) 审批（auto 直过；manual 停在 PENDING_APPROVAL 等人）
        if ("manual".equals(approvalMode)) {
            chapterDAO.updateStatus(ch.id(), "PENDING_APPROVAL");
            log.info("第 {} 章待人工审批", chapterNo);
            return true;
        }

        // 5) digest
        digestService.digest(novelId, ch.id(), chapterNo, fullText);
        return true;
    }
}
