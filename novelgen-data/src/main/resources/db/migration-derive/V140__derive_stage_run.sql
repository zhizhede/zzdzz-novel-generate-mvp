ALTER TABLE run ADD COLUMN IF NOT EXISTS draft_stage_state VARCHAR(16);

CREATE TABLE IF NOT EXISTS derive_stage_run (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    stage VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_path VARCHAR(256),
    message TEXT,
    output_revision INTEGER,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_stage_run_stage_attempt UNIQUE (run_id, stage, attempt),
    CONSTRAINT ck_derive_stage_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_derive_stage_run_stage
        CHECK (stage IN ('DRAFT', 'FINAL_AUDIT', 'COMPLETE')),
    CONSTRAINT ck_derive_stage_run_attempt CHECK (attempt >= 0)
);

CREATE INDEX IF NOT EXISTS idx_derive_stage_run_open
    ON derive_stage_run(run_id, stage, attempt DESC)
    WHERE status IN ('PENDING', 'RUNNING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_derive_stage_run_lease
    ON derive_stage_run(lease_until)
    WHERE status = 'RUNNING';

