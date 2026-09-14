-- 章节生成时的评审标准快照（reader 五参数），回看该章当时按什么口径过的审；历史章节为 NULL
ALTER TABLE chapters ADD COLUMN IF NOT EXISTS review_config jsonb;
