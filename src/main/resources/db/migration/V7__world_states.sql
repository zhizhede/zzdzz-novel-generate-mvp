-- 世界状态账：每章一份结构化状态快照（时间/位置/随身物/新承诺/未解），随 digest 产出，注入后续上下文
CREATE TABLE world_states (
    id BIGSERIAL PRIMARY KEY,
    novel_id BIGINT NOT NULL REFERENCES novels(id),
    chapter_no INTEGER NOT NULL,
    state JSONB NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    delete_time TIMESTAMPTZ
);

-- 一章一份快照：重跑 digest 覆盖而非新增
CREATE UNIQUE INDEX idx_world_states_ch ON world_states (novel_id, chapter_no) WHERE is_deleted = FALSE;
