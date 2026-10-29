CREATE TABLE derive_genre_capability (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    capability_key VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    required_contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    forbidden_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    event_roles JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_rules JSONB NOT NULL DEFAULT '{}'::jsonb,
    priority INTEGER NOT NULL DEFAULT 0,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_genre_capability_run_key UNIQUE (run_id, capability_key)
);
CREATE INDEX idx_derive_genre_capability_run ON derive_genre_capability(run_id, priority);
