CREATE TABLE derive_character_relationship (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    from_character_key VARCHAR(16) NOT NULL,
    to_character_key VARCHAR(16) NOT NULL,
    relationship_type VARCHAR(32) NOT NULL,
    public_relation TEXT,
    hidden_relation TEXT,
    initial_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    target_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    debt TEXT,
    trust INTEGER NOT NULL DEFAULT 0,
    intimacy INTEGER NOT NULL DEFAULT 0,
    conflict INTEGER NOT NULL DEFAULT 0,
    history TEXT,
    current_turn TEXT,
    payoff TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_character_relationship UNIQUE (run_id, from_character_key, to_character_key),
    CONSTRAINT ck_derive_character_relationship_values CHECK (
        trust BETWEEN 0 AND 100 AND intimacy BETWEEN 0 AND 100 AND conflict BETWEEN 0 AND 100
    )
);
CREATE INDEX idx_derive_character_relationship_run ON derive_character_relationship(run_id, relationship_type);
