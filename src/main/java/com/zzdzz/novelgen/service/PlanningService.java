package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.model.enums.PlanMode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import com.zzdzz.novelgen.model.dto.CanonDocDTO;
import com.zzdzz.novelgen.model.dto.ChapterDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划三件套的系统管理：大纲（canon misc/大纲，进出生成上下文）、
 * 卷纲（chapters 规划行：逐章 goal/hook/预算，可增删改）、章纲（AI 场景拆解：可查看/强制重出）、
 * 以及 AI 卷纲规划（委托 VolumePlanService，含模式切换）。
 */
@Service
@RequiredArgsConstructor
public class PlanningService {

    private static final String STORY_KIND = "misc";
    private static final String STORY_NAME = "大纲";

    private final CanonDocDataService canonData;
    private final ChapterDataService chapterData;
    private final SceneDataService sceneData;
    private final ChapterPipelineService pipelineService;
    private final VolumePlanService volumePlanService;
    private final NovelDataService novelData;
    private final ObjectMapper mapper;


    // ===== 大纲 =====

    public String storyOutline(long novelId) {
        return canonData.findContentByKindName(novelId, STORY_KIND, STORY_NAME);
    }

    public void saveStoryOutline(long novelId, String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "大纲内容不能为空");
        }
        Long id = canonData.findId(novelId, STORY_KIND, STORY_NAME);
        if (id == null) {
            canonData.insert(novelId, STORY_KIND, STORY_NAME, content);
        } else {
            canonData.updateContent(id, content);
        }
    }

    // ===== 卷纲 =====

    public List<Map<String, Object>> volumes(long novelId) {
        List<ChapterDTO> chapters = chapterData.listSummariesByNovel(novelId);
        Map<Integer, List<ChapterDTO>> byVolume = new LinkedHashMap<>();
        for (ChapterDTO c : chapters) {
            byVolume.computeIfAbsent(c.getVolumeNo() == null ? 0 : c.getVolumeNo(), k -> new ArrayList<>()).add(c);
        }
        List<Map<String, Object>> volumes = new ArrayList<>();
        for (var e : byVolume.entrySet()) {
            ChapterDTO first = e.getValue().get(0);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("volNo", e.getKey());
            v.put("arc", e.getKey() == 0 ? "未分卷" : first.getArc());
            v.put("chapters", e.getValue().stream().map(this::toPlanVO).toList());
            volumes.add(v);
        }
        return volumes;
    }

    private Map<String, Object> toPlanVO(ChapterDTO c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("chapterNo", c.getChapterNo());
        m.put("title", c.getTitle());
        m.put("goal", c.getGoal());
        m.put("hook", c.getHook());
        m.put("timeNote", c.getTimeNote());
        m.put("status", c.getStatus());
        m.put("hasText", c.getFullText() != null && !c.getFullText().isBlank());
        m.put("budgetMin", c.getBudgetMin());
        m.put("budgetMax", c.getBudgetMax());
        m.put("sceneCount", sceneData.countByChapter(c.getId()));
        return m;
    }

    public void updatePlan(long chapterId, Integer volNo, String arc, String title,
                           String goal, String hook, String timeNote, Integer budgetMin, Integer budgetMax) {
        ChapterDTO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        chapterData.updatePlan(chapterId,
                volNo != null ? volNo : ch.getVolumeNo(),
                arc != null ? arc : ch.getArc(),
                title != null ? title : ch.getTitle(),
                goal != null ? goal : ch.getGoal(),
                hook != null ? hook : ch.getHook(),
                timeNote != null ? timeNote : ch.getTimeNote(),
                budgetMin != null ? budgetMin : ch.getBudgetMin(),
                budgetMax != null ? budgetMax : ch.getBudgetMax());
    }

    public void addPlan(long novelId, int chapterNo, int volNo, String arc, String title,
                        String goal, String hook, String timeNote, int budgetMin, int budgetMax) {
        if (chapterData.exists(novelId, chapterNo)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "章号已存在: " + chapterNo);
        }
        chapterData.insertPlan(novelId, chapterNo, volNo, arc, title, goal, hook, timeNote, "[]", "[]",
                budgetMin, budgetMax);
    }

    public void deletePlan(long chapterId) {
        ChapterDTO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (ch.getFullText() != null && !ch.getFullText().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "第 " + ch.getChapterNo() + " 章已有正文，禁止删除");
        }
        chapterData.softDeletePlan(chapterId);
    }

    // ===== AI 卷纲规划 =====

    /** 规划模式（卷纲）：auto=AI 审校通过直接落库；manual=出草稿待采纳。 */
    public Map<String, String> modes(long novelId) {
        return Map.of("planMode", novelData.findPlanMode(novelId),
                "approvalMode", novelData.findApprovalMode(novelId));
    }

    public void setPlanMode(long novelId, String mode) {
        if (!PlanMode.AUTO.is(mode) && !PlanMode.MANUAL.is(mode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "规划模式只支持 auto / manual");
        }
        novelData.updatePlanMode(novelId, mode);
    }

    /** AI 规划一卷（同步，约 2-10 分钟）。 */
    public Map<String, Object> autoPlan(long novelId, int volNo, int from, Integer to, String seedOutline) {
        VolumePlanService.PlanOutcome o = volumePlanService.planVolume(novelId, volNo, from, to, seedOutline);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planMode", o.planMode());
        m.put("adopted", o.adopted());
        m.put("arc", o.draft().arc());
        m.put("brief", o.draft().brief());
        m.put("rows", o.draft().rows().stream().map(PlanningService::rowMap).toList());
        if (o.result() != null) {
            m.put("adoptedChapters", o.result().chapters());
            m.put("adoptedForeshadows", o.result().adoptedForeshadows());
            m.put("warnings", o.result().warnings());
        }
        return m;
    }

    /** manual 模式采纳（可先人工修改）：结构校验后落库，不再过 AI 审校。 */
    public Map<String, Object> adoptDraft(long novelId, int volNo, String arc, String brief,
                                          List<Map<String, Object>> rawRows) {
        List<VolumePlanService.PlanRow> rows = new ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            List<VolumePlanService.FsRef> refs = new ArrayList<>();
            for (String code : toStrList(r.get("foreshadows"))) {
                refs.add(new VolumePlanService.FsRef(code, null, null));
            }
            rows.add(new VolumePlanService.PlanRow(
                    ((Number) r.get("no")).intValue(),
                    (String) r.get("title"),
                    (String) r.get("goal"),
                    (String) r.get("hook"),
                    (String) r.get("timeNote"),
                    refs,
                    ((Number) r.get("budgetMin")).intValue(),
                    ((Number) r.get("budgetMax")).intValue()));
        }
        VolumePlanService.AdoptResult result = volumePlanService.adoptDraft(novelId, volNo,
                new VolumePlanService.PlanDraft(arc, brief == null ? "" : brief, rows));
        return Map.of("adoptedChapters", result.chapters(),
                "adoptedForeshadows", result.adoptedForeshadows(),
                "warnings", result.warnings());
    }

    /** 单章卷纲重写（人工纠偏 / 管线自愈共用）：返回重写后的规划行。 */
    public Map<String, Object> replanChapter(long novelId, int chapterNo, String reason) {
        ChapterDTO ch = volumePlanService.replanChapter(novelId, chapterNo,
                reason == null || reason.isBlank() ? "人工触发重写" : reason);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chapterNo", ch.getChapterNo());
        m.put("title", ch.getTitle());
        m.put("goal", ch.getGoal());
        m.put("hook", ch.getHook());
        m.put("timeNote", ch.getTimeNote());
        return m;
    }

    private static Map<String, Object> rowMap(VolumePlanService.PlanRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", r.chapterNo());
        m.put("title", r.title());
        m.put("goal", r.goal());
        m.put("hook", r.hook());
        m.put("timeNote", r.timeNote());
        m.put("foreshadows", r.foreshadows().stream()
                .map(f -> f.code() == null || f.code().isBlank() ? "新埋：" + f.content() : f.code())
                .toList());
        m.put("budgetMin", r.budgetMin());
        m.put("budgetMax", r.budgetMax());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStrList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }

    // ===== 章纲 =====

    public List<OutlineService.SceneSpec> scenes(long novelId, int chapterNo) {
        ChapterDTO ch = chapterData.find(novelId, chapterNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterNo));
        return outlineSpecs(ch.getId());
    }

    public List<OutlineService.SceneSpec> regenerate(long novelId, int chapterNo) {
        return pipelineService.regenerateOutline(novelId, chapterNo);
    }

    private List<OutlineService.SceneSpec> outlineSpecs(long chapterId) {
        return sceneData.findByChapter(chapterId).stream()
                .map(s -> new OutlineService.SceneSpec(s.getSceneNo(), s.getGoal(),
                        toList(s.getPresent()), toList(s.getMustReveal()), toList(s.getMustNot()),
                        s.getWordsBudget()))
                .toList();
    }

    /** 场景 JSON 列表字段（present/must_reveal/must_not）解析；坏数据降级为空表。 */
    private List<String> toList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
