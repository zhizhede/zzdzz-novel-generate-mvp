-- V27 · 书级无人续跑闭环（爆款资产化三批之 P3）。
-- novels 链状态：null=未启用；RUNNING=链在跑；PAUSED=链断暂停（失败/用户停止，可恢复）；REACHED=目标达成收链。
ALTER TABLE novels ADD COLUMN auto_state VARCHAR(16);
ALTER TABLE novels ADD COLUMN auto_message VARCHAR(256);
ALTER TABLE novels ADD COLUMN auto_volumes INT NOT NULL DEFAULT 0;

-- 队列认领序带优先级（generation_tasks.priority 由 V26 落列，此处只补调参与索引）
CREATE INDEX idx_generation_tasks_claim ON generation_tasks (status, priority DESC, id) WHERE is_deleted = FALSE;

INSERT INTO tuning (tkey, tvalue, description) VALUES
    ('batch_max_chapters', '10', '单次生成任务的章数上限（含无人续跑自动续批；契约施工 M4 欠账收口）'),
    ('auto_continue_max_volumes', '50', '无人续跑的规划卷数保险丝（防失控，达到即暂停链）')
ON CONFLICT DO NOTHING;
