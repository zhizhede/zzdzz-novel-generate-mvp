-- P1 FactLedger: canonical fact provenance for derive planning.
CREATE TABLE IF NOT EXISTS derive_fact (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    novel_id BIGINT NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    fact_key VARCHAR(64) NOT NULL,
    canonical_text TEXT NOT NULL,
    writers JSONB NOT NULL DEFAULT '[]'::jsonb,
    readers JSONB NOT NULL DEFAULT '[]'::jsonb,
    planted_chapter INTEGER,
    revealed_chapter INTEGER,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_fact_key UNIQUE (run_id, fact_key),
    CONSTRAINT ck_derive_fact_status CHECK (status IN ('OPEN','PLANTED','KNOWN','REVEALED','CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_derive_fact_run ON derive_fact(run_id, status);
CREATE INDEX IF NOT EXISTS idx_derive_fact_novel ON derive_fact(novel_id, status);