-- 导入小说样本台账：用户导入的每一本小说 = 一行（切块落 preset_corpus，分析结论全文落 analysis）。
-- 用户定调：输入的小说与系统生成的一切分析都要落库可复用，素材库有专门分类展示。
CREATE TABLE imported_samples (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(256) NOT NULL,
    genre       VARCHAR(64)  NOT NULL,
    chunks      INT          NOT NULL,
    total_chars BIGINT       NOT NULL,
    source      VARCHAR(16)  NOT NULL DEFAULT 'wizard',
    analysis    JSONB,
    preset_id   BIGINT       REFERENCES style_packs (id),
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delete_time TIMESTAMPTZ
);

CREATE INDEX idx_imported_samples_genre ON imported_samples (genre) WHERE is_deleted = FALSE;

-- 存量品类回填（分析快照早于本表未存，统计从语料聚合；source=backfill 标注）
INSERT INTO imported_samples (title, genre, chunks, total_chars, source)
SELECT c.genre, c.genre, count(*), sum(c.word_count), 'backfill'
FROM preset_corpus c
WHERE c.is_deleted = FALSE
GROUP BY c.genre;
