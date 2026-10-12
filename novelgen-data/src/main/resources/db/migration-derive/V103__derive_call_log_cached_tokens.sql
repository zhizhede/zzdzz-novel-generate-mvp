-- V103 · llm_call_log 补 cached_tokens 列（照搬 V13 原句）
-- 教训：照搬旧表要按**最终结构**（全部后续 ALTER 汇总），不能只抄建表初版——
-- LlmCallLogMapper.insert 写 cached_tokens（V13 加的列），只抄 V1 会在第一次 LLM 调用时报列不存在。
ALTER TABLE llm_call_log ADD COLUMN IF NOT EXISTS cached_tokens INT;
