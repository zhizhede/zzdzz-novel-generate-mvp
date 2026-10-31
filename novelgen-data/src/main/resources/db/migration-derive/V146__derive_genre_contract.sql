CREATE TABLE derive_genre_contract (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  genre VARCHAR(32) NOT NULL,
  contract_version INTEGER NOT NULL DEFAULT 1,
  scope JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload JSONB NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'FROZEN',
  created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_user_id BIGINT,
  updated_user_id BIGINT
);
CREATE UNIQUE INDEX uq_derive_genre_contract_run
  ON derive_genre_contract(run_id);
