package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.service.OutlineService;
import com.zzdzz.novelgen.service.PlanningService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 规划三件套：大纲（增改查，进生成上下文）/ 卷纲（规划行增删改）/ 章纲（查看 + 强制重出）。 */
@RestController
@RequestMapping("/api/novels/{novelId}/planning")
public class PlanningController {

    private final PlanningService planningService;

    public PlanningController(PlanningService planningService) {
        this.planningService = planningService;
    }

    // ===== 大纲 =====

    @GetMapping("/story")
    public Result<Map<String, String>> story(@PathVariable long novelId) {
        return Result.ok(Map.of("content", planningService.storyOutline(novelId) == null
                ? "" : planningService.storyOutline(novelId)));
    }

    @PutMapping("/story")
    public Result<Void> saveStory(@PathVariable long novelId, @RequestBody Map<String, String> body) {
        planningService.saveStoryOutline(novelId, body.get("content"));
        return Result.ok();
    }

    // ===== 卷纲 =====

    @GetMapping("/volumes")
    public Result<List<Map<String, Object>>> volumes(@PathVariable long novelId) {
        return Result.ok(planningService.volumes(novelId));
    }

    @PostMapping("/chapters")
    public Result<Void> addChapter(@PathVariable long novelId, @RequestBody Map<String, Object> body) {
        planningService.addPlan(novelId,
                ((Number) body.get("chapterNo")).intValue(),
                body.get("volNo") != null ? ((Number) body.get("volNo")).intValue() : 1,
                (String) body.get("arc"),
                (String) body.get("title"),
                (String) body.get("goal"),
                (String) body.get("hook"),
                body.get("budgetMin") != null ? ((Number) body.get("budgetMin")).intValue() : 1800,
                body.get("budgetMax") != null ? ((Number) body.get("budgetMax")).intValue() : 2800);
        return Result.ok();
    }

    @PutMapping("/chapters/{chapterId}/plan")
    public Result<Void> updatePlan(@PathVariable long chapterId, @RequestBody Map<String, Object> body) {
        planningService.updatePlan(chapterId,
                body.get("volNo") instanceof Number n ? n.intValue() : null,
                (String) body.get("arc"),
                (String) body.get("title"),
                (String) body.get("goal"),
                (String) body.get("hook"),
                body.get("budgetMin") instanceof Number n ? n.intValue() : null,
                body.get("budgetMax") instanceof Number n ? n.intValue() : null);
        return Result.ok();
    }

    @DeleteMapping("/chapters/{chapterId}/plan")
    public Result<Void> deletePlan(@PathVariable long chapterId) {
        planningService.deletePlan(chapterId);
        return Result.ok();
    }

    // ===== 章纲 =====

    @GetMapping("/chapters/{chapterNo}/scenes")
    public Result<List<OutlineService.SceneSpec>> scenes(@PathVariable long novelId,
                                                         @PathVariable int chapterNo) {
        return Result.ok(planningService.scenes(novelId, chapterNo));
    }

    /** 强制重出章纲（同步，约 1-2 分钟；已有正文的章会被拒绝）。 */
    @PostMapping("/chapters/{chapterNo}/outline/regenerate")
    public Result<List<OutlineService.SceneSpec>> regenerate(@PathVariable long novelId,
                                                             @PathVariable int chapterNo) {
        return Result.ok(planningService.regenerate(novelId, chapterNo));
    }
}
