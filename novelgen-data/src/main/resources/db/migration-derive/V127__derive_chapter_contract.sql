CREATE TABLE derive_chapter_contract (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    chapter_no INTEGER NOT NULL,
    volume_no INTEGER NOT NULL,
    required_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_state_before JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_state_after JSONB NOT NULL DEFAULT '[]'::jsonb,
    reveal_level SMALLINT NOT NULL DEFAULT 0,
    min_beats INTEGER NOT NULL DEFAULT 1,
    max_beats INTEGER NOT NULL,
    outline_contract TEXT,
    pack_version INTEGER NOT NULL DEFAULT 0,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    UNIQUE(run_id, chapter_no)
);
CREATE INDEX idx_derive_chapter_contract_run ON derive_chapter_contract(run_id, chapter_no);
