-- V31 · 提示词注册表扩展：运行时拼装段的 sectionKey 需要更长 phase（如 plan_budget_without=18 字符）。
ALTER TABLE prompt_templates ALTER COLUMN phase TYPE varchar(32);
ALTER TABLE prompt_templates ALTER COLUMN node TYPE varchar(64);
