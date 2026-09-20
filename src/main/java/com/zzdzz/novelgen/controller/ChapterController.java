package com.zzdzz.novelgen.controller;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.vo.ChapterDetailVO;
import com.zzdzz.novelgen.model.vo.ChapterListItemVO;
import com.zzdzz.novelgen.model.vo.ReviewVO;
import com.zzdzz.novelgen.service.ChapterQueryService;
import com.zzdzz.novelgen.service.ChapterPipelineService;
import com.zzdzz.novelgen.service.GenerationQueueService;
import com.zzdzz.novelgen.service.ReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 章查询、AI 审校触发与人工审批/打回。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChapterController {

    private final ChapterQueryService chapterQueryService;
    private final ChapterPipelineService pipelineService;
    private final ReviewService reviewService;
    private final GenerationQueueService queueService;


    @GetMapping("/novels/{novelId}/chapters")
    public Result<List<ChapterListItemVO>> list(@PathVariable long novelId) {
        return Result.ok(chapterQueryService.listByNovel(novelId));
    }

    /** 待审批聚合（人工通道常设能力）：本书全部 PENDING_APPROVAL 章。 */
    @GetMapping("/novels/{novelId}/pending-approvals")
    public Result<List<ChapterListItemVO>> pendingApprovals(@PathVariable long novelId) {
        return Result.ok(chapterQueryService.pendingApprovals(novelId));
    }

    @GetMapping("/chapters/{id}")
    public Result<ChapterDetailVO> detail(@PathVariable long id) {
        return Result.ok(chapterQueryService.detail(id));
    }

    /** 章生成档案：步骤状态行 + 按章 LLM 台账（元数据）+ 全轮次门禁/评审判定 + 节点小计。 */
    @GetMapping("/chapters/{id}/trace")
    public Result<com.zzdzz.novelgen.model.vo.ChapterTraceVO> trace(@PathVariable long id) {
        return Result.ok(chapterQueryService.trace(id));
    }

    /** 人工审批（异步）：占位后立即返回，digest（事实账/世界状态/伏笔提议）后台生成，失败自动回退待审批。 */
    @PostMapping("/chapters/{id}/approve")
    public Result<Void> approve(@PathVariable long id) {
        pipelineService.approveAsync(id);
        return Result.ok();
    }

    /** 流 A 终稿打回：意见原文落库并注入下次章纲重写；清场后自动重新入队。 */
    @PostMapping("/chapters/{id}/reject")
    public Result<Long> reject(@PathVariable long id, @RequestBody(required = false) RejectReq req) {
        String reason = req == null || req.reason() == null ? "" : req.reason().strip();
        if (reason.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "打回意见不能为空");
        }
        ChapterPipelineService.RejectTarget target = pipelineService.reject(id, reason);
        return Result.ok(queueService.submitById(target.novelId(), target.novelTitle(),
                target.chapterNo(), target.chapterNo(), null));
    }

    /** 流 A 章纲卡点（manual 模式）：批准放行 / 打回带意见重出章纲。 */
    @PostMapping("/chapters/{id}/outline-decision")
    public Result<Long> outlineDecision(@PathVariable long id, @RequestBody OutlineDecisionReq req) {
        if (req == null || req.action() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "action 必填（APPROVE/REJECT）");
        }
        if ("APPROVE".equalsIgnoreCase(req.action())) {
            ChapterPipelineService.RejectTarget target = pipelineService.approveOutline(id);
            return Result.ok(queueService.submitById(target.novelId(), target.novelTitle(),
                    target.chapterNo(), target.chapterNo(), null));
        }
        String reason = req.reason() == null ? "" : req.reason().strip();
        if (reason.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "打回章纲必须附意见");
        }
        ChapterPipelineService.RejectTarget target = pipelineService.rejectOutline(id, reason);
        return Result.ok(queueService.submitById(target.novelId(), target.novelTitle(),
                target.chapterNo(), target.chapterNo(), null));
    }

    /** 流 A 扩展·事后否决：DIGESTED 章打回——清除本章事实账，重生成后 digest 重算；伏笔 flips 保留。 */
    @PostMapping("/chapters/{id}/veto")
    public Result<Long> veto(@PathVariable long id, @RequestBody(required = false) RejectReq req) {
        String reason = req == null || req.reason() == null ? "" : req.reason().strip();
        if (reason.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "否决意见不能为空");
        }
        ChapterPipelineService.RejectTarget target = pipelineService.veto(id, reason);
        return Result.ok(queueService.submitById(target.novelId(), target.novelTitle(),
                target.chapterNo(), target.chapterNo(), null));
    }

    /** 人工编辑场景草稿（Q5a）：保存后立即重过该场景机械门禁；仅生成前状态可用。 */
    @org.springframework.web.bind.annotation.PutMapping("/scenes/{id}/edit")
    public Result<Boolean> editScene(@PathVariable long id, @RequestBody(required = false) RejectReq req) {
        String text = req == null || req.reason() == null ? "" : req.reason().strip();
        if (text.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "场景内容不能为空");
        }
        return Result.ok(pipelineService.editSceneDraft(id, text));
    }

    /** 人工编辑正文（Q5b）：仅 PENDING_APPROVAL/DIGESTED；DIGESTED 编辑后回待审批、digest 重算。 */
    @org.springframework.web.bind.annotation.PutMapping("/chapters/{id}/fulltext")
    public Result<Void> editFullText(@PathVariable long id, @RequestBody(required = false) RejectReq req) {
        String text = req == null || req.reason() == null ? "" : req.reason();
        if (text.strip().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "正文不能为空");
        }
        pipelineService.editFullText(id, text);
        return Result.ok();
    }

    /** 对已有正文的章立即跑一次 AI 审校（回溯/人工触发），只落报告不动正文与状态。 */
    @PostMapping("/chapters/{id}/review")
    public Result<ReviewVO> review(@PathVariable long id) {
        reviewService.reviewExisting(id);
        return Result.ok(chapterQueryService.latestReview(id));
    }

    public record RejectReq(String reason) {}

    public record OutlineDecisionReq(String action, String reason) {}
}
