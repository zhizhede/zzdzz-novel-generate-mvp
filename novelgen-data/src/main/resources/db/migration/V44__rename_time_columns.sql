-- V44: 全库时间列统一 ed 形（2026-10-16 用户定调）
-- create_time → created_time / update_time → updated_time
-- 索引定义随 RENAME COLUMN 自动更新；无外键/约束/视图依赖这两列。
-- 幂等：仅当旧列在、且新列不在时才改。

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.table_name
        FROM information_schema.columns c
        WHERE c.table_schema = 'public'
          AND c.column_name = 'create_time'
          AND NOT EXISTS (
              SELECT 1 FROM information_schema.columns c2
              WHERE c2.table_schema = 'public'
                AND c2.table_name = c.table_name
                AND c2.column_name = 'created_time'
          )
        ORDER BY c.table_name
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME COLUMN create_time TO created_time', r.table_name);
    END LOOP;

    FOR r IN
        SELECT c.table_name
        FROM information_schema.columns c
        WHERE c.table_schema = 'public'
          AND c.column_name = 'update_time'
          AND NOT EXISTS (
              SELECT 1 FROM information_schema.columns c2
              WHERE c2.table_schema = 'public'
                AND c2.table_name = c.table_name
                AND c2.column_name = 'updated_time'
          )
        ORDER BY c.table_name
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME COLUMN update_time TO updated_time', r.table_name);
    END LOOP;
END $$;

COMMENT ON COLUMN users.created_time IS '创建时间';
COMMENT ON COLUMN users.updated_time IS '更新时间';
COMMENT ON COLUMN llm_providers.created_time IS '创建时间';
COMMENT ON COLUMN llm_providers.updated_time IS '更新时间';
COMMENT ON COLUMN llm_call_log.created_time IS '创建时间';
COMMENT ON COLUMN llm_call_log.updated_time IS '更新时间';
