-- V17 · 卷级复盘：每卷保留最新一份复盘报告（机械对账 + LLM 漂移分析）。
CREATE TABLE volume_reviews (
    id          BIGSERIAL PRIMARY KEY,
    novel_id    BIGINT NOT NULL REFERENCES novels(id),
    vol_no      INT NOT NULL,
    report      JSONB NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_volume_reviews ON volume_reviews(novel_id, vol_no) WHERE is_deleted = FALSE;
