CREATE TABLE derive_world_institution (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    institution_key VARCHAR(64) NOT NULL,
    institution_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    law_or_doctrine TEXT NOT NULL,
    enforcement TEXT NOT NULL,
    benefit TEXT,
    cost TEXT,
    affected_characters JSONB NOT NULL DEFAULT '[]'::jsonb,
    contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_world_institution_run_key UNIQUE (run_id, institution_key)
);
CREATE INDEX idx_derive_world_institution_run ON derive_world_institution(run_id, institution_type);
