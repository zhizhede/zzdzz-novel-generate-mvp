-- V11 · 素材卡：设定层结构化实体卡（角色/物品/地点/现象/地标/灾害/组织）。
-- 与三层记忆的关系：卡=设定层（本质是什么），世界状态=时点层，事实账=事件层，伏笔=长线层。
-- 生成时按 pinned + 别名匹配取值注入；无卡作品回退 canon 整文档（向下兼容）。
CREATE TABLE material_cards (
    id             BIGSERIAL PRIMARY KEY,
    novel_id       BIGINT NOT NULL REFERENCES novels(id),
    kind           VARCHAR(16) NOT NULL
                   CHECK (kind IN ('character', 'item', 'location', 'phenomenon', 'landmark', 'disaster', 'org', 'misc')),
    name           VARCHAR(128) NOT NULL,
    aliases        JSONB NOT NULL DEFAULT '[]',
    summary        TEXT,
    content_md     TEXT,
    pinned         BOOLEAN NOT NULL DEFAULT FALSE,
    status         VARCHAR(16) NOT NULL DEFAULT 'active'
                   CHECK (status IN ('active', 'retired', 'dead', 'merged')),
    source_chapter INT,
    is_deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    create_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time    TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_material_cards_novel_kind_name ON material_cards(novel_id, kind, name) WHERE is_deleted = FALSE;
CREATE INDEX idx_material_cards_novel ON material_cards(novel_id, kind) WHERE is_deleted = FALSE;
