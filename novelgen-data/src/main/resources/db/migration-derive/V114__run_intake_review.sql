-- V114: run.intake_review JSONB（输入思路 AI 优化，v3.6 后置能力；开关 run 创建入参 intakeOptimize 默认关）
-- 开启时⑤先跑 derive_intake_review 预审：holes/changes/优化后 intent 落 param_intent，评审明细落本列；失败 fail-open 用原思路。
ALTER TABLE run ADD COLUMN intake_review JSONB;

COMMENT ON COLUMN run.intake_review IS '输入思路预审记录（v1：{v,score,holes,changes,source=USER|OPTIMIZED,error?}）；intakeOptimize 开启时⑤建 run 前 LLM 预审并以优化 intent 顶替 param_intent';
