-- V14 · 读者评审（反无聊闸门）：gate_reports 新增 gate_type 'reader_review'。
-- 读者评审=没耐心的网文读者视角，查钩子/戏剧张力/章间衔接/注水率，BLOCKER 带清单重写。
ALTER TABLE gate_reports DROP CONSTRAINT gate_reports_gate_type_check;
ALTER TABLE gate_reports ADD CONSTRAINT gate_reports_gate_type_check
    CHECK (gate_type IN ('mechanical', 'ai_review', 'reader_review'));
