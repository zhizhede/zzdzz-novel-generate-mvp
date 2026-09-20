package com.zzdzz.novelgen.controller;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.dto.PlanModeDTO;
import com.zzdzz.novelgen.model.entity.RetroProposalDO;
import com.zzdzz.novelgen.service.GenerationQueueService;
import com.zzdzz.novelgen.service.OutlineService;
import com.zzdzz.novelgen.service.PlanningService;
import com.zzdzz.novelgen.service.VolumeReviewService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.RetroProposalDataService;
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

/**
 * 规划：大纲（增改查，进生成上下文）/ 卷纲（规划行增删改 + AI 规划一卷）/ 章纲（查看 + 强制重出）/ 规划模式。
 */
@RestController
@RequestMapping("/api/novels/{novelId}/planning")
@RequiredArgsConstructor
public class PlanningController {

    private final PlanningService planningService;
    private final VolumeReviewService volumeReviewService;
    private final GenerationQueueService queueService;
    private final NovelDataService novelData;
    private final RetroProposalDataService proposalData;


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
                (String) body.get("timeNote"),
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
                (String) body.get("timeNote"),
                body.get("budgetMin") instanceof Number n ? n.intValue() : null,
                body.get("budgetMax") instanceof Number n ? n.intValue() : null);
        return Result.ok();
    }

    @DeleteMapping("/chapters/{chapterId}/plan")
    public Result<Void> deletePlan(@PathVariable long chapterId) {
        planningService.deletePlan(chapterId);
        return Result.ok();
    }

    // ===== AI 卷纲规划 =====

    @GetMapping("/mode")
    public Result<Map<String, String>> modes(@PathVariable long novelId) {
        return Result.ok(planningService.modes(novelId));
    }

    @PutMapping("/plan-mode")
    public Result<Void> setPlanMode(@PathVariable long novelId, @RequestBody PlanModeDTO dto) {
        planningService.setPlanMode(novelId, dto.mode());
        return Result.ok();
    }

    /** ⑤ 任务化：AI 规划一卷入队（秒回；进度见工作台队列，完成后队列 DONE）。 */
    @PostMapping("/volume/auto-plan-async")
    public Result<Map<String, Object>> autoPlanAsync(@PathVariable long novelId,
                                                     @RequestBody Map<String, Object> body) {
        int volNo = ((Number) body.get("volNo")).intValue();
        int from = ((Number) body.get("from")).intValue();
        Integer to = body.get("to") instanceof Number n ? n.intValue() : null;
        String seed = (String) body.get("seedOutline");
        String title = String.valueOf(novelData.getById(novelId).getTitle());
        long taskId = queueService.submitPlan(novelId, title, volNo, from, to, seed, null);
        return Result.ok(Map.of("taskId", taskId));
    }

    /** 流 D：某卷复盘建议/提案列表（含采纳状态）。 */
    @GetMapping("/volumes/{volNo}/proposals")
    public Result<List<RetroProposalDO>> proposals(@PathVariable long novelId, @PathVariable int volNo) {
        return Result.ok(proposalData.listByVolume(novelId, volNo));
    }

    /** 流 D：提案决策（采纳/忽略）。 */
    @PostMapping("/retro/{proposalId}/decision")
    public Result<Void> decideProposal(@PathVariable long proposalId,
                                       @RequestBody Map<String, Object> body) {
        boolean adopt = Boolean.TRUE.equals(body.get("adopt"));
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        if (!proposalData.decide(proposalId, adopt, note)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "提案不存在或已决策");
        }
        return Result.ok();
    }

    /** AI 规划一卷（同步，约 2-10 分钟）：auto 模式 AI 审校通过直接落库；manual 模式返回草稿。 */
    @PostMapping("/volume/auto-plan")
    public Result<Map<String, Object>> autoPlan(@PathVariable long novelId,
                                                @RequestBody Map<String, Object> body) {
        return Result.ok(planningService.autoPlan(novelId,
                ((Number) body.get("volNo")).intValue(),
                ((Number) body.get("from")).intValue(),
                body.get("to") instanceof Number n ? n.intValue() : null,
                (String) body.get("seedOutline")));
    }

    /** manual 模式采纳草稿（可先人工修改）：结构校验后直接落库，不再过 AI 审校。 */
    @PostMapping("/volume/adopt")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> adoptDraft(@PathVariable long novelId,
                                                  @RequestBody Map<String, Object> body) {
        return Result.ok(planningService.adoptDraft(novelId,
                ((Number) body.get("volNo")).intValue(),
                (String) body.get("arc"),
                (String) body.get("brief"),
                (List<Map<String, Object>>) body.get("rows")));
    }

    /** 单章卷纲重写（人工纠偏；管线失败自愈走同一服务方法）。 */
    @PostMapping("/chapters/{chapterNo}/replan")
    public Result<Map<String, Object>> replan(@PathVariable long novelId, @PathVariable int chapterNo,
                                              @RequestBody(required = false) Map<String, String> body) {
        return Result.ok(planningService.replanChapter(novelId, chapterNo,
                body == null ? null : body.get("reason")));
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

    // ===== 卷级复盘 =====

    /** 复盘一卷（同步，约 1-3 分钟）：机械对账 + LLM 漂移分析，报告落库并返回。 */
    @PostMapping("/volumes/{volNo}/review")
    public Result<Map<String, Object>> reviewVolume(@PathVariable long novelId,
                                                    @PathVariable int volNo) {
        return Result.ok(volumeReviewService.review(novelId, volNo));
    }

    /** 上次复盘报告；尚未复盘返回 null。 */
    @GetMapping("/volumes/{volNo}/review")
    public Result<String> lastReview(@PathVariable long novelId, @PathVariable int volNo) {
        return Result.ok(volumeReviewService.findLatest(novelId, volNo));
    }
}
