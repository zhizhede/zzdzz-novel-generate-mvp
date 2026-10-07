-- V26 · 衍生配置与队列优先级（爆款资产化三批之 P2）。
-- derive_config：开书向导的衍生参数（掺水量/POV/节奏/目标章数/无人值守/优先级/来源样本），提示词与续跑闭环按书读取。
ALTER TABLE novels ADD COLUMN derive_config JSONB;

-- 队列认领序改为 priority DESC, id ASC（P3 无人续跑一起消费）。
ALTER TABLE generation_tasks ADD COLUMN priority INT NOT NULL DEFAULT 0;
