-- 卷弧线是模型生成的完整叙事描述，不是短标签。
ALTER TABLE derive_volume_plan
    ALTER COLUMN arc TYPE TEXT;

-- 卷合同属于 run/成品书的下游规划，删除父行时必须随之清理。
ALTER TABLE derive_volume_plan
    DROP CONSTRAINT IF EXISTS derive_volume_plan_run_id_fkey,
    ADD CONSTRAINT derive_volume_plan_run_id_fkey
        FOREIGN KEY (run_id) REFERENCES run(id) ON DELETE CASCADE;

ALTER TABLE derive_volume_plan
    DROP CONSTRAINT IF EXISTS derive_volume_plan_novel_id_fkey,
    ADD CONSTRAINT derive_volume_plan_novel_id_fkey
        FOREIGN KEY (novel_id) REFERENCES novels(id) ON DELETE CASCADE;
