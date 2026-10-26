package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CalibrationMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveBeatPoolMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveChapterContractMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveGateReviewMapper;
import com.zzdzz.novelgen.derive.mapper.DraftHistoryMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.QcReportMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.derive.mapper.WorldStateMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.PlanV2StateCodec;
import com.zzdzz.novelgen.model.entity.derive.CalibrationDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveBeatPoolDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveChapterContractDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveGateReviewDO;
import com.zzdzz.novelgen.model.entity.derive.DraftHistoryDO;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO;
import com.zzdzz.novelgen.model.entity.derive.WorldStateDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.vo.derive.RunResetVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 清空 run 的⑥及之后数据；⑤采样和焊接配置保留。 */
@Service
@RequiredArgsConstructor
public class DeriveResetService {

    private final RunMapper runMapper;
    private final ChapterMapper chapterMapper;
    private final NovelMapper novelMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final QcReportMapper qcReportMapper;
    private final CalibrationMapper calibrationMapper;
    private final DraftHistoryMapper draftHistoryMapper;
    private final WorldStateMapper worldStateMapper;
    private final WorkFingerprintMapper workFingerprintMapper;
    private final VolumePlanMapper volumePlanMapper;
    private final CharacterPlanMapper characterPlanMapper;
    private final StoryThreadMapper storyThreadMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeriveBeatPoolMapper beatPoolMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeriveChapterContractMapper chapterContractMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeriveGateReviewMapper gateReviewMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public RunResetVO clearPlanning(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (DeriveAutoFlowService.autoRunning(runId) || DeriveWriteService.loopRunning(runId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 正在执行，先停止全流程/连跑再清空");
        }

        long novelId = run.getNovelId() == null ? 0 : run.getNovelId();
        List<ChapterDO> chapters = novelId == 0 ? List.of()
                : chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId));
        long textChapters = chapters.stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .count();
        boolean fullText = !chapters.isEmpty() && textChapters == chapters.size();
        if ("PASSED".equals(run.getStatus()) || fullText) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该 run 已有完整正文，禁止清空规划；请使用回炉或重新建 run");
        }

        int deletedBeats = runId == 0 ? 0 : beatInstanceMapper.delete(
                new LambdaQueryWrapper<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO>()
                        .eq(com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO::getRunId, runId));
        int deletedReports = qcReportMapper.delete(
                new LambdaQueryWrapper<QcReportDO>().eq(QcReportDO::getRunId, runId));
        calibrationMapper.delete(new LambdaQueryWrapper<CalibrationDO>()
                .eq(CalibrationDO::getRunId, runId));
        draftHistoryMapper.delete(new LambdaQueryWrapper<DraftHistoryDO>()
                .eq(DraftHistoryDO::getRunId, runId));

        int deletedChapters = 0;
        if (novelId > 0) {
            volumePlanMapper.delete(new LambdaQueryWrapper<VolumePlanDO>()
                    .eq(VolumePlanDO::getRunId, runId));
            characterPlanMapper.delete(new LambdaQueryWrapper<CharacterPlanDO>()
                    .eq(CharacterPlanDO::getRunId, runId));
            storyThreadMapper.delete(new LambdaQueryWrapper<StoryThreadDO>()
                    .eq(StoryThreadDO::getRunId, runId));
            deletedChapters = chapterMapper.delete(new LambdaQueryWrapper<ChapterDO>()
                    .eq(ChapterDO::getNovelId, novelId));
            worldStateMapper.delete(new LambdaQueryWrapper<WorldStateDO>()
                    .eq(WorldStateDO::getNovelId, novelId));
            workFingerprintMapper.delete(new LambdaQueryWrapper<WorkFingerprintDO>()
                    .eq(WorkFingerprintDO::getNovelId, novelId));
            // 先解除 run.novel_id 外键，再删除成品书；否则 FK 会阻止 delete。
            runMapper.clearPlanning(runId, stripPostPlanningSteps(run.getStepStatus()));
            novelMapper.deleteById(novelId);
        } else {
            runMapper.clearPlanning(runId, stripPostPlanningSteps(run.getStepStatus()));
        }
        return new RunResetVO(runId, "PLANNING", deletedChapters, deletedBeats, deletedReports,
                "已清空⑥及之后的规划/正文数据；⑤采样、焊接和参数保留");
    }

    /**
     * P6 根因回炉：SPINE 保留规模并重建故事合同下游；SCALE 连规模一起重判。
     * 已写正文的 run 禁止调用，避免清掉成品。
     */
    @Transactional
    public void replanFrom(long runId, String target) {
        if (!"SPINE".equals(target) && !"SCALE".equals(target)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "只支持从 SPINE/SCALE 重规划");
        }
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!("REWORK".equals(run.getStatus()) || "DRAFTING".equals(run.getStatus()))) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "根因回炉需要 run 处于 REWORK/DRAFTING（当前 " + run.getStatus() + "）");
        }
        long novelId = run.getNovelId() == null ? 0 : run.getNovelId();
        List<ChapterDO> chapters = novelId == 0 ? List.of()
                : chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId));
        if (chapters.stream().anyMatch(c -> c.getContent() != null && !c.getContent().isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "已有正文，拒绝自动重建故事脊柱；请人工处理或新建 run");
        }

        beatInstanceMapper.delete(new LambdaQueryWrapper<com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO>()
                .eq(com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO::getRunId, runId));
        qcReportMapper.delete(new LambdaQueryWrapper<QcReportDO>()
                .eq(QcReportDO::getRunId, runId));
        calibrationMapper.delete(new LambdaQueryWrapper<CalibrationDO>()
                .eq(CalibrationDO::getRunId, runId));
        draftHistoryMapper.delete(new LambdaQueryWrapper<DraftHistoryDO>()
                .eq(DraftHistoryDO::getRunId, runId));
        volumePlanMapper.delete(new LambdaQueryWrapper<VolumePlanDO>()
                .eq(VolumePlanDO::getRunId, runId));
        characterPlanMapper.delete(new LambdaQueryWrapper<CharacterPlanDO>()
                .eq(CharacterPlanDO::getRunId, runId));
        storyThreadMapper.delete(new LambdaQueryWrapper<StoryThreadDO>()
                .eq(StoryThreadDO::getRunId, runId));
        if (beatPoolMapper != null) {
            beatPoolMapper.delete(new LambdaQueryWrapper<DeriveBeatPoolDO>()
                    .eq(DeriveBeatPoolDO::getRunId, runId));
        }
        if (chapterContractMapper != null) {
            chapterContractMapper.delete(new LambdaQueryWrapper<DeriveChapterContractDO>()
                    .eq(DeriveChapterContractDO::getRunId, runId));
        }
        if (gateReviewMapper != null) {
            gateReviewMapper.delete(new LambdaQueryWrapper<DeriveGateReviewDO>()
                    .eq(DeriveGateReviewDO::getRunId, runId));
        }
        if (novelId > 0) {
            chapterMapper.delete(new LambdaQueryWrapper<ChapterDO>()
                    .eq(ChapterDO::getNovelId, novelId));
            worldStateMapper.delete(new LambdaQueryWrapper<WorldStateDO>()
                    .eq(WorldStateDO::getNovelId, novelId));
            workFingerprintMapper.delete(new LambdaQueryWrapper<WorkFingerprintDO>()
                    .eq(WorkFingerprintDO::getNovelId, novelId));
            // P6 Task4：根因回炉保留 protagonist/cast，只清 brief/truth，供角色合同重做读取 role/persona。
            novelMapper.clearDraftBriefTruth(novelId);
            novelMapper.updateStats(novelId, 0, 0);
        }
        runMapper.updateStepStatus(runId, resetPlanState(run.getStepStatus(), target));
        runMapper.updateStatus(runId, "PLANNING", null);
    }

    /** 纯状态变换：保留规模或退到 SCALE，并保留 GATE 轮次/指令供同一轮熔断计数。 */
    static String resetPlanState(String stepStatus, String target) {
        if (!"SPINE".equals(target) && !"SCALE".equals(target)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未知规划回炉目标: " + target);
        }
        try {
            JsonNode parsed = new ObjectMapper().readTree(
                    stepStatus == null || stepStatus.isBlank() ? "{}" : stepStatus);
            ObjectNode root = parsed.isObject()
                    ? (ObjectNode) parsed.deepCopy() : new ObjectMapper().createObjectNode();
            root.put("v", 1);
            ObjectNode steps = root.path("steps").isObject()
                    ? (ObjectNode) root.path("steps") : root.putObject("steps");
            PlanContracts.PlanV2State old = PlanV2StateCodec.read(
                    steps.path("PLAN_V2").toString());
            PlanContracts.PlanV2State next;
            if ("SPINE".equals(target)) {
                if (old.scope() == null) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "回炉 SPINE 需要已有规模合同");
                }
                next = new PlanContracts.PlanV2State(
                        PlanContracts.PlanUnit.SPINE,
                        List.of(PlanContracts.PlanUnit.SCALE),
                        old.scope(), Map.of());
            } else {
                next = PlanV2StateCodec.initial();
            }
            steps.set("PLAN_V2", new ObjectMapper().valueToTree(next));
            steps.remove("STORY_SPINE");
            steps.remove("DRAFT");
            steps.remove("QC");
            steps.remove("QC_REPAIR_TARGET");
            return DeriveGateService.gateResetForRootRebuild(root.toString());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "run.step_status 解析失败，无法安全回炉");
        }
    }

    private String stripPostPlanningSteps(String stepStatus) {
        try {
            JsonNode parsed = objectMapper.readTree(
                    stepStatus == null || stepStatus.isBlank() ? "{}" : stepStatus);
            ObjectNode root = parsed.isObject()
                    ? (ObjectNode) parsed.deepCopy() : objectMapper.createObjectNode();
            root.put("v", 1);
            ObjectNode steps = root.path("steps").isObject()
                    ? (ObjectNode) root.path("steps") : root.putObject("steps");
            steps.remove("PLAN");
            steps.remove("PLAN_V2");
            steps.remove("STORY_SPINE");
            steps.remove("TRUTH_CONTRACT");
            steps.remove("DRAFT");
            steps.remove("GATE");
            steps.remove("QC");
            steps.remove("QC_REPAIR_TARGET");
            return root.toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run.step_status 解析失败，无法安全清空");
        }
    }
}
