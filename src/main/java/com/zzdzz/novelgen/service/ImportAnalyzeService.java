package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.model.dto.ChapterDTO;
import com.zzdzz.novelgen.model.dto.ImportAnalyzeTaskDTO;
import com.zzdzz.novelgen.model.dto.NovelDTO;
import com.zzdzz.novelgen.model.enums.ImportAnalyzeStep;
import com.zzdzz.novelgen.model.vo.ImportAnalyzeStatusVO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ImportAnalyzeTaskDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 导入书籍后的「解析链」：把素材库能通过 LLM 生成的东西（事实账/世界状态/伏笔提议、素材卡、世界观、文风规则、
 * 向量索引）与大纲/卷纲/章纲串成**可选、可续跑**的多步任务，用户勾选哪些跑哪些（默认全跑）。
 *
 * 运行形态与「样本深度解析」同构：全局单线程 runner（一本一本地跑，瓶颈在 LLM）+ 一行活跃任务表
 * （import_analyze_tasks，重复提交＝重置同一行）——单步动辄几十秒到十几分钟，不能挂在 HTTP 请求上。
 * 逐步 fail-open：某步失败只记该步 FAILED 并继续下一步（一步炸不该让整链白跑），链终态只反映「有没有未完成」
 * （全部 SUCCESS/SKIPPED = DONE，其余 = FAILED，前端按步渲染）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportAnalyzeService {

    /** 逐章事实账的章数上限：导入书动辄上百章，全量重跑既慢又贵，默认取末尾 N 章（续写前情够用）。 */
    static final int DIGEST_CHAPTER_CAP = 20;
    /** 单步结果里消息长度上限（任务行 message 列有限长，且前端只展示一行）。 */
    private static final int MESSAGE_MAX = 300;

    private final NovelDataService novelData;
    private final ChapterDataService chapterData;
    private final ImportAnalyzeTaskDataService taskData;
    private final BookAssetExtractService bookAssets;
    private final NovelService novelService;
    private final PlanningService planningService;
    private final GenrePresetService genrePresetService;
    private final EmbeddingService embeddingService;
    private final GenerationQueueService queueService;
    private final ObjectMapper mapper;

    /** 全局串行 runner：一次只跑一本书的解析链（可续跑靠任务行，不靠线程）。 */
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "import-analyze-runner");
        t.setDaemon(true);
        return t;
    });

    // ===== 对外 API =====

    /**
     * 提交解析链（勾选步骤键；空 = 不解析）。已在跑则拒绝（A0006），否则重置任务行并入队。
     * 前端在导入成功后立刻调它，也允许对任何已有书重跑（缺的补上、已有的按各自幂等口径覆盖或跳过）。
     */
    public long submit(long novelId, List<String> stepKeys) {
        requireNovel(novelId);
        List<ImportAnalyzeStep> steps = ImportAnalyzeStep.ordered(stepKeys);
        if (steps.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "没有勾选任何解析项");
        }
        ImportAnalyzeTaskDTO alive = taskData.findAliveByNovel(novelId);
        if (alive != null && ("QUEUED".equals(alive.getStatus()) || "RUNNING".equals(alive.getStatus()))) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "这本书已有解析任务在跑（" + alive.getStatus() + "，当前 " + alive.getCurrentStep() + "），请等它跑完");
        }
        String stepsJson = writeJson(steps.stream().map(ImportAnalyzeStep::wire).toList());
        long taskId = taskData.resetForRun(novelId, stepsJson);
        runner.submit(() -> safeRun(taskId, novelId, steps));
        log.info("导入解析链已入队：novelId={} 步骤={} taskId={}", novelId,
                steps.stream().map(ImportAnalyzeStep::wire).toList(), taskId);
        return taskId;
    }

    /** 解析进度/结果（无任务返回 null，前端据此隐藏面板）。 */
    public ImportAnalyzeStatusVO status(long novelId) {
        ImportAnalyzeTaskDTO task = taskData.findAliveByNovel(novelId);
        return task == null ? null : toStatus(task, null);
    }

    // ===== 执行 =====

    private void safeRun(long taskId, long novelId, List<ImportAnalyzeStep> steps) {
        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;
        try {
            taskData.markCurrent(taskId, steps.get(0).wire());
            for (ImportAnalyzeStep step : steps) {
                taskData.markCurrent(taskId, step.wire());
                long start = System.currentTimeMillis();
                StepResult r;
                try {
                    r = runStep(novelId, step);
                } catch (Exception e) {
                    failed++;
                    r = new StepResult("FAILED", brief(e), Map.of());
                    log.warn("解析链步骤失败（继续下一步）：novelId={} step={} {}", novelId, step.wire(), e.toString());
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("step", step.wire());
                row.put("label", step.label());
                row.put("status", r.status());
                row.put("message", r.message());
                row.put("counts", r.counts());
                row.put("elapsedMs", System.currentTimeMillis() - start);
                results.add(row);
                taskData.appendStep(taskId, writeJson(results));   // 每一步都落库：断点事实而不是最后才写
            }
        } catch (Exception e) {
            log.error("解析链异常中断：novelId={} taskId={}", novelId, taskId, e);
            taskData.finish(taskId, "FAILED", "解析链异常中断：" + brief(e));
            return;
        }
        String summary = summarize(results, failed);
        taskData.finish(taskId, failed == 0 ? "DONE" : "FAILED", summary);
        log.info("导入解析链结束：novelId={} {}", novelId, summary);
    }

    /** 单步执行：返回该步的终态（SUCCESS / SKIPPED / 部分成功记 SUCCESS 但 message 说明）。 */
    private StepResult runStep(long novelId, ImportAnalyzeStep step) {
        return switch (step) {
            case DIGESTS -> digests(novelId);
            case OUTLINE -> {
                String outline = bookAssets.synthesizeOutline(novelId);
                yield new StepResult("SUCCESS", "已写入大纲 " + outline.length() + " 字", Map.of("chars", outline.length()));
            }
            case CARDS -> {
                int created = bookAssets.extractCards(novelId);
                yield new StepResult("SUCCESS", created == 0 ? "无新增（已有卡或摘要不足）" : "新增素材卡 " + created + " 张",
                        Map.of("created", created));
            }
            case WORLD -> {
                String world = bookAssets.synthesizeWorld(novelId);
                yield new StepResult("SUCCESS", "已写入世界观 " + world.length() + " 字", Map.of("chars", world.length()));
            }
            case RULES -> {
                String rules = genrePresetService.extractRulesForNovel(novelId);
                int len = rules == null ? 0 : rules.length();
                yield new StepResult("SUCCESS", "已写回风格包规则 " + len + " 字", Map.of("chars", len));
            }
            case EMBEDDINGS -> {
                int n = embeddingService.backfillNovel(novelId);
                yield new StepResult("SUCCESS", "新增向量 " + n + " 条", Map.of("created", n));
            }
            case VOLUME_PLAN -> volumePlan(novelId);
            case CHAPTER_OUTLINES -> chapterOutlines(novelId);
        };
    }

    /** 事实账（含世界状态与伏笔提议）：取末尾 N 章逐章跑，已有事实账的章自动跳过。 */
    private StepResult digests(long novelId) {
        List<ChapterDTO> chapters = chapterData.listSummariesByNovel(novelId);
        if (chapters.isEmpty()) {
            return new StepResult("SKIPPED", "本书还没有章节", Map.of());
        }
        int recent = Math.min(chapters.size(), DIGEST_CHAPTER_CAP);
        NovelService.DigestBackfillVO r = novelService.backfillDigests(novelId, recent);
        String note = "已补 " + r.digested() + "/" + r.requested() + " 章（取末尾 " + recent + " 章）";
        if (r.digested() < r.requested()) {
            note += "；失败章可稍后重试";
        }
        return new StepResult("SUCCESS", note, Map.of("digested", r.digested(), "attempted", r.requested()));
    }

    /** 卷纲：规划「下一卷」（接在已有正文最末章之后；含前置卷复盘）。auto 模式直接落库，manual 模式出草稿。 */
    private StepResult volumePlan(long novelId) {
        NovelDTO novel = novelData.getById(novelId);
        Integer lastWithText = chapterData.maxChapterWithText(novelId);
        int from = (lastWithText == null ? 0 : lastWithText) + 1;
        int volNo = chapterData.maxVolumeNo(novelId) + 1;
        Map<String, Object> r = planningService.autoPlan(novelId, volNo, from, null, null);
        boolean adopted = Boolean.TRUE.equals(r.get("adopted"));
        Object arc = r.get("arc");
        Object rows = r.get("rows");
        int rowCount = rows instanceof List<?> list ? list.size() : 0;
        if (!adopted) {
            return new StepResult("SUCCESS",
                    "第 " + volNo + " 卷草稿已生成（manual 模式，待你在规划页采纳）", Map.of("volNo", volNo, "rows", rowCount));
        }
        return new StepResult("SUCCESS", "第 " + volNo + " 卷已落库：" + (arc == null ? "" : arc) + "，"
                + rowCount + " 章（自第 " + from + " 章起）", Map.of("volNo", volNo, "rows", rowCount, "from", from));
    }

    /** 章纲：把最近规划出的那一卷的章行批量入队（本链只提交，进度看工作台队列）。 */
    private StepResult chapterOutlines(long novelId) {
        List<ChapterDTO> chapters = chapterData.listSummariesByNovel(novelId);
        if (chapters.isEmpty()) {
            return new StepResult("SKIPPED", "本书还没有规划章行", Map.of());
        }
        int volNo = chapterData.maxVolumeNo(novelId);
        List<Integer> nos = chapters.stream()
                .filter(c -> volNo > 0 && c.getVolumeNo() != null && c.getVolumeNo() == volNo)
                .map(ChapterDTO::getChapterNo)
                .sorted()
                .toList();
        if (nos.isEmpty()) {
            return new StepResult("SKIPPED", "没有可批量生成章纲的章行（先跑卷纲）", Map.of());
        }
        int from = nos.get(0);
        int to = nos.get(nos.size() - 1);
        NovelDTO novel = novelData.getById(novelId);
        long taskId = queueService.submitOutline(novelId, novel.getTitle(), from, to, null);
        return new StepResult("SUCCESS", "第 " + from + "–" + to + " 章章纲已入队（任务 " + taskId + "，进度见工作台）",
                Map.of("from", from, "to", to, "taskId", taskId));
    }

    // ===== 助手 =====

    /** 逐步结果 → 人话汇总（前端标题行 + 任务行 message）。 */
    String summarize(List<Map<String, Object>> results, int failed) {
        long skipped = results.stream().filter(r -> "SKIPPED".equals(r.get("status"))).count();
        long ok = results.size() - failed - skipped;
        StringBuilder sb = new StringBuilder("解析链结束：成功 ").append(ok).append(" 步");
        if (skipped > 0) {
            sb.append("、跳过 ").append(skipped).append(" 步");
        }
        if (failed > 0) {
            sb.append("、失败 ").append(failed).append(" 步（详见各步说明）");
        }
        return sb.toString();
    }

    private ImportAnalyzeStatusVO toStatus(ImportAnalyzeTaskDTO task, List<ImportAnalyzeStep> steps) {
        List<ImportAnalyzeStatusVO.StepResultVO> rows = new ArrayList<>();
        for (var n : readJsonArray(task.getDoneSteps())) {
            rows.add(new ImportAnalyzeStatusVO.StepResultVO(
                    n.path("step").asText(""), n.path("label").asText(""),
                    n.path("status").asText(""), n.path("message").asText(""),
                    n.path("elapsedMs").asLong(0),
                    n.path("counts").isObject() ? mapper.convertValue(n.path("counts"), Map.class) : Map.of()));
        }
        List<String> planned = new ArrayList<>();
        for (var n : readJsonArray(task.getSteps())) {
            ImportAnalyzeStep s = ImportAnalyzeStep.of(n.asText(""));
            if (s != null) {
                planned.add(s.wire());
            }
        }
        return new ImportAnalyzeStatusVO(task.getId(), task.getNovelId(), task.getStatus(), task.getCurrentStep(),
                planned, rows, task.getMessage(), task.getUpdateTime());
    }

    private List<com.fasterxml.jackson.databind.JsonNode> readJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            var node = mapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<com.fasterxml.jackson.databind.JsonNode> out = new ArrayList<>();
            node.forEach(out::add);
            return out;
        } catch (Exception e) {
            log.warn("解析任务 JSON 解析失败（按空处理）：{}", e.getMessage());
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("解析任务序列化失败", e);
        }
    }

    private static String brief(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String one = m.replaceAll("\\s+", " ").strip();
        return one.length() > MESSAGE_MAX ? one.substring(0, MESSAGE_MAX) + "…" : one;
    }

    private void requireNovel(long novelId) {
        if (novelData.getById(novelId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在: " + novelId);
        }
    }

    /** 单步执行结果（内部结构）。 */
    private record StepResult(String status, String message, Map<String, Object> counts) {
    }
}
