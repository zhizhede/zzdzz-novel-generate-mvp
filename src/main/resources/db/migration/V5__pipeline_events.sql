-- 管线事件流水：SSE 事件的落库副本（重跑轮次/门禁失败原因/阶段推进），供日志页按章回放
CREATE TABLE pipeline_events (
    id BIGSERIAL PRIMARY KEY,
    novel_id BIGINT REFERENCES novels(id),
    chapter_no INTEGER,
    stage VARCHAR(32) NOT NULL,
    phase VARCHAR(32),
    payload JSONB NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    delete_time TIMESTAMPTZ
);

CREATE INDEX idx_pipeline_events_ch ON pipeline_events (novel_id, chapter_no, id DESC);
