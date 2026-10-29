package com.zzdzz.novelgen.derive.draft;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 单章正文闭环：只生成缺失场，逐场/逐章条件写入，并绑定 active plan revision。 */
@Service
public class ChapterDraftCompletionService {

    private static final int MAX_SCENE_ATTEMPTS = 3;
    private static final String STALE = "DRAFT_PLAN_REVISION_STALE";

    private final PlanSnapshotService snapshotService;
    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final SceneRetryPolicy retryPolicy;
    private final ChapterAssembler assembler;

    /** 创建单章正文完成服务。 */
    public ChapterDraftCompletionService(
            PlanSnapshotService snapshotService,
            RunMapper runMapper,
            NovelMapper novelMapper,
            ChapterMapper chapterMapper,
            BeatInstanceMapper beatInstanceMapper,
            SceneRetryPolicy retryPolicy,
            ChapterAssembler assembler) {
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.runMapper = Objects.requireNonNull(runMapper);
        this.novelMapper = Objects.requireNonNull(novelMapper);
        this.chapterMapper = Objects.requireNonNull(chapterMapper);
        this.beatInstanceMapper = Objects.requireNonNull(beatInstanceMapper);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.assembler = Objects.requireNonNull(assembler);
    }

    /** 当前 active revision 的该章是否已有非空正文。 */
    public boolean chapterComplete(long runId, int chapterNo) {
        DerivePlanSnapshotDO active = snapshotService.requireActive(runId);
        RunDO run = requireRun(runId);
        ChapterDO chapter = findChapter(run.getNovelId(), chapterNo);
        return chapter != null && chapter.getContent() != null
                && !chapter.getContent().isBlank()
                && active.getRevision().equals(chapter.getPlanRevision());
    }

    /** 完成一章；每个场只重试自己，任一 revision guard 失败都不覆盖旧产物。 */
    public ChapterDraftResult completeChapter(long runId, int chapterNo) {
        DerivePlanSnapshotDO active = snapshotService.requireActive(runId);
        int revision = active.getRevision();
        CompiledStoryPlan plan = snapshotService.loadCompiled(runId, revision);
        RunDO run = requireRun(runId);
        NovelDO novel = novelMapper.selectById(run.getNovelId());
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "成品书不存在: " + run.getNovelId());
        }
        PlanGraphRecords.PlannedChapter planned = plan.chapters().stream()
                .filter(row -> row.chapterNo() == chapterNo)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "计划中不存在章节: " + chapterNo));
        List<ChapterDO> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<ChapterDO>()
                        .eq(ChapterDO::getNovelId, novel.getId())
                        .orderByAsc(ChapterDO::getChapterNo));
        ChapterDO chapter = chapters.stream()
                .filter(row -> row.getChapterNo() == chapterNo)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "章节投影不存在: " + chapterNo));
        if (chapter.getContent() != null && !chapter.getContent().isBlank()
                && revision != (chapter.getPlanRevision() == null ? -1 : chapter.getPlanRevision())) {
            throw stale("chapter=" + chapterNo + " 绑定 revision="
                    + chapter.getPlanRevision() + "，active=" + revision);
        }

        ChapterScript script = script(plan, planned, run, chapters, chapterNo);
        Map<String, BeatInstanceDO> persistedBeats = beatInstanceMapper.selectList(
                        new LambdaQueryWrapper<BeatInstanceDO>()
                                .eq(BeatInstanceDO::getRunId, runId)
                                .eq(BeatInstanceDO::getChapterNo, chapterNo)
                                .eq(BeatInstanceDO::getPlanRevision, revision)
                                .orderByAsc(BeatInstanceDO::getChapterSeq))
                .stream()
                .filter(row -> row.getBeatKey() != null)
                .collect(Collectors.toMap(BeatInstanceDO::getBeatKey, row -> row,
                        (left, right) -> left, TreeMap::new));

        if (chapter.getContent() != null && !chapter.getContent().isBlank()
                && script.scenes().stream().allMatch(scene -> {
                    BeatInstanceDO row = persistedBeats.get(scene.beatKey());
                    return row != null && row.getFilledText() != null && !row.getFilledText().isBlank();
                })) {
            List<SceneDraft> completed = script.scenes().stream()
                    .map(scene -> {
                        BeatInstanceDO row = persistedBeats.get(scene.beatKey());
                        return new SceneDraft(scene.sceneNo(), row.getFilledText().trim(),
                                DraftTextRules.length(row.getFilledText()));
                    })
                    .toList();
            return assembler.assemble(script, completed);
        }

        Map<Integer, SceneDraft> drafts = new TreeMap<>();
        boolean degraded = false;
        List<String> sceneFailures = new ArrayList<>();
        String priorSummary = priorSummary(chapters, chapterNo);
        for (SceneScript scene : script.scenes()) {
            BeatInstanceDO persisted = persistedBeats.get(scene.beatKey());
            if (persisted != null && persisted.getFilledText() != null
                    && !persisted.getFilledText().isBlank()) {
                drafts.put(scene.sceneNo(), new SceneDraft(scene.sceneNo(),
                        persisted.getFilledText().trim(),
                        DraftTextRules.length(persisted.getFilledText())));
                continue;
            }
            SceneContext context = new SceneContext(runId, revision, chapterNo,
                    priorSummary, stateBefore(plan, chapterNo, scene.sceneNo()),
                    run.getParamStyle());
            SceneRetryPolicy.SceneRetryResult result =
                    retryPolicy.writeWithRetry(scene, context, MAX_SCENE_ATTEMPTS);
            if (result.best() == null) {
                sceneFailures.add("scene=" + scene.sceneNo() + "：" + String.join("；", result.lastIssues()));
                continue;
            }
            drafts.put(scene.sceneNo(), result.best());
            degraded |= result.degraded();
        }

        if (!sceneFailures.isEmpty()) {
            throw new SceneDraftException("DRAFT_SCENE_EMPTY",
                    "chapter=" + chapterNo + "；" + String.join("；", sceneFailures));
        }

        snapshotService.requireActive(runId, revision);
        for (SceneScript scene : script.scenes()) {
            SceneDraft draft = drafts.get(scene.sceneNo());
            int affected = beatInstanceMapper.updateFilledIfRevision(
                    runId, scene.beatKey(), revision, draft.text(), "WRITTEN");
            if (affected != 1) {
                throw stale("chapter=" + chapterNo + ".scene=" + scene.sceneNo()
                        + " beat_key=" + scene.beatKey());
            }
        }
        ChapterDraftResult assembled = assembler.assemble(script, new ArrayList<>(drafts.values()));
        ChapterDraftResult result = new ChapterDraftResult(assembled.chapterNo(),
                assembled.text(), assembled.chars(), assembled.scenes(), degraded);
        int affected = chapterMapper.updateContentIfRevision(
                novel.getId(), chapterNo, revision, result.text(), result.chars(), "DRAFTED");
        if (affected != 1) {
            throw stale("chapter=" + chapterNo);
        }

        snapshotService.requireActive(runId, revision);
        return result;
    }

    private RunDO requireRun(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在或未关联书: " + runId);
        }
        return run;
    }

    private ChapterDO findChapter(Long novelId, int chapterNo) {
        return chapterMapper.selectOne(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .eq(ChapterDO::getChapterNo, chapterNo));
    }

    private ChapterScript script(CompiledStoryPlan plan,
                                 PlanGraphRecords.PlannedChapter planned,
                                 RunDO run,
                                 List<ChapterDO> chapters,
                                 int chapterNo) {
        Map<String, PlanGraphRecords.PlannedBeat> beats = plan.beats().stream()
                .collect(Collectors.toMap(PlanGraphRecords.PlannedBeat::beatKey,
                        beat -> beat, (left, right) -> left, TreeMap::new));
        int count = planned.scenes().size();
        int base = planned.budget() / count;
        int remainder = planned.budget() % count;
        List<SceneScript> scenes = new ArrayList<>();
        for (PlanGraphRecords.PlannedScene scene : planned.scenes()) {
            int budget = base + (scene.sceneNo() <= remainder ? 1 : 0);
            if (!beats.containsKey(scene.beatKey())) {
                throw stale("chapter=" + chapterNo + ".scene=" + scene.sceneNo()
                        + " 缺少 beat_key=" + scene.beatKey());
            }
            scenes.add(new SceneScript(scene.sceneNo(), scene.beatKey(), scene.goal(),
                    budget, scene.carryKnowledge(), scene.revealKnowledge()));
        }
        return new ChapterScript(chapterNo, planned.title(), planned.budget(), scenes);
    }

    private static String priorSummary(List<ChapterDO> chapters, int chapterNo) {
        return chapters.stream()
                .filter(row -> row.getChapterNo() < chapterNo
                        && row.getContent() != null && !row.getContent().isBlank())
                .reduce((left, right) -> right)
                .map(row -> {
                    String text = row.getContent().replaceAll("\\s", "");
                    return text.length() <= 1200 ? text : text.substring(text.length() - 1200);
                })
                .orElse("");
    }

    private static Map<String, List<String>> stateBefore(
            CompiledStoryPlan plan, int chapterNo, int sceneNo) {
        Map<String, Set<String>> grouped = new HashMap<>();
        for (PlanGraphRecords.PlannedBeat beat : plan.beats()) {
            boolean before = beat.chapterNo() < chapterNo
                    || (beat.chapterNo() == chapterNo && beat.sceneNo() < sceneNo);
            if (!before) {
                continue;
            }
            for (PlanGraphRecords.StateEffect effect : beat.stateEffects()) {
                grouped.computeIfAbsent(effect.characterKey(), key -> new LinkedHashSet<>())
                        .add(effect.detail());
            }
        }
        Map<String, List<String>> result = new HashMap<>();
        grouped.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static BizException stale(String detail) {
        return new BizException(ErrorCode.STATE_CONFLICT, STALE + "：" + detail);
    }
}



