ALTER TABLE derive_story_contract ADD COLUMN IF NOT EXISTS created_user_id BIGINT;
ALTER TABLE derive_story_contract ADD COLUMN IF NOT EXISTS updated_user_id BIGINT;
