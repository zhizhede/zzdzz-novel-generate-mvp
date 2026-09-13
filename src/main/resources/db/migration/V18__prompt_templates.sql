-- V18 · 提示词注册表：PromptCatalog 启动同步落库；阶段二管线从库读取（fail-open 回退代码模板）。
CREATE TABLE prompt_templates (
    id           BIGSERIAL PRIMARY KEY,
    node         VARCHAR(32)  NOT NULL,           -- LlmNode 常量名（volume_plan 等平台级节点无章级归属）
    phase        VARCHAR(16)  NOT NULL,           -- system / user
    title        VARCHAR(64)  NOT NULL,           -- 中文用途说明
    content      TEXT         NOT NULL,           -- 模板原文；%s/%d 为运行时占位
    exact        BOOLEAN      NOT NULL DEFAULT TRUE,  -- true=与代码逐字一致（接入库读取）；false=运行时拼接的骨架快照
    version      INT          NOT NULL DEFAULT 1, -- 内容变化时自增
    custom       BOOLEAN      NOT NULL DEFAULT FALSE, -- true=人工编辑过，代码同步永不覆盖
    catalog_hash VARCHAR(32),                     -- 最近一次与代码目录对齐时的 md5(content)
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delete_time  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_prompt_templates ON prompt_templates(node, phase) WHERE is_deleted = FALSE;
-- 存量行视为与代码目录对齐：catalog_hash 先回填自身 md5，代码模板真变了才触发版本递增
UPDATE prompt_templates SET catalog_hash = md5(content) WHERE catalog_hash IS NULL;
