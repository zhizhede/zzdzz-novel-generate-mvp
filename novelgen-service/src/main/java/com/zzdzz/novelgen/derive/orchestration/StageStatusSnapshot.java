package com.zzdzz.novelgen.derive.orchestration;

import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import java.util.List;
import java.util.Objects;

/** 从 derive_stage_run 直接投影的运行状态；禁止从内存或 run.step_status 猜。 */
public record StageStatusSnapshot(List<DeriveStageRunDO> rows) {

    public StageStatusSnapshot {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
