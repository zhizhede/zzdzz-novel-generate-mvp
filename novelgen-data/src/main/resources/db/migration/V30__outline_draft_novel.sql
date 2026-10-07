-- V30 · 大纲生成挂书：向导点「AI 生成大纲」即静默落库（books 草稿态），任务与书关联可追溯。
ALTER TABLE outline_draft_tasks ADD COLUMN novel_id BIGINT REFERENCES novels(id);
