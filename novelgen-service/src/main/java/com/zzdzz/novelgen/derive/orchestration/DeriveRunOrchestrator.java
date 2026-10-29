package com.zzdzz.novelgen.derive.orchestration;

import java.net.InetAddress;
import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import com.zzdzz.novelgen.model.vo.derive.AutoVO;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 正文闭环编排器：一次 advance 只执行一个 stage，状态与错误全部落 derive_stage_run。 */
@Service
public class DeriveRunOrchestrator {

    private final StageRunStateService stateService;
    private final DeriveStageHandlerRegistry registry;

    /** 创建正文阶段编排器。 */
    public DeriveRunOrchestrator(StageRunStateService stateService,
                                 DeriveStageHandlerRegistry registry) {
        this.stateService = Objects.requireNonNull(stateService);
        this.registry = Objects.requireNonNull(registry);
    }

    /** 抢占并执行下一个未完成 stage。 */
    public AutoVO advance(long runId) {
        String owner = owner();
        StageClaim claim = stateService.claimNext(runId, owner);
        if (claim == null) {
            return status(runId);
        }
        StageOutcome outcome;
        try {
            outcome = registry.require(claim.stage()).execute(claim);
        } catch (Exception e) {
            outcome = StageOutcome.failed(
                    e.getMessage() != null && e.getMessage().contains("PLAN_REVISION_STALE")
                            ? "DRAFT_PLAN_REVISION_STALE"
                            : StageOutcome.DRAFT_FAILED,
                    "stage[" + claim.stage() + "]",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (outcome.done()) {
            String completion = outcome.completionMessage() == null
                    ? outcome.errorMessage() : outcome.completionMessage();
            stateService.done(claim, outcome.outputRevision(), completion);
        } else {
            stateService.failed(claim, outcome.errorCode(), outcome.errorPath(), outcome.errorMessage());
        }
        return status(runId);
    }

    /** 取消未终态阶段；已执行阶段的持久产物保留，恢复时跳过完成 stage。 */
    public AutoVO cancel(long runId) {
        stateService.cancelOpen(runId);
        return status(runId);
    }

    /** 状态读模型：只从 stage ledger 投影。 */
    /** 状态读模型：只从 stage ledger 投影，并显示下一个未完成阶段。 */
    public AutoVO status(long runId) {
        List<DeriveStageRunDO> rows = stateService.status(runId).rows();
        boolean cancelled = rows.stream().anyMatch(row -> "CANCELLED".equals(row.getStatus()));
        boolean done = rows.stream().anyMatch(row ->
                DeriveStage.COMPLETE.name().equals(row.getStage())
                        && "DONE".equals(row.getStatus()));
        int steps = (int) rows.stream().filter(row -> "DONE".equals(row.getStatus())).count();
        DeriveStageRunDO problem = rows.stream()
                .filter(row -> "FAILED".equals(row.getStatus()))
                .findFirst().orElse(null);
        DeriveStageRunDO running = rows.stream()
                .filter(row -> "RUNNING".equals(row.getStatus()))
                .findFirst().orElse(null);
        DeriveStage next = java.util.Arrays.stream(DeriveStage.values())
                .filter(candidate -> rows.stream().noneMatch(row ->
                        candidate.name().equals(row.getStage())
                                && "DONE".equals(row.getStatus())))
                .findFirst().orElse(null);
        String stage;
        if (running != null) {
            stage = running.getStage() + ":RUNNING";
        } else if (problem != null) {
            stage = problem.getStage() + ":FAILED";
        } else if (done) {
            stage = "COMPLETE:DONE";
        } else {
            stage = (next == null ? DeriveStage.DRAFT : next).name() + ":PENDING";
        }
        String lastError = problem == null ? ""
                : problem.getErrorCode() + ":" + problem.getErrorPath();
        return new AutoVO(runId, false, cancelled, done, stage, steps, lastError, 0, false);
    }

    private static String owner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID();
    }
}

