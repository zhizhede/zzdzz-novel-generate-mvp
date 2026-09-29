-- 接入用途分流（2026-09-29）：会话（OpenAI 兼容 /chat/completions）与向量化（MiniMax 私有
-- /embeddings：texts/base_resp/vectors）是两套协议，共用一行会互相踩——把会话切到别家时，
-- 向量化请求会被发到不支持该协议的服务上。加 role 列后，单活约束从"全平台一行"改为"每用途一行"。
ALTER TABLE llm_providers ADD COLUMN IF NOT EXISTS role varchar(16);
UPDATE llm_providers SET role = 'chat' WHERE role IS NULL;
ALTER TABLE llm_providers ALTER COLUMN role SET DEFAULT 'chat';
ALTER TABLE llm_providers ALTER COLUMN role SET NOT NULL;

-- 幂等回填：升级前唯一启用行（现状=MiniMax 主接入）改判为向量化用途，向量化链路不断；
-- 同时把空的默认模型补成该用途的真实模型名，让素材库列表自解释。
UPDATE llm_providers SET role = 'embedding', model = COALESCE(NULLIF(model, ''), 'embo-01')
WHERE is_deleted = FALSE
  AND enabled = TRUE
  AND id = (SELECT MIN(id) FROM llm_providers WHERE is_deleted = FALSE AND enabled = TRUE)
  AND NOT EXISTS (SELECT 1 FROM llm_providers WHERE is_deleted = FALSE AND role = 'embedding');

CREATE INDEX IF NOT EXISTS idx_llm_providers_role ON llm_providers (role) WHERE is_deleted = FALSE;
