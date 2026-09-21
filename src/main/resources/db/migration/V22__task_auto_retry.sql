-- 量产阶段二：失败任务自动重试——重试计数（task_auto_retry_times 调参控制上限，0 关闭）
ALTER TABLE generation_tasks ADD COLUMN retry_count int NOT NULL DEFAULT 0;
