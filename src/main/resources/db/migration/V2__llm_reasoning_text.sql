-- V2 · llm_call_log 增加思考过程列（推理模型 think 块单独存档，展示时与正文分区）
ALTER TABLE llm_call_log ADD COLUMN IF NOT EXISTS reasoning_text TEXT;
