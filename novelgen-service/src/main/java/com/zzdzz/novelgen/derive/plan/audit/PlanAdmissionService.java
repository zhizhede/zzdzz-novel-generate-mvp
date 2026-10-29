package com.zzdzz.novelgen.derive.plan.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanProjectionService;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.derive.service.DeriveContractService;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把“能否开始正文”收敛为确定性 admission。
 *
 * <p>提交顺序固定为 projection → snapshot.admit；整个序列同事务，任一步失败都不会留下
 * ADMITTED 快照或 active revision。语义审稿只追加 ADVISORY，不能改变准入。
 */
@Service
public class PlanAdmissionService {

    private final PlanAuditor auditor;
    private final PlanSnapshotService snapshotService;
    private final PlanProjectionService projectionService;
    private final DeriveContractService contractService;
    private final RunMapper runMapper;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SemanticPlanReviewService semanticReviewService;

    /** 创建计划准入服务。 */
    public PlanAdmissionService(
            PlanAuditor auditor,
            PlanSnapshotService snapshotService,
            PlanProjectionService projectionService,
            DeriveContractService contractService,
            RunMapper runMapper,
            ObjectMapper objectMapper) {
        this.auditor = Objects.requireNonNull(auditor);
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.projectionService = Objects.requireNonNull(projectionService);
        this.contractService = Objects.requireNonNull(contractService);
        this.runMapper = Objects.requireNonNull(runMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** 注入可选语义审稿扩展；仅测试或后续阶段使用。 */
    public void setSemanticReviewService(SemanticPlanReviewService semanticReviewService) {
        this.semanticReviewService = semanticReviewService;
    }

    /** 准入一个 DRAFT 计划 revision。 */
    @Transactional
    public PlanAdmissionResult admit(long runId, int revision) {
        DerivePlanSnapshotDO snapshot = snapshotService.requireRevision(runId, revision);
        CompiledStoryPlan plan = snapshotService.loadCompiled(runId, revision);
        RunDO run = runMapper.selectById(runId);
        if (run == null || run.getNovelId() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行不存在或尚未关联成品书: " + runId);
        }
        StoryContract contract = contractService.load(run);
        if (contract == null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "计划准入前缺少冻结合同: " + runId);
        }

        PlanAuditReport deterministic = auditor.audit(plan, contract);
        List<PlanAuditIssue> issues = new ArrayList<>(deterministic.issues());
        if (semanticReviewService != null) {
            for (PlanAuditIssue issue : semanticReviewService.review(plan)) {
                issues.add(new PlanAuditIssue(
                        issue.code(), Severity.ADVISORY, issue.path(), issue.chapterNo(),
                        issue.beatKey(), issue.repairScope(), issue.message(),
                        issue.expected(), issue.actual()));
            }
        }
        PlanAuditReport report = new PlanAuditReport(issues);
        PlanAdmissionDecision decision = report.admitted()
                ? PlanAdmissionDecision.ADMIT
                : PlanAdmissionDecision.REPAIR;
        if (decision != PlanAdmissionDecision.ADMIT) {
            return new PlanAdmissionResult(runId, revision, decision, report);
        }

        String reportJson;
        try {
            reportJson = objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "计划准入报告序列化失败", e);
        }
        projectionService.activate(runId, run.getNovelId(), revision, plan);
        snapshotService.admit(runId, revision, reportJson, plan.canonicalHash());
        return new PlanAdmissionResult(runId, revision, decision, report);
    }

    /** 读取当前 DRAFT revision 的只读审计结果，不执行准入。 */
    @Transactional(readOnly = true)
    public PlanAuditReport auditOnly(long runId, int revision) {
        CompiledStoryPlan plan = snapshotService.loadCompiled(runId, revision);
        RunDO run = runMapper.selectById(runId);
        StoryContract contract = run == null ? null : contractService.load(run);
        if (contract == null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "计划审计前缺少冻结合同: " + runId);
        }
        return auditor.audit(plan, contract);
    }
}

