package com.zzdzz.novelgen.derive.orchestration;

/** 每个正文阶段对应唯一 handler；orchestrator 不写业务 SQL 或 JSON。 */
public interface DeriveStageHandler {

    DeriveStage supports();

    StageOutcome execute(StageClaim claim);
}
