-- V25 · 导入小说深度解析：样本资产三表（平台级，全员可复用）。
-- 设计口径：章行 sample_plot_nodes(level=chapter) 即断点 checkpoint（UNIQUE 幂等，续跑跳过已存在行）；
-- sample_cards 承载角色/物品/地点/组织/现象/世界观结构化卡 + 关系；任务行每样本一行（活跃唯一）。

-- 解析任务（一本样本一行活跃任务；断点续跑按章行幂等跳过）
CREATE TABLE sample_parse_tasks (
    id          BIGSERIAL PRIMARY KEY,
    sample_id   BIGINT NOT NULL REFERENCES imported_samples(id),
    mode        VARCHAR(8)  NOT NULL CHECK (mode IN ('FAST', 'FULL')),
    status      VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'INTERRUPTED')),
    total_units INT NOT NULL DEFAULT 0,
    done_units  INT NOT NULL DEFAULT 0,
    stage       VARCHAR(32) NOT NULL DEFAULT '',
    message     VARCHAR(256),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_sample_parse_task_alive ON sample_parse_tasks(sample_id) WHERE is_deleted = FALSE;

-- 剧情结构树：书 → 卷 → 章（章行 = 逐章解析结果与断点）
CREATE TABLE sample_plot_nodes (
    id         BIGSERIAL PRIMARY KEY,
    sample_id  BIGINT NOT NULL REFERENCES imported_samples(id),
    level      VARCHAR(8) NOT NULL CHECK (level IN ('book', 'volume', 'chapter')),
    seq        INT NOT NULL,
    parent_seq INT NOT NULL DEFAULT 0,
    title      VARCHAR(256),
    summary    TEXT,
    beats      JSONB,
    meta       JSONB,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_sample_plot_node ON sample_plot_nodes(sample_id, level, seq) WHERE is_deleted = FALSE;
CREATE INDEX idx_sample_plot_chapter ON sample_plot_nodes(sample_id, level) WHERE is_deleted = FALSE;

-- 结构化资产卡：kind 对齐 material_cards 全类 + world（每样本一行的世界观文档）
CREATE TABLE sample_cards (
    id         BIGSERIAL PRIMARY KEY,
    sample_id  BIGINT NOT NULL REFERENCES imported_samples(id),
    kind       VARCHAR(16) NOT NULL CHECK (kind IN ('character','item','location','phenomenon','landmark','disaster','org','misc','world')),
    name       VARCHAR(128) NOT NULL,
    aliases    JSONB NOT NULL DEFAULT '[]'::jsonb,
    summary    TEXT,
    content_md TEXT,
    relations  JSONB NOT NULL DEFAULT '[]'::jsonb,
    importance INT NOT NULL DEFAULT 1 CHECK (importance BETWEEN 1 AND 3),
    first_seq  INT,
    mentions   INT NOT NULL DEFAULT 1,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_sample_cards ON sample_cards(sample_id, kind, name) WHERE is_deleted = FALSE;
CREATE INDEX idx_sample_cards_sample ON sample_cards(sample_id) WHERE is_deleted = FALSE;

-- 解析管线调参（沿 V20/V21 惯例：INSERT ON CONFLICT，代码默认在 TuningDefaults）
INSERT INTO tuning (tkey, tvalue, description) VALUES
    ('sample_parse_parallel', '4', '样本深度解析的逐章 LLM 并发度'),
    ('sample_fast_chapters', '40', '快速解析抽样的章数上限（约 12 万字）')
ON CONFLICT DO NOTHING;
