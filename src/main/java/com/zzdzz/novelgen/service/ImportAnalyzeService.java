package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.ImportAnalyzeTaskDO;
import com.zzdzz.novelgen.model.entity.NovelDO;
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
 * 导入书籍后的「解析链」：把素材库能通过 LLM 生成的东西（事实账/世界状态/伏笔提议、素材卡、世界观、文风规则、
 * 向量索引）与大纲/卷纲/章纲串成**可选、可续跑**的多步任务，用户勾选哪些跑哪些（默认全跑）。
 *
 * 运行形态与「样本深度解析」同构：全局单线程 runner（一本一本地跑，瓶颈在 LLM）+ 一行活跃任务表
 * （import_analyze_tasks，重复提交＝重置同一行）——单步动辄几十秒到十几分钟，不能挂在 HTTP 请求上。
 * 逐步 fail-open：某步失败只记该步 FAILED 并继续下一步（一步炸不该让整链白跑），链终态只反映「有没有未完成」
 * （全部 SUCCESS/SKIPPED = DONE，其余 = FAILED，前端按步渲染）。
 *
 * 「已有内容」的处置：每步可单独选**跳过 / 覆盖重做**（默认不跳过＝覆盖），只有 DIGESTS / CARDS / EMBEDDINGS
 * 三步的该开关有实际效果（这三步原本是「已有即跳过」），其余步的处置是固定的（大纲/世界观/规则覆盖、卷纲新增一卷、
 * 章纲把新卷章行入队）。开关随 steps 一起写进任务行，前端进度面板据此标出本次选择。
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
     * 固定口径：OUTLINE/WORLD/RULES 覆盖同名文档；VOLUME_PLAN 新增一卷；
     * CHAPTER_OUTLINES 把新规划那卷的章行批量入队（其中已有正文的章由生成侧守卫跳过）。
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
            case VOLUME_PLAN -> volumePlan(novelId, skipExisting);
            case CHAPTER_OUTLINES -> chapterOutlines(novelId);
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
     * 卷纲：规划「下一卷」（接在已有正文最末章之后；含前置卷复盘）。auto 模式直接落库，manual 模式出草稿。
     * 「已有内容」＝该段章节**已有规划行**（重复跑解析链时的常态）：
     * 覆盖（默认）＝**原地重规划那一卷**（同卷号、同起点，adopt 会软删该起点起的旧行再插新行）——
     * 否则每重跑一次就往后编一个新卷号、把上一卷的规划行顶掉（真库踩过：第 1 卷被第 2 卷顶掉）；
     * 跳过＝已有规划行覆盖到起点就完全不动。
     */
    private StepResult volumePlan(long novelId, boolean skipExisting) {
        NovelDO novel = novelData.getById(novelId);
        Integer lastWithText = chapterData.maxChapterWithText(novelId);
        int from = (lastWithText == null ? 0 : lastWithText) + 1;
        PlanTarget t = planTarget(chapterData.maxPlannedChapterNo(novelId), from,
                chapterData.maxVolumeNo(novelId), skipExisting);
        if (t.skip()) {
            return new StepResult("SKIPPED",
                    "第 " + t.volNo() + " 卷已规划到第 " + chapterData.maxPlannedChapterNo(novelId)
                            + " 章（自第 " + from + " 章起），本次选了跳过——未动已有卷纲",
                    Map.of("volNo", t.volNo(), "from", from));
        }
        Map<String, Object> r = planningService.autoPlan(novelId, t.volNo(), from, null, null);
        boolean adopted = Boolean.TRUE.equals(r.get("adopted"));
        Object arc = r.get("arc");
        Object rows = r.get("rows");
        int rowCount = rows instanceof List<?> list ? list.size() : 0;
        if (!adopted) {
            return new StepResult("SUCCESS",
                    "第 " + t.volNo() + " 卷草稿已生成（manual 模式，待你在规划页采纳）",
                    Map.of("volNo", t.volNo(), "rows", rowCount));
        }
        return new StepResult("SUCCESS", "第 " + t.volNo() + " 卷" + (t.replan() ? "已原地重规划：" : "已落库：")
                + (arc == null ? "" : arc) + "，" + rowCount + " 章（自第 " + from + " 章起）",
                Map.of("volNo", t.volNo(), "rows", rowCount, "from", from));
    }

    /** 卷纲步的目标：{volNo, replan, skip}。已有规划行覆盖到起点＝重规划同一卷，否则新增一卷。 */
    record PlanTarget(int volNo, boolean replan, boolean skip) {
    }

    static PlanTarget planTarget(Integer maxPlannedChapterNo, int from, int maxVolumeNo, boolean skipExisting) {
        boolean covered = maxPlannedChapterNo != null && maxPlannedChapterNo >= from;
        if (!covered) {
            return new PlanTarget(maxVolumeNo + 1, false, false);   // 空档：新增一卷（首次跑就是第 1 卷）
        }
        return new PlanTarget(maxVolumeNo, true, skipExisting);      // 已有覆盖：原地重规划该卷，或按选择跳过
    }

    /** 章纲要出纲的「规划卷」＝号最大的非导入成稿卷；只有导入正文（无规划卷）时返回 null。 */
    static Integer plannedVolumeNo(List<ChapterDO> chapters) {
        return chapters.stream()
                .filter(c -> c.getVolumeNo() != null && !NovelService.IMPORT_VOLUME_ARC.equals(c.getArc()))
                .map(ChapterDO::getVolumeNo)
                .max(Integer::compareTo)
                .orElse(null);
    }

    /** 章纲：把最近规划出的那一卷的章行批量入队（本链只提交，进度看工作台队列）。
     *  **导入成稿卷不算目标卷**——导入书的 volume_no=1/arc=导入正文 是原文（正文已成），出章纲既无意义也会被守卫跳过；
     *  只导入、还没规划下一卷的书，本步直接 SKIPPED 并在消息里说明，不再入队一个「全跳过」的空任务。 */
    private StepResult chapterOutlines(long novelId) {
        List<ChapterDO> chapters = chapterData.listSummariesByNovel(novelId);
        if (chapters.isEmpty()) {
            return new StepResult("SKIPPED", "本书还没有规划章行", Map.of());
        }
        Integer volNo = plannedVolumeNo(chapters);
        if (volNo == null) {
            return new StepResult("SKIPPED",
                    "本书只有导入成稿正文（尚无规划卷）——成稿章不需要章纲，续写卷规划后才出纲", Map.of());
        }
        List<Integer> nos = chapters.stream()
                .filter(c -> volNo.equals(c.getVolumeNo()))
                .map(ChapterDO::getChapterNo)
                .sorted()
                .toList();
        if (nos.isEmpty()) {
            return new StepResult("SKIPPED", "没有可批量生成章纲的章行（先跑卷纲）", Map.of());
        }
        int from = nos.get(0);
        int to = nos.get(nos.size() - 1);
        NovelDO novel = novelData.getById(novelId);
        long taskId = queueService.submitOutline(novelId, novel.getTitle(), from, to, null);
        return new StepResult("SUCCESS",
                "第 " + from + "–" + to + " 章章纲已入队（任务 " + taskId + "，进度见工作台）；"
                        + "本步只给新规划那卷出纲，导入成稿章不重出章纲（正文已成、无需规划）",
                Map.of("from", from, "to", to, "taskId", taskId));
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
