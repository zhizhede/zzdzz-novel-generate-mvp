-- V28 · 样本类型/特征标签（爆款资产化·衍生自由度扩展）。
-- AI 深度解析时自动提取（类型/题材/元素特征，如「奇幻」「惊悚」「短篇」「不可名状」），
-- 衍生开书时可沿用或由用户自定（自定标签落 novels.derive_config.tags，零迁移）。
ALTER TABLE imported_samples ADD COLUMN tags JSONB NOT NULL DEFAULT '[]'::jsonb;
