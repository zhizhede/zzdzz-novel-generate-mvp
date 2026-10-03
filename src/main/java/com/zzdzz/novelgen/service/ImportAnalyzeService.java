package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.ImportAnalyzeTaskDO;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 导入书籍后的「解析链」：把导入正文里**已经存在**的东西用 LLM 抽出来，落成素材库资产
 * （事实账/世界状态/伏笔提议、大纲、素材卡、世界观、文风规则、向量索引、**已有章的章纲反推**）。
 *
 * **只解析，不规划**（2026-10-03 用户定调）：本链**不产生新章、不规划续写卷**——导入书的解析不该顺手
 * 规划出一卷续写，那属于规划页（「AI 规划下一卷」）与生成管线。原先的 `VOLUME_PLAN`（规划下一卷）与
 * 旧的 `CHAPTER_OUTLINES`（把新规划卷入生成队列）两步已移除；现在链里的 `DERIVE_CHAPTER_OUTLINES` 是
 * **反推**——读已有正文，把它实际怎么分场拆出来，不动正文与章状态。
 *
 * 运行形态与「样本深度解析」同构：全局单线程 runner（一本一本地跑，瓶颈在 LLM）+ 一行活跃任务表
 * （import_analyze_tasks，重复提交＝重置同一行）——单步动辄几十秒到十几分钟，不能挂在 HTTP 请求上。
 * 逐步 fail-open：某步失败只记该步 FAILED 并继续下一步（一步炸不该让整链白跑），链终态只反映「有没有未完成」
 * （全部 SUCCESS/SKIPPED = DONE，其余 = FAILED，前端按步渲染）。
 *
 * 「已有内容」的处置：每步可单独选**跳过 / 覆盖重做**（默认不跳过＝覆盖），DIGESTS / CARDS / EMBEDDINGS /
 * DERIVE_CHAPTER_OUTLINES 四步的该开关有实际效果，其余步的处置是固定的（大纲/世界观/规则覆盖同名文档）。
 * 开关随 steps 一起写进任务行，前端进度面板据此标出本次选择。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportAnalyzeService {

    /** 逐章事实账的章数上限：导入书动辄上百章，全量重跑既慢又贵，默认取末尾 N 章（续写前情够用）。 */
    static final int DIGEST_CHAPTER_CAP = 20;
    /**
     * 章纲反推的章数上限：单章 LLM 约几十秒，导入书上百章不能无上限全跑——取前 N 章（章号升序），
     * 超出部分在步骤消息里明说，下轮（或调高此值）再补。
     */
    static final int DERIVE_OUTLINE_CHAPTER_CAP = 30;
    /** 单步结果里消息长度上限（任务行 message 列有限长，且前端只展示一行）。 */
    private static final int MESSAGE_MAX = 300;

    private final NovelDataService novelData;
    private final ChapterDataService chapterData;
    private final ImportAnalyzeTaskDataService taskData;
    private final BookAssetExtractService bookAssets;
    private final NovelService novelService;
    private final GenrePresetService genrePresetService;
    private final EmbeddingService embeddingService;
    private final OutlineService outlineService;
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
     * skipExistingKeys = 这些步对「已有内容」选择**跳过**（默认不传/空 = 不跳过 = 覆盖重做）。
     * 只有 DIGESTS / CARDS / EMBEDDINGS 三步的该开关有实际效果，其余步的已有内容处置是固定的（见 runStep）。
     */
    public long submit(long novelId, List<String> stepKeys, List<String> skipExistingKeys) {
        requireNovel(novelId);
        List<ImportAnalyzeStep> steps = ImportAnalyzeStep.ordered(stepKeys);
        if (steps.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "没有勾选任何解析项");
        }
        // 只有同时被勾选的步才谈得上「跳过已有」；未知键在此被 ordered 丢弃
        Set<ImportAnalyzeStep> skip = effectiveSkip(steps, skipExistingKeys);
        ImportAnalyzeTaskDO alive = taskData.findAliveByNovel(novelId);
        if (alive != null && ("QUEUED".equals(alive.getStatus()) || "RUNNING".equals(alive.getStatus()))) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "这本书已有解析任务在跑（" + alive.getStatus() + "，当前 " + alive.getCurrentStep() + "），请等它跑完");
        }
        long taskId = taskData.resetForRun(novelId, writeSteps(steps, skip));
        runner.submit(() -> safeRun(taskId, novelId, steps, skip));
        log.info("导入解析链已入队：novelId={} 步骤={} 跳过已有={} taskId={}", novelId,
                steps.stream().map(ImportAnalyzeStep::wire).toList(),
                skip.stream().map(ImportAnalyzeStep::wire).toList(), taskId);
        return taskId;
    }

    /** 解析进度/结果（无任务返回 null，前端据此隐藏面板）。 */
    public ImportAnalyzeStatusVO status(long novelId) {
        ImportAnalyzeTaskDO task = taskData.findAliveByNovel(novelId);
        return task == null ? null : toStatus(task);
    }

    // ===== 执行 =====

    private void safeRun(long taskId, long novelId, List<ImportAnalyzeStep> steps, Set<ImportAnalyzeStep> skip) {
        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;
        try {
            taskData.markCurrent(taskId, steps.get(0).wire());
            for (ImportAnalyzeStep step : steps) {
                taskData.markCurrent(taskId, step.wire());
                long start = System.currentTimeMillis();
                StepResult r;
                try {
                    r = runStep(novelId, step, skip.contains(step));
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

    /**
     * 单步执行：返回该步的终态（SUCCESS / SKIPPED / 部分成功记 SUCCESS 但 message 说明）。
     * skipExisting 只对三步有意义（其余步的已有内容处置是固定的）：
     * ①DIGESTS：默认重算已有事实账的章，选跳过则只补缺的；
     * ②CARDS：默认用新结果更新已有同类同名卡，选跳过则保留（人工写过的优先）；
     * ③EMBEDDINGS：默认清掉本书旧向量全量重嵌（事实账/卡被覆盖后旧向量即陈旧），选跳过则只补缺的。
     * 固定口径：OUTLINE/WORLD/RULES 覆盖同名文档。
     */
    private StepResult runStep(long novelId, ImportAnalyzeStep step, boolean skipExisting) {
        return switch (step) {
            case DIGESTS -> digests(novelId, !skipExisting);
            case OUTLINE -> {
                String outline = bookAssets.synthesizeOutline(novelId);
                yield new StepResult("SUCCESS", "已写入大纲 " + outline.length() + " 字", Map.of("chars", outline.length()));
            }
            case CARDS -> {
                BookAssetExtractService.CardWriteResult w = bookAssets.extractCards(novelId, !skipExisting);
                int n = w.created() + w.updated();
                String msg;
                if (n == 0) {
                    msg = skipExisting ? "无新增（已有同名同类卡，本次选了跳过）" : "无变化（模型没给出新卡）";
                } else {
                    msg = skipExisting ? "素材卡新增 " + w.created() + " 张（已有同类卡保留）"
                            : "素材卡新增 " + w.created() + " 张、覆盖更新 " + w.updated() + " 张";
                }
                yield new StepResult("SUCCESS", msg, Map.of("created", w.created(), "updated", w.updated()));
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
                int n = embeddingService.backfillNovel(novelId, !skipExisting);
                yield new StepResult("SUCCESS",
                        (skipExisting ? "新增向量 " : "向量已重算 ") + n + " 条", Map.of("created", n));
            }
            case DERIVE_CHAPTER_OUTLINES -> deriveChapterOutlines(novelId, !skipExisting);
        };
    }

    /** 事实账（含世界状态与伏笔提议）：取末尾 N 章逐章跑；overwrite=false 时已有事实账的章跳过。 */
    private StepResult digests(long novelId, boolean overwrite) {
        List<ChapterDO> chapters = chapterData.listSummariesByNovel(novelId);
        if (chapters.isEmpty()) {
            return new StepResult("SKIPPED", "本书还没有章节", Map.of());
        }
        int recent = Math.min(chapters.size(), DIGEST_CHAPTER_CAP);
        NovelService.DigestBackfillVO r = novelService.backfillDigests(novelId, recent, overwrite);
        String note = "已处理 " + r.digested() + "/" + r.requested() + " 章（取末尾 " + recent + " 章，"
                + (overwrite ? "覆盖重做" : "只补缺失") + "）";
        if (r.skipped() > 0) {
            note += "，跳过已有 " + r.skipped() + " 章";
        }
        if (r.requested() - r.digested() - r.skipped() > 0) {
            note += "；失败章可稍后重试";
        }
        return new StepResult("SUCCESS", note, Map.of("digested", r.digested(), "attempted", r.requested(),
                "skipped", r.skipped()));
    }

    /**
     * 章纲反推：把本书**已有正文**的章按实际分场拆出来（写 outline_yaml + chapter_scenes）。
     * 事后描述而非写作规划——走 OutlineService 的保全状态分支，正文/章状态/门禁报告都不动。
     * 取前 {@link #DERIVE_OUTLINE_CHAPTER_CAP} 章（章号升序，单章几十秒不做无上限全跑）；单章失败只记数。
     */
    private StepResult deriveChapterOutlines(long novelId, boolean overwrite) {
        OutlineService.DeriveResult r = outlineService.deriveChapterOutlines(
                novelId, overwrite, DERIVE_OUTLINE_CHAPTER_CAP);
        if (r.bookChapters() == 0) {
            return new StepResult("SKIPPED", "本书还没有带正文的章，没有可反推的章纲", Map.of());
        }
        String note = "已反推 " + r.derived() + "/" + r.requested() + " 章章纲（场景拆解，正文与章状态未动）；"
                + (overwrite ? "覆盖重写已有章纲" : "只补没有章纲的章");
        if (r.skipped() > 0) {
            note += "，跳过已有 " + r.skipped() + " 章";
        }
        if (r.failed() > 0) {
            note += "，" + r.failed() + " 章失败（可稍后重跑）";
        }
        if (r.bookChapters() > r.requested()) {
            note += "；本书 " + r.bookChapters() + " 章有正文，本次只取前 " + r.requested() + " 章";
        }
        return new StepResult("SUCCESS", note, Map.of("derived", r.derived(), "skipped", r.skipped(),
                "failed", r.failed(), "requested", r.requested(), "bookChapters", r.bookChapters()));
    }

    // ===== 助手 =====

    /**
     * 「跳过已有」的有效集合：只保留**本次勾选**的步（未勾的步谈不上跳过），未知键忽略。
     * 其余步一律按覆盖/固定口径执行（默认不跳过——勾了就该真做，这是用户明确的口径）。
     */
    static Set<ImportAnalyzeStep> effectiveSkip(List<ImportAnalyzeStep> steps, List<String> skipExistingKeys) {
        Set<ImportAnalyzeStep> skip = new LinkedHashSet<>(ImportAnalyzeStep.ordered(skipExistingKeys));
        skip.retainAll(steps);
        return skip;
    }

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

    private ImportAnalyzeStatusVO toStatus(ImportAnalyzeTaskDO task) {
        List<ImportAnalyzeStatusVO.StepResultVO> rows = new ArrayList<>();
        for (var n : readJsonArray(task.getDoneSteps())) {
            rows.add(new ImportAnalyzeStatusVO.StepResultVO(
                    n.path("step").asText(""), n.path("label").asText(""),
                    n.path("status").asText(""), n.path("message").asText(""),
                    n.path("elapsedMs").asLong(0),
                    n.path("counts").isObject() ? mapper.convertValue(n.path("counts"), Map.class) : Map.of()));
        }
        List<String> planned = new ArrayList<>();
        List<String> skipExisting = new ArrayList<>();
        for (var n : readJsonArray(task.getSteps())) {
            ImportAnalyzeStep s = ImportAnalyzeStep.of(readStepKey(n));
            if (s != null) {
                planned.add(s.wire());
                if (readStepSkip(n)) {
                    skipExisting.add(s.wire());
                }
            }
        }
        return new ImportAnalyzeStatusVO(task.getId(), task.getNovelId(), task.getStatus(), task.getCurrentStep(),
                planned, skipExisting, rows, task.getMessage(), task.getUpdateTime());
    }

    /** 步骤项的键：新格式是对象 {key,skipExisting}，旧格式（本次之前入队的行）是裸字符串。 */
    private static String readStepKey(com.fasterxml.jackson.databind.JsonNode n) {
        return n.isTextual() ? n.asText("") : n.path("key").asText("");
    }

    private static boolean readStepSkip(com.fasterxml.jackson.databind.JsonNode n) {
        return !n.isTextual() && n.path("skipExisting").asBoolean(false);
    }

    /** 把「勾选的步 + 其中选跳过的步」写成任务行 steps（对象数组，前端进度与审计都靠它）。 */
    private String writeSteps(List<ImportAnalyzeStep> steps, Set<ImportAnalyzeStep> skip) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ImportAnalyzeStep s : steps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", s.wire());
            row.put("skipExisting", skip.contains(s));
            rows.add(row);
        }
        return writeJson(rows);
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
