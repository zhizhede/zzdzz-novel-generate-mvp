CREATE TABLE derive_story_obligation_evidence (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  obligation_key TEXT NOT NULL,
  chapter_no INTEGER NOT NULL,
  evidence_kind TEXT NOT NULL,
  event_key TEXT,
  beat_seq INTEGER,
  entity_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
  thread_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
  fact TEXT,
  source TEXT NOT NULL,
  confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_user_id BIGINT,
  updated_user_id BIGINT
);
CREATE UNIQUE INDEX uq_derive_story_obligation_evidence
  ON derive_story_obligation_evidence(
    run_id, obligation_key, evidence_kind, chapter_no,
    COALESCE(event_key, ''), COALESCE(beat_seq, -1)
  );
CREATE INDEX idx_derive_story_obligation_evidence_run_chapter
  ON derive_story_obligation_evidence(run_id, chapter_no);
