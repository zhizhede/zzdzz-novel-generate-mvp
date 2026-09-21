package com.zzdzz.novelgen.controller;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.vo.PlanModeVO;
import com.zzdzz.novelgen.model.vo.RetroProposalVO;
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
        return Result.success(Map.of("content", planningService.storyOutline(novelId) == null
                ? "" : planningService.storyOutline(novelId)));
    }

    @PutMapping("/story")
    public Result<Void> saveStory(@PathVariable long novelId, @RequestBody StoryOutlineVO dto) {
        planningService.saveStoryOutline(novelId, dto.content());
        return Result.success();
    }

    // ===== 卷纲 =====

    @GetMapping("/volumes")
    public Result<List<Map<String, Object>>> volumes(@PathVariable long novelId) {
        return Result.success(planningService.volumes(novelId));
    }

    @PostMapping("/chapters")
    public Result<Void> addChapter(@PathVariable long novelId, @RequestBody ChapterPlanVO dto) {
        if (dto.chapterNo() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "chapterNo 必填");
        }
        planningService.addPlan(novelId, dto.chapterNo(),
                dto.volNo() != null ? dto.volNo() : 1,
                dto.arc(), dto.title(), dto.goal(), dto.hook(), dto.timeNote(),
                dto.budgetMin() != null ? dto.budgetMin() : 1800,
                dto.budgetMax() != null ? dto.budgetMax() : 2800);
        return Result.success();
    }

    @PutMapping("/chapters/{chapterId}/plan")
    public Result<Void> updatePlan(@PathVariable long chapterId, @RequestBody ChapterPlanVO dto) {
        planningService.updatePlan(chapterId,
                dto.volNo(),
                dto.arc(), dto.title(), dto.goal(), dto.hook(), dto.timeNote(),
                dto.budgetMin(), dto.budgetMax());
        return Result.success();
    }

    @DeleteMapping("/chapters/{chapterId}/plan")
    public Result<Void> deletePlan(@PathVariable long chapterId) {
        planningService.deletePlan(chapterId);
        return Result.success();
    }

    // ===== AI 卷纲规划 =====

    @GetMapping("/mode")
    public Result<Map<String, String>> modes(@PathVariable long novelId) {
        return Result.success(planningService.modes(novelId));
    }

    @PutMapping("/plan-mode")
    public Result<Void> setPlanMode(@PathVariable long novelId, @RequestBody PlanModeVO dto) {
        planningService.setPlanMode(novelId, dto.mode());
        return Result.success();
    }

    /** ⑤ 任务化：AI 规划一卷入队（秒回；进度见工作台队列，完成后队列 DONE）。 */
    @PostMapping("/volume/auto-plan-async")
    public Result<Map<String, Object>> autoPlanAsync(@PathVariable long novelId,
                                                     @RequestBody VolumeAutoPlanVO dto) {
        if (dto.volNo() == null || dto.from() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "参数不合法：volNo/from 必填");
        }
        String title = String.valueOf(novelData.getById(novelId).getTitle());
        long taskId = queueService.submitPlan(novelId, title, dto.volNo(), dto.from(), dto.to(), dto.seedOutline(), null);
        return Result.success(Map.of("taskId", taskId));
    }

    /** 流 D：某卷复盘建议/提案列表（含采纳状态）。 */
    @GetMapping("/volumes/{volNo}/proposals")
    public Result<List<RetroProposalVO>> proposals(@PathVariable long novelId, @PathVariable int volNo) {
        return Result.success(proposalData.listByVolumeVO(novelId, volNo));
    }

    /** 流 D：提案决策（采纳/忽略）。 */
    @PostMapping("/retro/{proposalId}/decision")
    public Result<Void> decideProposal(@PathVariable long proposalId,
                                       @RequestBody ProposalDecisionVO dto) {
        boolean adopt = Boolean.TRUE.equals(dto.adopt());
        if (!proposalData.decide(proposalId, adopt, dto.note())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "提案不存在或已决策");
        }
        return Result.success();
    }

    /** AI 规划一卷（同步，约 2-10 分钟）：auto 模式 AI 审校通过直接落库；manual 模式返回草稿。 */
    @PostMapping("/volume/auto-plan")
    public Result<Map<String, Object>> autoPlan(@PathVariable long novelId,
                                                @RequestBody VolumeAutoPlanVO dto) {
        if (dto.volNo() == null || dto.from() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "参数不合法：volNo/from 必填");
        }
        return Result.success(planningService.autoPlan(novelId, dto.volNo(), dto.from(), dto.to(), dto.seedOutline()));
    }

    /** manual 模式采纳草稿（可先人工修改）：结构校验后直接落库，不再过 AI 审校。 */
    @PostMapping("/volume/adopt")
    public Result<Map<String, Object>> adoptDraft(@PathVariable long novelId,
                                                  @RequestBody VolumeAdoptVO dto) {
        if (dto.volNo() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "volNo 必填");
        }
        return Result.success(planningService.adoptDraft(novelId, dto.volNo(), dto.arc(), dto.brief(), dto.rows()));
    }

    /** 单章卷纲重写（人工纠偏；管线失败自愈走同一服务方法）。 */
    @PostMapping("/chapters/{chapterNo}/replan")
    public Result<Map<String, Object>> replan(@PathVariable long novelId, @PathVariable int chapterNo,
                                              @RequestBody(required = false) ReplanVO dto) {
        return Result.success(planningService.replanChapter(novelId, chapterNo,
                dto == null ? null : dto.reason()));
    }

    // ===== 章纲 =====

    @GetMapping("/chapters/{chapterNo}/scenes")
    public Result<List<OutlineService.SceneSpec>> scenes(@PathVariable long novelId,
                                                         @PathVariable int chapterNo) {
        return Result.success(planningService.scenes(novelId, chapterNo));
    }

    /** 强制重出章纲（同步，约 1-2 分钟；已有正文的章会被拒绝）。 */
    @PostMapping("/chapters/{chapterNo}/outline/regenerate")
    public Result<List<OutlineService.SceneSpec>> regenerate(@PathVariable long novelId,
                                                             @PathVariable int chapterNo) {
        return Result.success(planningService.regenerate(novelId, chapterNo));
    }

    // ===== 卷级复盘 =====

    /** 复盘一卷（同步，约 1-3 分钟）：机械对账 + LLM 漂移分析，报告落库并返回。 */
    @PostMapping("/volumes/{volNo}/review")
    public Result<Map<String, Object>> reviewVolume(@PathVariable long novelId,
                                                    @PathVariable int volNo) {
        return Result.success(volumeReviewService.review(novelId, volNo));
    }

    /** 上次复盘报告；尚未复盘返回 null。 */
    @GetMapping("/volumes/{volNo}/review")
    public Result<String> lastReview(@PathVariable long novelId, @PathVariable int volNo) {
        return Result.success(volumeReviewService.findLatest(novelId, volNo));
    }

    public record StoryOutlineVO(String content) {}

    /** 卷纲规划行（新增/更新共用；更新时以路径 chapterId 为准）。 */
    public record ChapterPlanVO(Integer chapterNo, Integer volNo, String arc, String title, String goal,
                                 String hook, String timeNote, Integer budgetMin, Integer budgetMax) {}

    /** AI 规划一卷（同步 auto-plan / 异步 auto-plan-async 共用）。 */
    public record VolumeAutoPlanVO(Integer volNo, Integer from, Integer to, String seedOutline) {}

    /** manual 草稿采纳；rows 为动态结构保持 Map。 */
    public record VolumeAdoptVO(Integer volNo, String arc, String brief, List<Map<String, Object>> rows) {}

    public record ProposalDecisionVO(Boolean adopt, String note) {}

    public record ReplanVO(String reason) {}
}
