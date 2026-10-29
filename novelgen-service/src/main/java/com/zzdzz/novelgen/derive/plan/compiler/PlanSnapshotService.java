package com.zzdzz.novelgen.derive.plan.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DerivePlanSnapshotMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 计划快照生命周期：DRAFT → ADMITTED/SUPERSEDED，并维护 run 的唯一 active revision。 */
@Service
public class PlanSnapshotService {

    private static final String STALE = "PLAN_REVISION_STALE";
    private static final String CONFLICT = "PLAN_REVISION_CONFLICT";

    private final DerivePlanSnapshotMapper snapshotMapper;
    private final RunMapper runMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建计划快照服务。
     *
     * @param snapshotMapper 计划快照 mapper
     * @param runMapper run mapper
     * @param objectMapper 用于校验准入报告 JSON
     */
    public PlanSnapshotService(DerivePlanSnapshotMapper snapshotMapper,
                               RunMapper runMapper,
                               ObjectMapper objectMapper) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper);
        this.runMapper = Objects.requireNonNull(runMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * 以当前最大 revision+1 持久化一个 DRAFT 快照。
     *
     * @param runId 运行 id
     * @param plan 已编译计划
     * @return 带 revision、planHash 与 graph 的快照行
     */
    @Transactional
    public DerivePlanSnapshotDO persistDraft(long runId, CompiledStoryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.runId() != runId) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "计划快照 runId 与当前运行不一致");
        }
        DerivePlanSnapshotDO max = snapshotMapper.findMaxRevision(runId);
        int revision = max == null ? 1 : max.getRevision() + 1;
        DerivePlanSnapshotDO snapshot = new DerivePlanSnapshotDO();
        snapshot.setRunId(runId);
        snapshot.setRevision(revision);
        snapshot.setContractVersion(plan.contractVersion());
        snapshot.setContractHash(plan.contractHash());
        snapshot.setStatus("DRAFT");
        snapshot.setPlanHash(plan.canonicalHash());
        snapshot.setGraph(plan.canonicalJson());
        try {
            long id = snapshotMapper.insertFull(snapshot);
            snapshot.setId(id);
            return snapshot;
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    CONFLICT + "：计划 revision 并发写入冲突，请重试");
        }
    }

    /**
     * 按 revision 读取快照；不存在即视为 stale。
     *
     * @param runId 运行 id
     * @param revision 计划 revision
     * @return 快照行
     */
    @Transactional(readOnly = true)
    public DerivePlanSnapshotDO requireRevision(long runId, int revision) {
        DerivePlanSnapshotDO snapshot = snapshotMapper.findRevision(runId, revision);
        if (snapshot == null) {
            throw stale("找不到指定计划 revision: " + revision);
        }
        return snapshot;
    }

    /**
     * 读取 run 当前唯一 ADMITTED 快照。
     *
     * @param runId 运行 id
     * @return 当前 active 快照
     */
    @Transactional(readOnly = true)
    public DerivePlanSnapshotDO requireActive(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行不存在: " + runId);
        }
        if (run.getActivePlanRevision() == null) {
            throw stale("当前运行没有已准入计划快照");
        }
        DerivePlanSnapshotDO active =
                requireRevision(runId, run.getActivePlanRevision());
        if (!"ADMITTED".equals(active.getStatus())) {
            throw stale("active revision 不是 ADMITTED 状态: " + active.getRevision());
        }
        verifyGraph(active, CompiledStoryPlan.fromJson(active.getGraph()));
        return active;
    }

    /**
     * 校验调用方 expectedRevision 是否仍是当前 active 快照。
     *
     * @param runId 运行 id
     * @param expectedRevision 调用方持有的 revision
     * @return 匹配的 active 快照
     */
    @Transactional(readOnly = true)
    public DerivePlanSnapshotDO requireActive(long runId, int expectedRevision) {
        DerivePlanSnapshotDO active = requireActive(runId);
        if (active.getRevision() != expectedRevision) {
            throw stale("期望 revision=" + expectedRevision
                    + "，当前 active revision=" + active.getRevision());
        }
        return active;
    }

    /**
     * 把 DRAFT 快照原子准入为唯一 ADMITTED，并更新 run active 指针。
     *
     * @param runId 运行 id
     * @param revision 待准入 revision
     * @param reportJson 审计报告 JSON
     * @param planHash 调用方复核后的计划 hash
     */
    @Transactional
    public void admit(long runId, int revision, String reportJson, String planHash) {
        DerivePlanSnapshotDO snapshot = requireRevision(runId, revision);
        if (!"DRAFT".equals(snapshot.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    CONFLICT + "：只有 DRAFT 快照可以准入，当前状态=" + snapshot.getStatus());
        }
        if (!Objects.equals(snapshot.getPlanHash(), planHash)) {
            throw stale("准入 planHash 与快照不一致");
        }
        validateJson(reportJson);

        requireAffected(snapshotMapper.supersedeOtherAdmitted(runId, revision),
                "旧计划快照 supersede 失败");
        requireAffected(snapshotMapper.admit(runId, revision, reportJson, planHash),
                "计划快照准入失败");
        requireAffected(snapshotMapper.updateRunActiveRevision(runId, revision),
                "active plan revision 更新失败");
    }

    /**
     * 按 revision 反序列化并复核计划图。
     *
     * @param runId 运行 id
     * @param revision 计划 revision
     * @return 不可变计划图
     */
    @Transactional(readOnly = true)
    public CompiledStoryPlan loadCompiled(long runId, int revision) {
        DerivePlanSnapshotDO snapshot = requireRevision(runId, revision);
        CompiledStoryPlan plan = CompiledStoryPlan.fromJson(snapshot.getGraph());
        verifyGraph(snapshot, plan);
        return plan;
    }

    private void verifyGraph(DerivePlanSnapshotDO snapshot, CompiledStoryPlan plan) {
        if (plan.runId() != snapshot.getRunId()
                || plan.contractVersion() != snapshot.getContractVersion()
                || !Objects.equals(plan.contractHash(), snapshot.getContractHash())
                || !Objects.equals(plan.canonicalHash(), snapshot.getPlanHash())) {
            throw stale("快照元数据与 graph 不一致: revision=" + snapshot.getRevision());
        }
    }

    private void validateJson(String reportJson) {
        try {
            if (objectMapper.readTree(reportJson) == null) {
                throw new IllegalArgumentException("empty report");
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "准入报告不是合法 JSON");
        }
    }

    private static void requireAffected(int affected, String message) {
        if (affected != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    CONFLICT + "：" + message + "，影响行数=" + affected);
        }
    }

    private static BizException stale(String detail) {
        return new BizException(ErrorCode.STATE_CONFLICT, STALE + "：" + detail);
    }
}