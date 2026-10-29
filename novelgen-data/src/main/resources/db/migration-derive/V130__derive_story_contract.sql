CREATE TABLE derive_story_contract (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    truth_kernel JSONB NOT NULL DEFAULT '{}'::jsonb,
    character_slots JSONB NOT NULL DEFAULT '{}'::jsonb,
    event_kernel JSONB NOT NULL DEFAULT '{}'::jsonb,
    clue_registry JSONB NOT NULL DEFAULT '{}'::jsonb,
    knowledge_ledger JSONB NOT NULL DEFAULT '{}'::jsonb,
    constraint_kernel JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_hash VARCHAR(64) NOT NULL,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_derive_story_contract_run_version UNIQUE (run_id, version)
);
CREATE INDEX idx_derive_story_contract_run ON derive_story_contract(run_id, version DESC);
