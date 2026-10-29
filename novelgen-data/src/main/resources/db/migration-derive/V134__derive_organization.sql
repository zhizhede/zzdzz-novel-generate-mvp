CREATE TABLE derive_organization (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    org_key VARCHAR(64) NOT NULL,
    org_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    public_goal TEXT NOT NULL,
    private_goal TEXT,
    resource TEXT,
    rule TEXT NOT NULL,
    weakness TEXT,
    pressure TEXT,
    members JSONB NOT NULL DEFAULT '[]'::jsonb,
    contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_organization_run_key UNIQUE (run_id, org_key)
);
CREATE INDEX idx_derive_organization_run ON derive_organization(run_id, org_type);
