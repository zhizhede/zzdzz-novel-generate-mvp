CREATE TABLE derive_chapter_schedule (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    contract_version INTEGER NOT NULL,
    chapter_no INTEGER NOT NULL,
    volume_no INTEGER NOT NULL,
    reveal BOOLEAN NOT NULL DEFAULT FALSE,
    must_plant JSONB NOT NULL DEFAULT '[]'::jsonb,
    must_payoff JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_chapter_schedule_run_chapter UNIQUE (run_id, chapter_no)
);
CREATE INDEX idx_derive_chapter_schedule_run ON derive_chapter_schedule(run_id, chapter_no);

ALTER TABLE derive_story_thread ADD COLUMN plant_chapter INTEGER;
ALTER TABLE derive_story_thread ADD COLUMN payoff_chapter INTEGER;
