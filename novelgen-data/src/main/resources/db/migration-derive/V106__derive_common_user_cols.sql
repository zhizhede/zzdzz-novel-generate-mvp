-- V106 · 补齐 4 张表的共用字段 created_user_id / updated_user_id（v1.2 §1：所有表继承共用字段）
-- V100 起草时 beat_instance / qc_report / calibration / work_fingerprint 漏抄了这两列——
-- DeriveBaseDO 映射了它们，MP BaseMapper 生成的 SELECT 含这两列 → "column does not exist"（实弹踩到）。
-- 教训：共用字段清单要按 §1 逐表核（information_schema 对账），别信手写。
ALTER TABLE beat_instance    ADD COLUMN IF NOT EXISTS created_user_id BIGINT REFERENCES users (id);
ALTER TABLE beat_instance    ADD COLUMN IF NOT EXISTS updated_user_id BIGINT REFERENCES users (id);
ALTER TABLE qc_report        ADD COLUMN IF NOT EXISTS created_user_id BIGINT REFERENCES users (id);
ALTER TABLE qc_report        ADD COLUMN IF NOT EXISTS updated_user_id BIGINT REFERENCES users (id);
ALTER TABLE calibration      ADD COLUMN IF NOT EXISTS created_user_id BIGINT REFERENCES users (id);
ALTER TABLE calibration      ADD COLUMN IF NOT EXISTS updated_user_id BIGINT REFERENCES users (id);
ALTER TABLE work_fingerprint ADD COLUMN IF NOT EXISTS created_user_id BIGINT REFERENCES users (id);
ALTER TABLE work_fingerprint ADD COLUMN IF NOT EXISTS updated_user_id BIGINT REFERENCES users (id);

COMMENT ON COLUMN beat_instance.created_user_id IS '创建用户 → users.id（v106 补齐，原 V100 漏抄）';
COMMENT ON COLUMN beat_instance.updated_user_id IS '更新用户 → users.id（v106 补齐）';
COMMENT ON COLUMN qc_report.created_user_id IS '创建用户 → users.id（v106 补齐）';
COMMENT ON COLUMN qc_report.updated_user_id IS '更新用户 → users.id（v106 补齐）';
COMMENT ON COLUMN calibration.created_user_id IS '创建用户 → users.id（v106 补齐）';
COMMENT ON COLUMN calibration.updated_user_id IS '更新用户 → users.id（v106 补齐）';
COMMENT ON COLUMN work_fingerprint.created_user_id IS '创建用户 → users.id（v106 补齐）';
COMMENT ON COLUMN work_fingerprint.updated_user_id IS '更新用户 → users.id（v106 补齐）';
