-- V16 · RAG 语义检索：embeddings 向量表（事实账摘要 + 素材卡），embo-01 · 1536 维（实测，官方资料标 1024 以实测为准）。
-- 惰性索引：打包场景上下文时自动补嵌缺失项（幂等 upsert）；检索带出处注入，fail-open 不拦管线。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE embeddings (
    id          BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(16) NOT NULL
                CHECK (source_type IN ('digest', 'card')),
    source_id   BIGINT NOT NULL,
    novel_id    BIGINT NOT NULL,
    chapter_no  INT,
    content     TEXT NOT NULL,
    embedding   vector(1536) NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_embeddings_source ON embeddings(source_type, source_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_embeddings_novel ON embeddings(novel_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_embeddings_vec ON embeddings USING hnsw (embedding vector_cosine_ops);

INSERT INTO tuning (tkey, tvalue, description) VALUES
    ('rag_enabled', '1', 'RAG 语义检索注入开关（1 开 0 关）'),
    ('rag_top_k', '6', '场景上下文语义召回条数上限'),
    ('rag_max_distance', '0.55', '语义召回最大余弦距离，超过即丢弃');
