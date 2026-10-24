-- V108：防复刻开关（2026-10-13 用户定调「防复刻在可设置的节点里都设置个字段控制开关，默认关闭」）
-- run 级字段，贯穿 ⑤同构弧阻断 / ⑥b主角名禁名 / ⑦独立会话与前情注入 / ⑧黑名单·反域词·主角名查重。
-- 默认 FALSE = 质量优先（harness 一致性模式）；TRUE = 九轮实测防复刻原口径。
ALTER TABLE run ADD COLUMN IF NOT EXISTS anti_repro BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN run.anti_repro IS '防复刻开关（默认关）：TRUE=九轮原口径（⑤同构弧阻断/⑥b禁名/⑦独立会话/⑧黑名单·反域词·主角名查重硬判）；FALSE=一致性优先（前情+cast 注入，词表类检查降报告，语义判定交⑧LLM）';
