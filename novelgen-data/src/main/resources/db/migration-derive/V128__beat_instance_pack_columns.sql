ALTER TABLE beat_instance
    ADD COLUMN beat_key TEXT,
    ADD COLUMN chapter_seq INTEGER,
    ADD COLUMN pack_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN novelty_key TEXT;
CREATE UNIQUE INDEX uq_beat_instance_run_beat_key
    ON beat_instance(run_id, beat_key) WHERE beat_key IS NOT NULL;
CREATE INDEX idx_beat_instance_chapter_seq
    ON beat_instance(run_id, chapter_no, chapter_seq);
