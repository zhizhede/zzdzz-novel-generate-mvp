-- 卷纲规划模式：auto=AI 规划+审校通过后直接落库（默认，全自动路线）；manual=产出草稿待人工编辑采纳
ALTER TABLE novels ADD COLUMN plan_mode VARCHAR(16) NOT NULL DEFAULT 'auto'
    CHECK (plan_mode IN ('auto', 'manual'));
