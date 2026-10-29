package com.zzdzz.novelgen.derive.orchestration;

/** 正文闭环的固定阶段顺序；阶段账是重启恢复事实源。 */
public enum DeriveStage {
    DRAFT,
    FINAL_AUDIT,
    COMPLETE
}
