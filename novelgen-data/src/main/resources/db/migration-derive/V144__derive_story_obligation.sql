CREATE TABLE derive_story_obligation (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  obligation_key TEXT NOT NULL,
  chapter_no INTEGER NOT NULL,
  kind TEXT NOT NULL,
  target_key TEXT,
  required BOOLEAN NOT NULL DEFAULT FALSE,
  forbidden BOOLEAN NOT NULL DEFAULT FALSE,
  severity TEXT NOT NULL DEFAULT 'HARD',
  source_contract TEXT,
  status TEXT NOT NULL DEFAULT 'PLANNED',
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_user_id BIGINT,
  updated_user_id BIGINT,
  UNIQUE(run_id, obligation_key)
);
CREATE INDEX idx_derive_story_obligation_run_chapter
  ON derive_story_obligation(run_id, chapter_no);
