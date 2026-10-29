package com.zzdzz.novelgen.derive.orchestration;

import java.time.OffsetDateTime;
import java.util.Objects;

/** 已原子抢占的阶段租约；完成、失败和取消都必须携带该身份。 */
public record StageClaim(
        long stageRunId,
        DeriveStage stage,
        int attempt,
        String leaseOwner,
        OffsetDateTime leaseUntil) {

    public StageClaim {
        if (stageRunId <= 0 || attempt < 0) {
            throw new IllegalArgumentException("stageRunId must be positive and attempt nonnegative");
        }
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
    }
}
