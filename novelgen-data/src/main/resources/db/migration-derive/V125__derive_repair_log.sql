-- P1 repair audit: deterministic and model-repair actions for derive runs.
CREATE TABLE IF NOT EXISTS derive_repair_log (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    stage VARCHAR(32) NOT NULL,
    repair_type VARCHAR(64) NOT NULL,
    before_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason TEXT NOT NULL DEFAULT '',
    target VARCHAR(64),
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT
);

CREATE INDEX IF NOT EXISTS idx_derive_repair_run ON derive_repair_log(run_id, created_time DESC);
CREATE INDEX IF NOT EXISTS idx_derive_repair_type ON derive_repair_log(run_id, repair_type);