-- V105 · 照搬 V31 prompt_templates 列加宽（原句拷贝）
-- 照搬教训二证：V18 初版 phase=varchar(16)，zzdzz 已在 V31 加宽到 32（node 到 64）——
-- PromptCatalog 的段键 fingerprint_targets(19) 等超 16 字符，启动同步直接撞 varchar(16)。
-- 照搬旧表必须按最终结构：建表 + 后续全部 ALTER 一并搬。
ALTER TABLE prompt_templates ALTER COLUMN phase TYPE varchar(32);
ALTER TABLE prompt_templates ALTER COLUMN node TYPE varchar(64);
