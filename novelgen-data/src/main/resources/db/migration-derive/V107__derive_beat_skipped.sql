-- V107 · beat_instance.status 补 SKIPPED 枚举值
-- v3.2 §⑥b 规则 2："用不上的拍标 status 跳过并报告"——V100 建表时 CHECK 漏了该值（文档为准）。
-- 教训三证：枚举 CHECK 要按工作流文档的全动作清单核，别只抄状态机主干。
ALTER TABLE beat_instance DROP CONSTRAINT ck_beat_instance_status;
ALTER TABLE beat_instance ADD CONSTRAINT ck_beat_instance_status
    CHECK (status IN ('PLANNED', 'WRITTEN', 'VERIFIED', 'SKIPPED'));
COMMENT ON COLUMN beat_instance.status IS '状态：PLANNED→WRITTEN→VERIFIED；SKIPPED=⑥b判定用不上（v107补）';
