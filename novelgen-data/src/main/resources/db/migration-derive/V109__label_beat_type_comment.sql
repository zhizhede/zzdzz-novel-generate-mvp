-- V109: label_index.beat_type 列注释补枚举值语义（仅注释，不动结构）
-- 口径来源：v3.2 §4b 打标规则（该段与系统 LABEL_PROMPT 逐字对应，未受 ⑦/harness 漂移影响）
COMMENT ON COLUMN label_index.beat_type IS
'拍型（④b 单选主导功能；WORLD 行 NULL，单值不权重化）：铺垫=为后文铺路造势；情绪=情绪/心理位移；事件=推动情节的行动；反转=信息/局势翻转；抉择=人物关键抉择；收束=收束结局';
