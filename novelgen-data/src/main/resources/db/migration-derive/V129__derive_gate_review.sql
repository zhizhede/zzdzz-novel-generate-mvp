CREATE TABLE derive_gate_review (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    round INTEGER NOT NULL,
    verdict TEXT NOT NULL,
    action TEXT NOT NULL,
    issues JSONB NOT NULL DEFAULT '[]'::jsonb,
    criteria JSONB NOT NULL DEFAULT '{}'::jsonb,
    directive TEXT,
    repair_target TEXT,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT
);
CREATE INDEX idx_derive_gate_review_run ON derive_gate_review(run_id, round DESC);
