package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.CanonDocDAO;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.SceneDAO;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划三件套的系统管理：大纲（canon misc/大纲，进出生成上下文）、
 * 卷纲（chapters 规划行：逐章 goal/hook/预算，可增删改）、章纲（AI 场景拆解：可查看/强制重出）。
 */
@Service
public class PlanningService {

    private static final String STORY_KIND = "misc";
    private static final String STORY_NAME = "大纲";

    private final CanonDocDAO canonDocDAO;
    private final ChapterDAO chapterDAO;
    private final SceneDAO sceneDAO;
    private final ChapterPipelineService pipelineService;
    private final ObjectMapper mapper;

    public PlanningService(CanonDocDAO canonDocDAO, ChapterDAO chapterDAO,
                           SceneDAO sceneDAO, ChapterPipelineService pipelineService,
                           ObjectMapper mapper) {
        this.canonDocDAO = canonDocDAO;
        this.chapterDAO = chapterDAO;
        this.sceneDAO = sceneDAO;
        this.pipelineService = pipelineService;
        this.mapper = mapper;
    }

    // ===== 大纲 =====

    public String storyOutline(long novelId) {
        return canonDocDAO.findContentByKindName(novelId, STORY_KIND, STORY_NAME);
    }

    public void saveStoryOutline(long novelId, String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "大纲内容不能为空");
        }
        Long id = canonDocDAO.findId(novelId, STORY_KIND, STORY_NAME);
        if (id == null) {
            canonDocDAO.insert(novelId, STORY_KIND, STORY_NAME, content);
        } else {
            canonDocDAO.updateContent(id, content);
        }
    }

    // ===== 卷纲 =====

    public List<Map<String, Object>> volumes(long novelId) {
        List<ChapterDO> chapters = chapterDAO.listSummariesByNovel(novelId);
        Map<Integer, List<ChapterDO>> byVolume = new LinkedHashMap<>();
        for (ChapterDO c : chapters) {
            byVolume.computeIfAbsent(c.volumeNo() == null ? 0 : c.volumeNo(), k -> new ArrayList<>()).add(c);
        }
        List<Map<String, Object>> volumes = new ArrayList<>();
        for (var e : byVolume.entrySet()) {
            ChapterDO first = e.getValue().get(0);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("volNo", e.getKey());
            v.put("arc", e.getKey() == 0 ? "未分卷" : first.arc());
            v.put("chapters", e.getValue().stream().map(this::toPlanVO).toList());
            volumes.add(v);
        }
        return volumes;
    }

    private Map<String, Object> toPlanVO(ChapterDO c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("chapterNo", c.chapterNo());
        m.put("title", c.title());
        m.put("goal", c.goal());
        m.put("hook", c.hook());
        m.put("status", c.status());
        m.put("hasText", c.fullText() != null && !c.fullText().isBlank());
        m.put("budgetMin", c.budgetMin());
        m.put("budgetMax", c.budgetMax());
        m.put("sceneCount", sceneDAO.countByChapter(c.id()));
        return m;
    }

    public void updatePlan(long chapterId, Integer volNo, String arc, String title,
                           String goal, String hook, Integer budgetMin, Integer budgetMax) {
        ChapterDO ch = chapterDAO.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        chapterDAO.updatePlan(chapterId,
                volNo != null ? volNo : ch.volumeNo(),
                arc != null ? arc : ch.arc(),
                title != null ? title : ch.title(),
                goal != null ? goal : ch.goal(),
                hook != null ? hook : ch.hook(),
                budgetMin != null ? budgetMin : ch.budgetMin(),
                budgetMax != null ? budgetMax : ch.budgetMax());
    }

    public void addPlan(long novelId, int chapterNo, int volNo, String arc, String title,
                        String goal, String hook, int budgetMin, int budgetMax) {
        if (chapterDAO.exists(novelId, chapterNo)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "章号已存在: " + chapterNo);
        }
        chapterDAO.insertPlan(novelId, chapterNo, volNo, arc, title, goal, hook, "[]", "[]",
                budgetMin, budgetMax);
    }

    public void deletePlan(long chapterId) {
        ChapterDO ch = chapterDAO.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        if (ch.fullText() != null && !ch.fullText().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "第 " + ch.chapterNo() + " 章已有正文，禁止删除");
        }
        chapterDAO.softDeletePlan(chapterId);
    }

    // ===== 章纲 =====

    public List<OutlineService.SceneSpec> scenes(long novelId, int chapterNo) {
        ChapterDO ch = chapterDAO.find(novelId, chapterNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterNo));
        return outlineSpecs(ch.id());
    }

    public List<OutlineService.SceneSpec> regenerate(long novelId, int chapterNo) {
        return pipelineService.regenerateOutline(novelId, chapterNo);
    }

    private List<OutlineService.SceneSpec> outlineSpecs(long chapterId) {
        return sceneDAO.findByChapter(chapterId).stream()
                .map(s -> new OutlineService.SceneSpec(s.sceneNo(), s.goal(),
                        toList(s.present()), toList(s.mustReveal()), toList(s.mustNot()),
                        s.wordsBudget()))
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
