-- V13 · LLM 计费：价目表（元/百万 tokens，空闲/高峰两档，高峰时段按行配置）+ 台账补缓存命中 tokens 列。
-- 输入侧拆「缓存命中/未命中」两档计价；cached_tokens 来自 MiniMax usage.prompt_tokens_details，历史行从 response_json 回填。
ALTER TABLE llm_call_log ADD COLUMN cached_tokens INT;

UPDATE llm_call_log
SET cached_tokens = (response_json->'usage'->'prompt_tokens_details'->>'cached_tokens')::int
WHERE cached_tokens IS NULL AND response_json IS NOT NULL;

CREATE TABLE llm_model_price (
    id              BIGSERIAL PRIMARY KEY,
    model           VARCHAR(64) NOT NULL,
    currency        VARCHAR(8)  NOT NULL DEFAULT '元',
    idle_input_hit  NUMERIC(10,4) NOT NULL,
    idle_input_miss NUMERIC(10,4) NOT NULL,
    idle_output     NUMERIC(10,4) NOT NULL,
    peak_input_hit  NUMERIC(10,4) NOT NULL,
    peak_input_miss NUMERIC(10,4) NOT NULL,
    peak_output     NUMERIC(10,4) NOT NULL,
    peak_start_hour INT NOT NULL DEFAULT 14,
    peak_end_hour   INT NOT NULL DEFAULT 18,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    remark          VARCHAR(256),
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time     TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_llm_model_price_model ON llm_model_price(model) WHERE is_deleted = FALSE;

INSERT INTO llm_model_price (model, currency, idle_input_hit, idle_input_miss, idle_output,
                             peak_input_hit, peak_input_miss, peak_output, peak_start_hour, peak_end_hour, remark)
VALUES ('MiniMax-M3', '元', 0.02, 1.00, 4.00, 0.04, 2.00, 8.00, 14, 18, '高峰期每日 14:00-18:00，全价翻倍');
