-- V29 · AI 大纲草稿异步任务（开书向导批量友好）：点击秒回，后台并发生成，向导不阻塞。
CREATE TABLE outline_draft_tasks (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(256) NOT NULL,
    request     JSONB NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    result      TEXT,
    message     VARCHAR(256),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE INDEX idx_outline_draft_task ON outline_draft_tasks (status, id) WHERE is_deleted = FALSE;

INSERT INTO tuning (tkey, tvalue, description) VALUES
    ('outline_draft_parallel', '4', 'AI 大纲草稿的并发生成数（业务方批量开书时排队消费）')
ON CONFLICT DO NOTHING;
