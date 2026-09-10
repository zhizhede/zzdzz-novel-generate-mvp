-- 门禁配置落库：黑名单 + 章长容差随风格包走（此前硬编码在 GateService 常量里）
-- 场景级/章级检查哪些指标仍由代码逻辑决定（结构配置，暂不开放）
ALTER TABLE style_packs ADD COLUMN IF NOT EXISTS gate_config JSONB;

-- 既有风格包填入与原 GateService 常量等价的默认配置
UPDATE style_packs SET gate_config = '{
  "banned_phrases": [
    "心中暗想", "不由得", "仿佛在诉说", "在空气中弥漫", "空气仿佛凝固",
    "嘴角勾起一抹", "眼底闪过一丝", "一丝不易察觉"
  ],
  "chapter_length_tolerance": 0.15,
  "no_straight_quote": true
}'::jsonb
WHERE gate_config IS NULL;
