package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.dto.ImportedSampleDTO;
import com.zzdzz.novelgen.model.dto.PresetCorpusDTO;
import com.zzdzz.novelgen.model.dto.SampleCardDTO;
import com.zzdzz.novelgen.model.dto.SampleParseTaskDTO;
import com.zzdzz.novelgen.model.dto.SamplePlotNodeDTO;
import com.zzdzz.novelgen.model.vo.SampleAssetsVO;
import com.zzdzz.novelgen.model.vo.SampleCardVO;
import com.zzdzz.novelgen.model.vo.SampleParamsVO;
import com.zzdzz.novelgen.model.vo.SampleParseStatusVO;
import com.zzdzz.novelgen.model.vo.SamplePlotVO;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import com.zzdzz.novelgen.service.data.PresetCorpusDataService;
import com.zzdzz.novelgen.service.data.SampleCardDataService;
import com.zzdzz.novelgen.service.data.SampleParseTaskDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导入小说深度解析（样本资产化）：原文重组 → 章切分 → 逐章 LLM 摘要/场景拆解/实体抽取（并发、章行幂等=断点续跑）
 * → 跨章实体归并 → 卷级汇总（有卷标记时）→ 全书大纲 + 世界观合成。
 * FAST=抽样快速骨架（前 N 章），FULL=全书完整解析；FAST 后升级 FULL 自然续跑已析章。
 * 与生成队列完全独立：独立 runner（全局串行）+ 逐章并发池；LLM 调用照常进 llm_call_log 记账。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SampleParseService {

    /** 单章进 LLM 的正文上限：超出按段二分逐段解析后机械合并（摘要串联/实体拼接）。 */
    static final int CHAPTER_LLM_MAX_CHARS = 12_000;

    /** 章标题行：第N章/第N回（N 为数字或汉字数词），行尾可带题名；整行 ≤70 字防误吞正文。 */
    private static final Pattern CHAPTER_LINE = Pattern.compile(
            "^(第[0-9零〇一二两三四五六七八九十百千万]+[章回])[：:、\\s.．]?\\s*([^\\n]{0,60})$");
    /** 特殊章：序章/楔子/引子/尾声/终章/番外。 */
    private static final Pattern SPECIAL_CHAPTER_LINE = Pattern.compile(
            "^(序章|楔子|引子|尾声|终章|番外[篇外]?[^\\n]{0,30})$");
    /** 卷标题行：第N卷/部/集。 */
    private static final Pattern VOLUME_LINE = Pattern.compile(
            "^第[0-9零〇一二两三四五六七八九十百千万]+[卷部集][：:、\\s.．]?\\s*([^\\n]{0,50})$");
    /** 语料块标题后缀：书名·块N。 */
    private static final Pattern BLOCK_NO = Pattern.compile("·块(\\d+)$");

    private final ImportedSampleDataService sampleData;
    private final PresetCorpusDataService corpusData;
    private final SampleParseTaskDataService taskData;
    private final SamplePlotNodeDataService plotData;
    private final SampleCardDataService cardData;
    private final LlmPort llm;
    private final LlmJson llmJson;
    private final PromptTemplateService promptTemplates;
    private final TuningService tuning;
    private final StageLog stages;
    private final ObjectMapper mapper;

    /** 全局串行 runner：一本一本地解析（速率瓶颈在 LLM，逐章并发已用满预算）。 */
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sample-parse-runner");
        t.setDaemon(true);
        return t;
    });

    // ===== 对外 API =====

    /**
     * 提交解析（FAST/FULL）。RUNNING/QUEUED 中拒 A0006；FAST→FULL 升级复用任务行，
     * 已析章行（plot node level=chapter）自然跳过即断点续跑。
     */
    public long submitParse(long sampleId, String mode) {
        String m = requireMode(mode);
        requireSample(sampleId);
        SampleParseTaskDTO alive = taskData.findAliveBySample(sampleId);
        if (alive != null && (alive.getStatus().equals("QUEUED") || alive.getStatus().equals("RUNNING"))) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该样本解析进行中（" + alive.getStatus() + "），请等待完成");
        }
        long taskId = taskData.resetForRun(sampleId, m);
        runner.submit(() -> safeRun(taskId, sampleId, m));
        stages.emit(null, StageLog.Stage.SAMPLE_PARSE, StageLog.Phase.QUEUED,
                Map.of("sampleId", sampleId, "mode", m, "taskId", taskId));
        return taskId;
    }

    /** 断点续跑：FAILED/INTERRUPTED 任务从缺口续（已析章跳过）。 */
    public long resumeParse(long sampleId) {
        requireSample(sampleId);
        SampleParseTaskDTO alive = taskData.findAliveBySample(sampleId);
        if (alive == null || !(alive.getStatus().equals("FAILED") || alive.getStatus().equals("INTERRUPTED"))) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "无可续跑的解析任务（当前状态：" + (alive == null ? "无任务" : alive.getStatus()) + "）");
        }
        long taskId = alive.getId();
        taskData.casStatus(taskId, alive.getStatus(), "QUEUED");
        String mode = alive.getMode();
        runner.submit(() -> safeRun(taskId, sampleId, mode));
        stages.emit(null, StageLog.Stage.SAMPLE_PARSE, StageLog.Phase.QUEUED,
                Map.of("sampleId", sampleId, "mode", mode, "taskId", taskId, "resume", true));
        return taskId;
    }

    /** 解析任务状态（含资产计数，前端进度条与按钮态）。 */
    public SampleParseTaskDTO parseStatus(long sampleId) {
        return taskData.findAliveBySample(sampleId);
    }

    /** 章切分结果：一章一行的原文分段（纯函数，可单测）。 */
    record ChapterSeg(int seq, String title, String text, int volumeSeq, boolean pseudo) {}

    /**
     * 整本原文按章标题行切段：识别 第N章/回 与 序章/楔子等特殊章；第N卷/部/集 行只作卷界标。
     * 章标记覆盖不足（章 &lt;3 或覆盖字数 &lt;40%）时回退伪章（~3200 字一段，同切块口径）。
     */
    static List<ChapterSeg> splitChapters(String text) {
        List<ChapterSeg> chapters = new ArrayList<>();
        int volumeSeq = 0;
        int volumeCount = 0;
        int chapterVolume = 0;
        StringBuilder cur = new StringBuilder();
        String curTitle = null;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (VOLUME_LINE.matcher(line).matches()) {
                volumeCount++;
                volumeSeq = volumeCount;
                continue;
            }
            boolean isChapter = CHAPTER_LINE.matcher(line).matches()
                    || SPECIAL_CHAPTER_LINE.matcher(line).matches();
            if (isChapter && line.length() <= 70) {
                if (cur.length() > 0) {
                    chapters.add(new ChapterSeg(chapters.size() + 1, curTitle, cur.toString(), chapterVolume, false));
                    cur.setLength(0);
                }
                curTitle = line;
                // 章的卷号以章开始时的卷界为准（章内容跨过卷标记不改属卷）
                chapterVolume = volumeSeq;
                continue;
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(line);
        }
        if (cur.length() > 0) {
            chapters.add(new ChapterSeg(chapters.size() + 1, curTitle, cur.toString(), chapterVolume, false));
        }
        if (chapters.isEmpty()) {
            return List.of();
        }
        int covered = chapters.stream().mapToInt(c -> c.text().length()).sum();
        if (chapters.size() < 3 || covered < text.length() * 0.4) {
            return pseudoChapters(text);
        }
        return chapters;
    }

    /** 无章标记回退：按 ~3200 字伪章切段（复用语料切块口径）；巨段（整章一行等）再硬切到同粒度。 */
    private static List<ChapterSeg> pseudoChapters(String text) {
        List<String> units = new ArrayList<>();
        for (String chunk : GenrePresetService.chunkNovel(text)) {
            if (chunk.length() <= 6000) {
                units.add(chunk);
            } else {
                units.addAll(splitBySize(chunk, 3200));
            }
        }
        List<ChapterSeg> out = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            out.add(new ChapterSeg(i + 1, null, units.get(i), 0, true));
        }
        return out;
    }

    /** 由语料块重组原文：title 形如 书名·块N，按 N 数字序拼接；不匹配时若品类块数与台账一致则按 id 序兜底。 */
    String reconstructText(ImportedSampleDTO sample) {
        List<PresetCorpusDTO> rows = corpusData.listByGenre(sample.getGenre());
        String prefix = sample.getTitle() + "·块";
        List<PresetCorpusDTO> matched = new ArrayList<>();
        for (PresetCorpusDTO row : rows) {
            if (row.getTitle() == null) {
                continue;
            }
            String stripped = row.getTitle().strip();
            if (stripped.startsWith(prefix)) {
                Matcher m = BLOCK_NO.matcher(stripped);
                if (m.find() && stripped.startsWith(prefix)) {
                    matched.add(row);
                }
            }
        }
        if (matched.isEmpty() && rows.size() == sample.getChunks()) {
            matched = rows;
        }
        if (matched.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "原文语料不可重组（可能被手动清理）：请重新导入该小说正文后再解析");
        }
        matched.sort(Comparator.comparingInt(SampleParseService::blockNo));
        StringBuilder sb = new StringBuilder();
        for (PresetCorpusDTO row : matched) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(row.getContent());
        }
        return sb.toString();
    }

    private static int blockNo(PresetCorpusDTO row) {
        Matcher m = BLOCK_NO.matcher(row.getTitle() == null ? "" : row.getTitle().strip());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ===== 解析管线 =====

    private void safeRun(long taskId, long sampleId, String mode) {
        try {
            runParse(taskId, sampleId, mode);
        } catch (Exception e) {
            log.error("样本解析失败 sampleId={} mode={}", sampleId, mode, e);
            taskData.finish(taskId, "FAILED", "", e.getMessage());
            stages.emit(null, StageLog.Stage.SAMPLE_PARSE, StageLog.Phase.FAILED,
                    Map.of("sampleId", sampleId, "error", String.valueOf(e.getMessage())));
        }
    }

    private void runParse(long taskId, long sampleId, String mode) {
        ImportedSampleDTO sample = sampleData.getById(sampleId);
        if (sample == null) {
            taskData.finish(taskId, "FAILED", "", "样本不存在");
            return;
        }
        if (taskData.casStatus(taskId, "QUEUED", "RUNNING") == 0) {
            log.warn("样本解析任务 {} 抢占失败（非 QUEUED），跳过", taskId);
            return;
        }
        String text = reconstructText(sample);
        List<ChapterSeg> chapters = splitChapters(text);
        if (chapters.isEmpty()) {
            taskData.finish(taskId, "FAILED", "", "原文没有可用正文");
            return;
        }
        boolean fast = mode.equals("FAST");
        int limit = fast ? Math.min(tuning.i("sample_fast_chapters", TuningDefaults.SAMPLE_FAST_CHAPTERS),
                chapters.size()) : chapters.size();
        int totalUnits = limit + 4;
        taskData.updateTotal(taskId, totalUnits);
        taskData.updateProgress(taskId, 0, "chapter");
        stages.emit(null, StageLog.Stage.SAMPLE_PARSE, StageLog.Phase.START,
                Map.of("sampleId", sampleId, "mode", mode, "chapters", limit, "totalUnits", totalUnits));

        // 逐章解析（并发、幂等：已有章行跳过 = 断点续跑/FAST→FULL 升级）
        AtomicInteger done = new AtomicInteger(0);
        Set<Integer> parsedSeqs = new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>().keySet(Boolean.TRUE);
        List<Integer> gaps = new java.util.concurrent.CopyOnWriteArrayList<>();
        int parallel = Math.max(1, tuning.i("sample_parse_parallel", TuningDefaults.SAMPLE_PARSE_PARALLEL));
        ExecutorService pool = Executors.newFixedThreadPool(parallel, r -> {
            Thread t = new Thread(r, "sample-parse-chapter");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                ChapterSeg seg = chapters.get(i);
                if (plotData.findBySeq(sampleId, "chapter", seg.seq()) != null) {
                    parsedSeqs.add(seg.seq());
                    done.incrementAndGet();
                    continue;
                }
                futures.add(pool.submit(() -> {
                    boolean ok = parseOneChapter(sampleId, seg);
                    if (ok) {
                        parsedSeqs.add(seg.seq());
                    } else {
                        gaps.add(seg.seq());
                    }
                    taskData.updateProgress(taskId, done.incrementAndGet(), "chapter");
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    log.error("章解析任务异常冒泡：{}", e.getCause() == null ? e : e.getCause().getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskData.finish(taskId, "INTERRUPTED", "chapter", "解析被中断，可继续解析断点续跑");
            return;
        } finally {
            pool.shutdownNow();
        }
        if (parsedSeqs.isEmpty()) {
            taskData.finish(taskId, "FAILED", "chapter", "全部章解析失败，请检查模型路由后继续解析");
            return;
        }
        if (gaps.size() > Math.max(3, limit / 10)) {
            taskData.finish(taskId, "FAILED", "chapter",
                    "失败章过多（" + gaps.size() + "/" + limit + "），可继续解析断点续跑");
            return;
        }

        // 实体归并 → 资产卡
        taskData.updateProgress(taskId, done.get(), "merge");
        int cardCount = mergeEntities(sampleId, parsedSeqs, limit);

        // 卷级汇总（FULL 且有卷标记）
        taskData.updateProgress(taskId, done.incrementAndGet(), "volume");
        int volumeCount = fast ? 0 : summarizeVolumes(sampleId, chapters, limit);

        // 全书大纲
        taskData.updateProgress(taskId, done.incrementAndGet(), "outline");
        synthesizeOutline(sampleId, sample, chapters, limit, fast, parsedSeqs.size());

        // 世界观
        taskData.updateProgress(taskId, done.incrementAndGet(), "world");
        synthesizeWorld(sampleId, fast);

        // 类型/特征标签（fail-open：失败不拦 DONE，可手动重提）
        taskData.updateProgress(taskId, done.incrementAndGet(), "tags");
        try {
            List<String> tags = extractTags(sampleId);
            sampleData.updateTags(sampleId, toJsonArray(new LinkedHashSet<>(tags)));
        } catch (Exception e) {
            log.warn("标签提取失败（fail-open，可手动重提）sampleId={}：{}", sampleId, e.getMessage());
        }

        taskData.finish(taskId, "DONE", "done",
                (fast ? "快速骨架完成（抽样 " + limit + " 章）" : "完整解析完成（" + limit + " 章）")
                        + "：卡 " + cardCount + " 张" + (volumeCount > 0 ? "，卷 " + volumeCount : ""));
        stages.emit(null, StageLog.Stage.SAMPLE_PARSE, StageLog.Phase.DONE,
                Map.of("sampleId", sampleId, "mode", mode, "chapters", parsedSeqs.size(),
                        "gaps", gaps.size(), "cards", cardCount));
    }

    /** 解析单章（超长分两段逐段解析后机械合并）；传输/格式双失败重试一次，再败返回 false 留缺口。 */
    private boolean parseOneChapter(long sampleId, ChapterSeg seg) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                parseChapterAttempt(sampleId, seg);
                return true;
            } catch (Exception e) {
                log.warn("章解析失败 sampleId={} seq={} 第{}次尝试：{}", sampleId, seg.seq(), attempt, e.getMessage());
            }
        }
        return false;
    }

    private void parseChapterAttempt(long sampleId, ChapterSeg seg) throws Exception {
        List<String> parts = splitBySize(seg.text(), CHAPTER_LLM_MAX_CHARS);
        List<JsonNode> parsed = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String header = (seg.title() == null ? "第" + seg.seq() + "章（无标题）" : seg.title())
                    + (parts.size() > 1 ? "（第 " + (i + 1) + "/" + parts.size() + " 段）" : "");
            parsed.add(askChapter(header, parts.get(i)));
        }
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode summaries = merged.putArray("_summaries");
        parsed.forEach(p -> summaries.add(p.path("summary").asText("")));
        merged.put("summary", truncate(String.join("；", toStringList(summaries)), 600));
        ArrayNode beats = merged.putArray("beats");
        parsed.forEach(p -> p.path("beats").forEach(beats::add));
        ArrayNode hooks = merged.putArray("hooks");
        parsed.forEach(p -> p.path("hooks").forEach(hooks::add));
        ArrayNode entities = merged.putArray("entities");
        parsed.forEach(p -> p.path("entities").forEach(entities::add));
        ObjectNode meta = merged.putObject("meta");
        meta.put("chars", seg.text().length());
        meta.put("pseudo", seg.pseudo());
        meta.put("volumeSeq", seg.volumeSeq());
        meta.set("entities", entities);
        meta.set("hooks", hooks);
        plotData.insertNode(sampleId, "chapter", seg.seq(), seg.volumeSeq(),
                seg.title() == null ? "第" + seg.seq() + "章（无标题）" : truncate(seg.title(), 256),
                merged.get("summary").asText(),
                mapper.writeValueAsString(beats),
                mapper.writeValueAsString(meta));
    }

    private JsonNode askChapter(String header, String text) {
        String user = promptTemplates.format(LlmNode.SAMPLE_CHAPTER, "user", header, text);
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_CHAPTER, null, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_CHAPTER, "system")),
                        LlmPort.Message.user(user)), 0.3);
        return llmJson.ask(req, node -> {
            if (!node.path("summary").isTextual() || node.path("summary").asText().isBlank()) {
                throw new LlmJson.Bad("缺少 summary 字段或为空");
            }
            if (!node.path("entities").isArray() || node.path("entities").isEmpty()) {
                throw new LlmJson.Bad("entities 必须是非空数组");
            }
            return node;
        }, 3);
    }

    /** 跨章实体归并：纯函数聚合（同名/别名相并）→ LLM 补判模糊组 → 落卡（重建式：先软删旧卡）。 */
    int mergeEntities(long sampleId, Set<Integer> parsedSeqs, int parsedCount) {
        Map<String, MergedEntity> byKey = new LinkedHashMap<>();
        for (SamplePlotNodeDTO node : plotData.listBySample(sampleId)) {
            if (!node.getLevel().equals("chapter") || !parsedSeqs.contains(node.getSeq())) {
                continue;
            }
            JsonNode entities = readJson(node.getMeta()).path("entities");
            for (JsonNode e : entities) {
                String name = e.path("name").asText("").strip();
                if (name.isEmpty()) {
                    continue;
                }
                String kind = normalizeKind(e.path("kind").asText("misc"));
                MergedEntity m = byKey.computeIfAbsent(kind + "·" + name, k -> new MergedEntity(name, kind));
                m.mentions++;
                if (m.firstSeq == null || node.getSeq() < m.firstSeq) {
                    m.firstSeq = node.getSeq();
                }
                e.path("aliases").forEach(a -> {
                    String s = a.asText("").strip();
                    if (!s.isEmpty()) {
                        m.aliases.add(s);
                    }
                });
                String note = e.path("note").asText("");
                if (!note.isBlank() && (m.note == null || m.note.length() < note.length())) {
                    m.note = truncate(note, 200);
                }
                e.path("relations").forEach(r -> m.addRelation(r));
            }
        }
        // 别名并名：别名命中他行规范名则并组
        List<MergedEntity> list = new ArrayList<>(byKey.values());
        for (MergedEntity m : list) {
            for (String alias : m.aliases) {
                for (MergedEntity other : list) {
                    if (other != m && other.name.equals(alias)) {
                        other.absorb(m);
                        break;
                    }
                }
            }
        }
        list = list.stream().filter(m -> m.mentions >= 1 && !m.absorbed).toList();
        // LLM 模糊归并（实体多于 20 才值得跑一次）
        if (list.size() > 20) {
            applyLlmGroups(list, askMerge(list));
        }
        cardData.softDeleteBySample(sampleId);
        int written = 0;
        for (MergedEntity m : list) {
            if (m.absorbed) {
                continue;
            }
            int importance = m.mentions >= Math.max(3, parsedCount / 5) ? 3 : (m.mentions >= 3 ? 2 : 1);
            String relationsJson;
            String contentMd;
            try {
                relationsJson = mapper.writeValueAsString(m.relationsList());
                contentMd = (m.note == null ? "" : m.note + "\n")
                        + (m.relationsList().isEmpty() ? "" : "关系：\n"
                        + m.relationsList().stream().map(r -> "- " + r.get("target").asText() + "（" + r.get("kind").asText() + "）").reduce("", (a, b) -> a + b + "\n"));
            } catch (Exception e) {
                throw new IllegalStateException("关系序列化失败", e);
            }
            cardData.insertCard(sampleId, m.kind, m.name,
                    toJsonArray(m.aliases), m.note, contentMd, relationsJson,
                    importance, m.firstSeq, m.mentions);
            written++;
        }
        return written;
    }

    private JsonNode askMerge(List<MergedEntity> list) {
        StringBuilder sb = new StringBuilder();
        for (MergedEntity m : list) {
            sb.append(m.name).append(" | ").append(m.kind).append(" | 别名：")
                    .append(String.join("、", m.aliases)).append('\n');
        }
        String user = promptTemplates.format(LlmNode.SAMPLE_MERGE, "user", truncate(sb.toString(), 20000));
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_MERGE, null, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_MERGE, "system")),
                        LlmPort.Message.user(user)), 0.1);
        try {
            return llmJson.ask(req, node -> {
                if (!node.path("groups").isArray()) {
                    throw new LlmJson.Bad("缺少 groups 数组");
                }
                return node;
            }, 2);
        } catch (Exception e) {
            log.warn("实体 LLM 归并失败，退回纯机械归并：{}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    private void applyLlmGroups(List<MergedEntity> list, JsonNode groups) {
        Map<String, MergedEntity> byName = new LinkedHashMap<>();
        for (MergedEntity m : list) {
            if (!m.absorbed) {
                byName.put(m.name, m);
            }
        }
        for (JsonNode g : groups.path("groups")) {
            MergedEntity canonical = byName.get(g.path("canonical").asText(""));
            if (canonical == null) {
                continue;
            }
            for (JsonNode member : g.path("members")) {
                MergedEntity m = byName.get(member.asText(""));
                if (m != null && m != canonical) {
                    canonical.absorb(m);
                }
            }
        }
    }

    /** 卷级汇总：按章切段携带的卷界标分组（无卷标记返回 0 不建卷行）。 */
    int summarizeVolumes(long sampleId, List<ChapterSeg> chapters, int limit) {
        Map<Integer, List<ChapterSeg>> byVolume = new LinkedHashMap<>();
        for (int i = 0; i < limit; i++) {
            ChapterSeg seg = chapters.get(i);
            if (seg.volumeSeq() > 0) {
                byVolume.computeIfAbsent(seg.volumeSeq(), k -> new ArrayList<>()).add(seg);
            }
        }
        if (byVolume.isEmpty()) {
            return 0;
        }
        plotData.softDeleteByLevel(sampleId, "volume");
        int written = 0;
        for (Map.Entry<Integer, List<ChapterSeg>> e : byVolume.entrySet()) {
            List<ChapterSeg> segs = e.getValue();
            StringBuilder sb = new StringBuilder();
            for (ChapterSeg seg : segs) {
                SamplePlotNodeDTO node = plotData.findBySeq(sampleId, "chapter", seg.seq());
                if (node != null) {
                    sb.append("第").append(seg.seq()).append("章 ").append(node.getTitle()).append("：")
                            .append(node.getSummary()).append('\n');
                }
            }
            if (sb.isEmpty()) {
                continue;
            }
            JsonNode v = askVolume("第" + e.getKey() + "卷（共 " + segs.size() + " 章）", sb.toString());
            ObjectNode meta = mapper.createObjectNode();
            meta.put("chapters", segs.size());
            meta.put("fromSeq", segs.get(0).seq());
            meta.put("toSeq", segs.get(segs.size() - 1).seq());
            meta.put("pacing_note", v.path("pacing_note").asText(""));
            plotData.insertNode(sampleId, "volume", e.getKey(), 0,
                    "第" + e.getKey() + "卷",
                    v.path("summary").asText(),
                    toJsonOrEmpty(v.path("key_turns")),
                    toJsonOrEmpty(meta));
            written++;
        }
        return written;
    }

    private JsonNode askVolume(String header, String chapterSummaries) {
        String user = promptTemplates.format(LlmNode.SAMPLE_VOLUME, "user", header, truncate(chapterSummaries, 30000));
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_VOLUME, null, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_VOLUME, "system")),
                        LlmPort.Message.user(user)), 0.3);
        return llmJson.ask(req, node -> {
            if (!node.path("summary").isTextual() || node.path("summary").asText().isBlank()) {
                throw new LlmJson.Bad("缺少 summary");
            }
            return node;
        }, 3);
    }

    /** 全书大纲合成：卷级结构优先，无卷行则抽样章摘要（头 20 + 尾 20 + 中间隔 10）+ 主要角色卡。 */
    void synthesizeOutline(long sampleId, ImportedSampleDTO sample, List<ChapterSeg> chapters,
                           int limit, boolean fast, int parsedCount) {
        List<SamplePlotNodeDTO> volumes = plotData.listBySample(sampleId).stream()
                .filter(n -> n.getLevel().equals("volume")).toList();
        StringBuilder volBlock = new StringBuilder();
        for (SamplePlotNodeDTO v : volumes) {
            volBlock.append(v.getTitle()).append("：").append(v.getSummary()).append('\n');
        }
        if (volBlock.isEmpty()) {
            for (SamplePlotNodeDTO n : plotData.listBySample(sampleId)) {
                if (n.getLevel().equals("chapter") && n.getSeq() <= limit
                        && (n.getSeq() <= 20 || n.getSeq() > limit - 20 || n.getSeq() % 10 == 0)) {
                    volBlock.append(n.getTitle()).append("：").append(n.getSummary()).append('\n');
                }
            }
        }
        StringBuilder cardBlock = new StringBuilder();
        for (SampleCardDTO c : cardData.listBySample(sampleId)) {
            if (c.getKind().equals("character") && cardBlock.length() < 6000) {
                cardBlock.append(c.getName()).append("：").append(c.getSummary()).append('\n');
            }
        }
        String stats = "共 " + limit + " 章（已析 " + parsedCount + "）"
                + (fast ? "；快速档为抽样骨架，完整结构请升级完整解析" : "");
        String user = promptTemplates.format(LlmNode.SAMPLE_OUTLINE, "user", stats, truncate(volBlock.toString(), 30000),
                truncate(cardBlock.toString(), 6000));
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_OUTLINE, null, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_OUTLINE, "system")),
                        LlmPort.Message.user(user)), 0.3);
        JsonNode outline = llmJson.ask(req, node -> {
            if (!node.path("main_plot").isTextual() || node.path("main_plot").asText().isBlank()) {
                throw new LlmJson.Bad("缺少 main_plot");
            }
            return node;
        }, 3);
        StringBuilder md = new StringBuilder();
        md.append("## 核心设定\n").append(outline.path("premise").asText()).append("\n\n## 主线\n")
                .append(outline.path("main_plot").asText()).append("\n\n## 阶段\n");
        outline.path("arcs").forEach(a -> md.append("- ").append(a.path("title").asText())
                .append("（").append(a.path("span").asText()).append("）：").append(a.path("summary").asText()).append('\n'));
        md.append("\n## 主题\n");
        outline.path("themes").forEach(t -> md.append("- ").append(t.asText()).append('\n'));
        md.append("\n## 结局\n").append(outline.path("ending").asText());
        ObjectNode meta = mapper.createObjectNode();
        meta.put("fast", fast);
        meta.set("arcs", outline.path("arcs"));
        meta.set("themes", outline.path("themes"));
        meta.put("chapters", limit);
        plotData.softDeleteByLevel(sampleId, "book");
        plotData.insertNode(sampleId, "book", 1, 0, sample.getTitle(), md.toString(), "[]",
                toJsonOrEmpty(meta));
    }

    /** 世界观文档：卷级结构 + 设定类卡 → markdown 落 kind=world 卡（每样本一行）。 */
    void synthesizeWorld(long sampleId, boolean fast) {
        StringBuilder volBlock = new StringBuilder();
        for (SamplePlotNodeDTO n : plotData.listBySample(sampleId)) {
            if (n.getLevel().equals("volume")) {
                volBlock.append(n.getTitle()).append("：").append(n.getSummary()).append('\n');
            }
        }
        if (volBlock.isEmpty()) {
            for (SamplePlotNodeDTO n : plotData.listBySample(sampleId)) {
                if (n.getLevel().equals("chapter") && (n.getSeq() <= 15 || n.getSeq() % 10 == 0)) {
                    volBlock.append(n.getTitle()).append("：").append(n.getSummary()).append('\n');
                }
            }
        }
        StringBuilder cardBlock = new StringBuilder();
        for (SampleCardDTO c : cardData.listBySample(sampleId)) {
            if (!c.getKind().equals("character") && !c.getKind().equals("world")
                    && cardBlock.length() < 8000) {
                cardBlock.append(c.getKind()).append("·").append(c.getName()).append("：")
                        .append(c.getSummary()).append('\n');
            }
        }
        String user = promptTemplates.format(LlmNode.SAMPLE_WORLD, "user", truncate(volBlock.toString(), 20000), truncate(cardBlock.toString(), 8000));
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_WORLD, null, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_WORLD, "system")),
                        LlmPort.Message.user(user)), 0.3);
        String world;
        LlmPort.ChatResult r = llm.chat(req);
        world = r.content();
        if (world == null || world.isBlank()) {
            log.warn("世界观合成空输出 sampleId={}，跳过", sampleId);
            return;
        }
        if (fast) {
            world = "> 注：快速档抽样合成，完整世界观请升级完整解析。\n\n" + world;
        }
        for (SampleCardDTO c : cardData.listBySample(sampleId)) {
            if (c.getKind().equals("world")) {
                cardData.softDeleteById(c.getId());
            }
        }
        cardData.insertCard(sampleId, "world", "世界观", "[]", "世界观设定文档", world, "[]", 2, null, 0);
    }

    /** 类型/特征标签提取：书级大纲 + 主要角色卡 → LLM 打标（5-15 个）→ 写回 imported_samples.tags。 */
    public List<String> extractTags(long sampleId) {
        ImportedSampleDTO sample = requireSample(sampleId);
        StringBuilder material = new StringBuilder();
        for (SamplePlotNodeDTO n : plotData.listBySample(sampleId)) {
            if (n.getLevel().equals("book") && n.getSummary() != null) {
                material.append(n.getSummary());
                break;
            }
        }
        int characters = 0;
        for (SampleCardDTO c : cardData.listBySample(sampleId)) {
            if (c.getKind().equals("character") && c.getImportance() != null && c.getImportance() >= 2
                    && material.length() < 9000) {
                material.append('\n').append(c.getName()).append("：").append(truncate(c.getSummary(), 60));
                characters++;
            }
        }
        if (material.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "尚无书级材料：请先完成深度解析（快速档即可）再提取标签");
        }
        String user = promptTemplates.format(LlmNode.SAMPLE_TAGS, "user",
                truncate(material + "\n（《" + sample.getTitle() + "》，主要角色 " + characters + " 个）", 10000));
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_TAGS, null, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_TAGS, "system")),
                        LlmPort.Message.user(user)), 0.3);
        JsonNode r = llmJson.ask(req, node -> {
            if (!node.path("tags").isArray() || node.path("tags").isEmpty()) {
                throw new LlmJson.Bad("tags 必须是非空数组");
            }
            return node;
        }, 3);
        List<String> tags = new ArrayList<>();
        for (JsonNode t : r.path("tags")) {
            String s = t.asText("").strip();
            if (!s.isEmpty() && tags.size() < 15) {
                tags.add(truncate(s, 12));
            }
        }
        sampleData.updateTags(sampleId, toJsonArray(new LinkedHashSet<>(tags)));
        log.info("样本标签提取完成 sampleId={}：{}", sampleId, tags);
        return tags;
    }

    /** 重启善后：RUNNING 的解析任务标 INTERRUPTED（前端可「继续解析」断点续跑）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        List<SampleParseTaskDTO> running = taskData.list(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SampleParseTaskDTO>()
                .eq("status", "RUNNING")
                .eq("is_deleted", false));
        for (SampleParseTaskDTO t : running) {
            if (taskData.casStatus(t.getId(), "RUNNING", "INTERRUPTED") > 0) {
                taskData.finish(t.getId(), "INTERRUPTED", "", "后端重启中断，可继续解析断点续跑");
                log.warn("样本解析任务 {}（sampleId={}）重启置 INTERRUPTED", t.getId(), t.getSampleId());
            }
        }
    }

    // ===== 资产装配与人工纠偏 =====

    /** 「AI 帮我定」：依据样本结构画像推荐衍生参数（掺水量/POV/节奏/章数；预算带取文风分析的章长带）。 */
    public SampleParamsVO recommendParams(long sampleId) {
        ImportedSampleDTO sample = requireSample(sampleId);
        StringBuilder profile = new StringBuilder();
        profile.append("体量：约 ").append(Math.round(sample.getTotalChars() / 10000.0)).append(" 万字");
        int chapterCount = 0;
        for (SamplePlotNodeDTO n : plotData.listBySample(sampleId)) {
            if (n.getLevel().equals("book")) {
                profile.append("；大纲骨架：\n").append(truncate(n.getSummary(), 2000));
            } else if (n.getLevel().equals("chapter")) {
                chapterCount++;
            }
        }
        if (chapterCount > 0) {
            profile.append("\n章数：").append(chapterCount).append("（约 ")
                    .append(Math.round(sample.getTotalChars() / 10000.0 / Math.max(chapterCount, 1) * 10000) / 10000.0)
                    .append(" 万字/章）");
        }
        profile.append("\n主要角色：");
        for (SampleCardDTO c : cardData.listBySample(sampleId)) {
            if (c.getKind().equals("character") && c.getImportance() != null && c.getImportance() >= 3
                    && profile.length() < 8000) {
                profile.append(c.getName()).append("（").append(truncate(c.getSummary(), 60)).append("）");
            }
        }
        String user = promptTemplates.format(LlmNode.SAMPLE_PARAMS, "user", sample.getTitle(), truncate(profile.toString(), 10000));
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_PARAMS, null, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_PARAMS, "system")),
                        LlmPort.Message.user(user)), 0.3);
        JsonNode r = llmJson.ask(req, node -> {
            if (!node.path("reason").isTextual() || node.path("reason").asText().isBlank()) {
                throw new LlmJson.Bad("缺少 reason");
            }
            return node;
        }, 3);
        int[] band = budgetBandOf(sample.getAnalysis());
        java.util.List<String> tags = new ArrayList<>();
        for (JsonNode t : r.path("tags")) {
            String v = t.asText("").strip();
            if (!v.isEmpty() && tags.size() < 10) {
                tags.add(truncate(v, 12));
            }
        }
        return new SampleParamsVO(
                clampInt(r.path("water").asInt(50), 0, 100),
                textOr(r.path("pov").asText(""), "第三人称限知"),
                r.path("povCharacter").asText(""),
                clampInt(r.path("chaptersPerVolume").asInt(10), 3, 30),
                clampInt(r.path("targetChapters").asInt(300), 50, 2000),
                r.path("pacingNote").asText(""),
                r.path("reason").asText(),
                band == null ? null : band[0], band == null ? null : band[1], tags);
    }

    /** 文风分析快照里的章长预算带（无快照/无带返回 null）。 */
    private int[] budgetBandOf(String analysisJson) {
        try {
            JsonNode a = readJson(analysisJson);
            int lo = a.path("budgetMin").asInt(0);
            int hi = a.path("budgetMax").asInt(0);
            return lo > 0 && hi >= lo ? new int[]{lo, hi} : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String textOr(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    /** 解析任务状态 + 资产计数（前端进度条/按钮态）。 */
    public SampleParseStatusVO status(long sampleId) {
        int chapters = 0;
        int volumes = 0;
        for (SamplePlotNodeDTO n : plotData.listBySample(sampleId)) {
            if (n.getLevel().equals("chapter")) {
                chapters++;
            } else if (n.getLevel().equals("volume")) {
                volumes++;
            }
        }
        int cards = cardData.listBySample(sampleId).size();
        return SampleParseStatusVO.from(taskData.findAliveBySample(sampleId), chapters, volumes, cards);
    }

    /** 解析资产总览（剧情树 + 卡）。 */
    public SampleAssetsVO assets(long sampleId) {
        ImportedSampleDTO sample = requireSample(sampleId);
        SamplePlotVO book = null;
        List<SamplePlotVO> volumes = new ArrayList<>();
        List<SamplePlotVO> chapters = new ArrayList<>();
        for (SamplePlotNodeDTO n : plotData.listBySample(sampleId)) {
            switch (n.getLevel()) {
                case "book" -> book = SamplePlotVO.from(n, mapper);
                case "volume" -> volumes.add(SamplePlotVO.from(n, mapper));
                case "chapter" -> chapters.add(SamplePlotVO.from(n, mapper));
                default -> log.warn("未知剧情节点层级：{}", n.getLevel());
            }
        }
        List<SampleCardVO> cards = cardData.listBySample(sampleId).stream()
                .map(c -> SampleCardVO.from(c, mapper)).toList();
        return new SampleAssetsVO(sampleId, sample.getTitle(), sample.getGenre(),
                book, volumes, chapters, cards);
    }

    /** 卡人工纠偏（摘要/正文/重要度；重要度 1-3）。 */
    public void updateCard(long cardId, String summary, String contentMd, Integer importance) {
        SampleCardDTO card = cardData.getById(cardId);
        if (card == null || Boolean.TRUE.equals(card.getIsDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND, "样本资产卡不存在: " + cardId);
        }
        if (importance != null && (importance < 1 || importance > 3)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "重要度必须 1-3");
        }
        cardData.update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<SampleCardDTO>()
                .eq("id", cardId)
                .set("summary", summary == null ? card.getSummary() : summary)
                .set("content_md", contentMd == null ? card.getContentMd() : contentMd)
                .set("importance", importance == null ? card.getImportance() : importance));
    }

    /** 卡人工删除（软删；重新解析会重建全量卡）。 */
    public void deleteCard(long cardId) {
        if (cardData.getById(cardId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "样本资产卡不存在: " + cardId);
        }
        cardData.softDeleteById(cardId);
    }

    /** 卡人工新建（AI 漏抽补录；别名逗号分隔）。 */
    public long createCard(long sampleId, String kind, String name, String aliasesText,
                           String summary, String contentMd, Integer importance) {
        requireSample(sampleId);
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "名称必填");
        }
        String k = normalizeKind(kind);
        int imp = importance == null ? 2 : Math.max(1, Math.min(3, importance));
        Set<String> aliases = new LinkedHashSet<>();
        if (aliasesText != null) {
            for (String a : aliasesText.split("[,，、]")) {
                String s = a.strip();
                if (!s.isEmpty()) {
                    aliases.add(s);
                }
            }
        }
        return cardData.insertCard(sampleId, k, name.strip(), toJsonArray(aliases),
                summary, contentMd, "[]", imp, null, 0);
    }

    // ===== 私有工具 =====

    private static final class MergedEntity {
        final String name;
        final String kind;
        int mentions;
        Integer firstSeq;
        String note;
        boolean absorbed;
        final Set<String> aliases = new LinkedHashSet<>();
        final Map<String, JsonNode> relations = new LinkedHashMap<>();

        MergedEntity(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }

        void addRelation(JsonNode r) {
            String target = r.path("target").asText("");
            String relKind = r.path("kind").asText("关系");
            if (target.isEmpty()) {
                return;
            }
            relations.computeIfAbsent(target + "·" + relKind, k -> r);
        }

        void absorb(MergedEntity other) {
            other.absorbed = true;
            mentions += other.mentions;
            aliases.addAll(other.aliases);
            aliases.add(other.name);
            if (firstSeq == null || (other.firstSeq != null && other.firstSeq < firstSeq)) {
                firstSeq = other.firstSeq;
            }
            other.relations.forEach(relations::putIfAbsent);
        }

        List<JsonNode> relationsList() {
            return new ArrayList<>(relations.values());
        }
    }

    private ImportedSampleDTO requireSample(long sampleId) {
        ImportedSampleDTO sample = sampleData.getById(sampleId);
        if (sample == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导入样本不存在: " + sampleId);
        }
        return sample;
    }

    private static String requireMode(String mode) {
        if (mode == null || !(mode.equals("FAST") || mode.equals("FULL"))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "mode 必须为 FAST 或 FULL");
        }
        return mode;
    }

    private static String normalizeKind(String kind) {
        return switch (kind == null ? "" : kind.strip()) {
            case "character", "item", "location", "phenomenon", "landmark", "disaster", "org", "misc" -> kind.strip();
            default -> "misc";
        };
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String toJsonArray(Set<String> values) {
        ArrayNode arr = mapper.createArrayNode();
        values.forEach(arr::add);
        try {
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** JsonNode 序列化兜底：失败给空数组/对象（不因序列化打断解析收尾）。 */
    private String toJsonOrEmpty(JsonNode node) {
        try {
            return mapper.writeValueAsString(node == null ? mapper.createArrayNode() : node);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<String> toStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    static List<String> splitBySize(String text, int maxChars) {
        List<String> parts = new ArrayList<>();
        if (text.length() <= maxChars) {
            parts.add(text);
            return parts;
        }
            int from = 0;
            while (from < text.length()) {
                int to = Math.min(from + maxChars, text.length());
                if (to < text.length()) {
                    int cut = text.lastIndexOf('\n', to);
                    // 换行归前段尾部（切口在换行后），下段不以空行开头
                    if (cut > from + maxChars / 2) {
                        to = Math.min(cut + 1, text.length());
                    }
                }
                parts.add(text.substring(from, to));
                from = to;
            }
        return parts;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
