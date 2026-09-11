-- 生成队列：异步任务（提交即返回，后台单线程逐个执行，进度逐章回写，重启后未完成任务重新排队）
CREATE TABLE generation_tasks (
    id BIGSERIAL PRIMARY KEY,
    novel_id BIGINT NOT NULL REFERENCES novels(id),
    from_chapter INTEGER NOT NULL,
    to_chapter INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'STOPPED', 'CANCELED')),
    done_chapters INTEGER NOT NULL DEFAULT 0,
    current_chapter INTEGER,
    last_message VARCHAR(256),
    submitted_by BIGINT,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    delete_time TIMESTAMPTZ
);

CREATE INDEX idx_generation_tasks_status ON generation_tasks (status, id);
