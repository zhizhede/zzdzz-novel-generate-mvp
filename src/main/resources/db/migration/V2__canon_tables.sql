-- V3 · canon 资产入库（文件包仅作导入源，库为运行时唯一真相源）

-- canon 文档（世界观、人设卡等：按文档存取，与导入包一一对应）
CREATE TABLE canon_docs (
    id          BIGSERIAL PRIMARY KEY,
    novel_id    BIGINT NOT NULL REFERENCES novels(id),
    kind        VARCHAR(16) NOT NULL CHECK (kind IN ('world', 'character', 'misc')),
    name        VARCHAR(128) NOT NULL,
    content     TEXT NOT NULL,
    sort_no     INT NOT NULL DEFAULT 0,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_canon_docs_novel_kind_name
    ON canon_docs(novel_id, kind, name) WHERE is_deleted = FALSE;

-- 伏笔账本（结构化：章纲生成按埋设/回收章查询，评审对照防伏笔悬空）
CREATE TABLE foreshadows (
    id           BIGSERIAL PRIMARY KEY,
    novel_id     BIGINT NOT NULL REFERENCES novels(id),
    code         VARCHAR(8) NOT NULL,
    content      TEXT NOT NULL,
    planted_in   INT,
    recovered_in INT,
    status       VARCHAR(16) NOT NULL DEFAULT 'planned'
                 CHECK (status IN ('planned', 'planted', 'recovered', 'dropped')),
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_foreshadows_novel_code
    ON foreshadows(novel_id, code) WHERE is_deleted = FALSE;

-- 章节表补卷纲规划列：卷纲导入后即为本表的行（status=NEW），AI 章纲生成后回填 outline_yaml
ALTER TABLE chapters
    ADD COLUMN IF NOT EXISTS volume_no        INT,
    ADD COLUMN IF NOT EXISTS arc              VARCHAR(64),
    ADD COLUMN IF NOT EXISTS goal             TEXT,
    ADD COLUMN IF NOT EXISTS hook             VARCHAR(16),
    ADD COLUMN IF NOT EXISTS rule_refs        JSONB,
    ADD COLUMN IF NOT EXISTS foreshadow_refs  JSONB;
CREATE INDEX IF NOT EXISTS idx_chapters_novel_no ON chapters(novel_id, volume_no, chapter_no);
