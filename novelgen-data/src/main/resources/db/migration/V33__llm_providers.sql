-- LLM 接入配置落库：平台级 baseUrl/apiKey(密文)/默认模型/超时，素材库·模型接入页签增删改查。
-- api_key 只存 AES-GCM 密文；主密钥在 application-local.yaml（novelgen.llm.master-key，gitignore），
-- 库泄露不等于密钥泄露。表空时运行时回退 yaml 静态配置（fail-open，老环境零变化）。
CREATE TABLE IF NOT EXISTS llm_providers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    base_url VARCHAR(256) NOT NULL,
    api_key_cipher TEXT NOT NULL,
    model VARCHAR(64),
    connect_timeout_ms INTEGER,
    read_timeout_ms INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    remark VARCHAR(256),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    delete_time TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_llm_providers_name ON llm_providers (name) WHERE is_deleted = FALSE;
