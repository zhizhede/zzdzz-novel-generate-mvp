-- 量产阶段三·特征提取管线地基：品类语料（用户随时导入/新增品类，量级不限）+ 预设复用 style_packs
CREATE TABLE preset_corpus (
    id BIGSERIAL PRIMARY KEY,
    genre VARCHAR(64) NOT NULL,
    title VARCHAR(200),
    content TEXT NOT NULL,
    word_count INT NOT NULL DEFAULT 0,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE INDEX idx_preset_corpus_genre ON preset_corpus (genre) WHERE is_deleted = FALSE;

-- 预设 = 未被书引用的风格包模板，应用到书即拷贝字段
ALTER TABLE style_packs ADD COLUMN is_preset BOOLEAN NOT NULL DEFAULT FALSE;
