package com.zzdzz.novelgen.derive.orchestration;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DeriveStageRunMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import java.net.InetAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 正文阶段状态机：固定顺序、单 owner、租约恢复、失败升 attempt、取消后不再推进。 */
@Service
public class StageRunStateService {

    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final String CONFLICT = "STAGE_CLAIM_CONFLICT";
    private static final String EXPIRED = "STAGE_LEASE_EXPIRED";

    private final DeriveStageRunMapper mapper;

    /** 创建阶段状态服务。 */
    public StageRunStateService(DeriveStageRunMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /** 为 active ADMITTED run 幂等播种 DRAFT→FINAL_AUDIT→COMPLETE。 */
    public synchronized void seedActiveStages(long runId) {
        for (DeriveStage stage : DeriveStage.values()) {
            if (mapper.findLatest(runId, stage.name()) == null) {
                DeriveStageRunDO row = new DeriveStageRunDO();
                row.setRunId(runId);
                row.setStage(stage.name());
                row.setStatus("PENDING");
                row.setAttempt(0);
                try {
                    mapper.insertFull(row);
                } catch (org.springframework.dao.DuplicateKeyException ignored) {
                    // 并发 seed：另一 owner 已写同一 attempt，后续统一按数据库事实 claim。
                }
            }
        }
    }

    /** 按固定顺序 claim 下一可执行阶段；任何 CANCELLED 都会终止链条。 */
    public StageClaim claimNext(long runId, String owner) {
        seedActiveStages(runId);
        Map<DeriveStage, DeriveStageRunDO> latest = latestByStage(runId);
        if (latest.values().stream().anyMatch(row -> "CANCELLED".equals(row.getStatus()))) {
            return null;
        }
        for (DeriveStage stage : DeriveStage.values()) {
            DeriveStageRunDO row = latest.get(stage);
            if (row == null || "PENDING".equals(row.getStatus())) {
                return claim(runId, stage, row == null ? 0 : row.getAttempt(), owner);
            }
            if ("FAILED".equals(row.getStatus())) {
                int next = row.getAttempt() + 1;
                mapper.insertPendingIfAbsent(runId, stage.name(), next);
                return claim(runId, stage, next, owner);
            }
            if ("RUNNING".equals(row.getStatus())) {
                if (row.getLeaseUntil() == null || row.getLeaseUntil().isAfter(OffsetDateTime.now())) {
                    throw new BizException(ErrorCode.STATE_CONFLICT,
                            CONFLICT + "：stage=" + stage + " 已由 " + row.getLeaseOwner() + " 持有");
                }
                return claim(runId, stage, row.getAttempt(), owner);
            }
        }
        return null;
    }

    /** RUNNING 且 owner 匹配时写 DONE 与 output revision。 */
    public void done(StageClaim claim, Integer outputRevision) {
        done(claim, outputRevision, null);
    }

    /** RUNNING 且 owner 匹配时写 DONE、完成摘要与 output revision。 */
    public void done(StageClaim claim, Integer outputRevision, String completionMessage) {
        finish(claim, "DONE", null, null, completionMessage, outputRevision);
    }

    /** RUNNING 且 owner 匳配时持久化稳定错误 code/path/message。 */
    public void failed(StageClaim claim, String code, String path, String message) {
        finish(claim, "FAILED", code, path, message, null);
    }

    /** 取消当前 run 的全部未终态阶段；取消是终态，后续 claim 返回 null。 */
    public void cancelOpen(long runId) {
        mapper.cancelOpen(runId);
    }

    /** 直接读取数据库 stage rows。 */
    public StageStatusSnapshot status(long runId) {
        return new StageStatusSnapshot(mapper.findAll(runId));
    }

    private void finish(StageClaim claim, String state, String code, String path,
                        String message, Integer outputRevision) {
        int affected = mapper.finish(claim.stageRunId(), claim.leaseOwner(), state,
                code, path, message, outputRevision);
        if (affected != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    EXPIRED + "：stage=" + claim.stage() + " 的租约已失效，结果不得提交");
        }
    }

    private StageClaim claim(long runId, DeriveStage stage, int attempt, String owner) {
        OffsetDateTime until = OffsetDateTime.now().plus(LEASE);
        int affected = mapper.claim(runId, stage.name(), attempt, owner, until);
        if (affected != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    CONFLICT + "：stage=" + stage + " 抢占失败，请重试");
        }
        DeriveStageRunDO row = mapper.findLatest(runId, stage.name());
        if (row == null || !"RUNNING".equals(row.getStatus()) || row.getAttempt() != attempt) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    EXPIRED + "：stage=" + stage + " 抢占后状态不可读");
        }
        return new StageClaim(row.getId(), stage, attempt, owner, until);
    }

    private Map<DeriveStage, DeriveStageRunDO> latestByStage(long runId) {
        Map<DeriveStage, DeriveStageRunDO> latest = new EnumMap<>(DeriveStage.class);
        Map<DeriveStage, Integer> attempts = new HashMap<>();
        for (DeriveStageRunDO row : mapper.findAll(runId)) {
            DeriveStage stage = DeriveStage.valueOf(row.getStage());
            int attempt = row.getAttempt();
            Integer old = attempts.get(stage);
            if (old == null || attempt > old) {
                latest.put(stage, row);
                attempts.put(stage, attempt);
            }
        }
        return latest;
    }
}



