ALTER TABLE run ADD COLUMN IF NOT EXISTS active_plan_revision INTEGER;
ALTER TABLE chapters ADD COLUMN IF NOT EXISTS plan_revision INTEGER;
ALTER TABLE beat_instance ADD COLUMN IF NOT EXISTS plan_revision INTEGER;

CREATE TABLE IF NOT EXISTS derive_plan_snapshot (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    contract_version INTEGER NOT NULL,
    contract_hash VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    plan_hash VARCHAR(128) NOT NULL,
    graph JSONB NOT NULL,
    admission_report JSONB,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_plan_snapshot_run_revision UNIQUE (run_id, revision),
    CONSTRAINT ck_derive_plan_snapshot_status
        CHECK (status IN ('DRAFT', 'ADMITTED', 'SUPERSEDED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_derive_plan_snapshot_admitted
    ON derive_plan_snapshot(run_id)
    WHERE status = 'ADMITTED';

CREATE INDEX IF NOT EXISTS idx_derive_plan_snapshot_run_revision
    ON derive_plan_snapshot(run_id, revision DESC);

CREATE INDEX IF NOT EXISTS idx_run_active_plan_revision
    ON run(active_plan_revision)
    WHERE active_plan_revision IS NOT NULL;