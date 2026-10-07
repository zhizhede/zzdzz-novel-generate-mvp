-- V1 · MVP 基础表
-- 惯例：时间列 create_time/update_time TIMESTAMPTZ NOT NULL DEFAULT NOW()；
--      所有表统一软删除双列：is_deleted BOOLEAN NOT NULL DEFAULT FALSE（显式标记，查询/索引用它）
--      + delete_time TIMESTAMPTZ（删除时间，NULL=存活）；
--      软删唯一冲突用 partial unique index (WHERE is_deleted = FALSE)。

-- 用户（MVP 仅 admin，注册流程不实现）
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'admin',
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delete_time   TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_users_username_alive ON users(username) WHERE is_deleted = FALSE;

-- 风格包（平台预制；rules_md 为蒸馏报告正文，属私有资产仅存库）
CREATE TABLE style_packs (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    rules_md    TEXT NOT NULL,
    fingerprint JSONB,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_style_packs_name_alive ON style_packs(name) WHERE is_deleted = FALSE;

-- 作品
CREATE TABLE novels (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    title         VARCHAR(256) NOT NULL,
    description   TEXT,
    style_pack_id BIGINT REFERENCES style_packs(id),
    approval_mode VARCHAR(16)  NOT NULL DEFAULT 'auto' CHECK (approval_mode IN ('auto', 'manual')),
    status        VARCHAR(32)  NOT NULL DEFAULT 'active',
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time   TIMESTAMPTZ
);

-- 章（状态机见架构文档 §四）
CREATE TABLE chapters (
    id           BIGSERIAL PRIMARY KEY,
    novel_id     BIGINT NOT NULL REFERENCES novels(id),
    chapter_no   INT    NOT NULL,
    title        VARCHAR(256),
    pov          VARCHAR(64),
    outline_yaml TEXT,
    full_text    TEXT,
    budget_min   INT NOT NULL DEFAULT 1500,
    budget_max   INT NOT NULL DEFAULT 4500,
    status       VARCHAR(32) NOT NULL DEFAULT 'NEW'
                 CHECK (status IN ('NEW', 'OUTLINED', 'OUTLINE_APPROVED', 'DRAFTING',
                                   'GATE_MECHANICAL', 'GATE_AI_REVIEW', 'REVISING',
                                   'PENDING_APPROVAL', 'FINAL', 'DIGESTED', 'INDEXED', 'FAILED')),
    round        INT NOT NULL DEFAULT 0,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_chapters_novel_no_alive ON chapters(novel_id, chapter_no) WHERE is_deleted = FALSE;

-- 场景（生成的最小单元；场景级缓存断点重跑的载体）
CREATE TABLE chapter_scenes (
    id             BIGSERIAL PRIMARY KEY,
    chapter_id     BIGINT NOT NULL REFERENCES chapters(id),
    scene_no       INT    NOT NULL,
    goal           TEXT,
    present        JSONB,
    must_reveal    JSONB,
    must_not       JSONB,
    words_budget   INT NOT NULL DEFAULT 700,
    draft_text     TEXT,
    gate_status    VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                   CHECK (gate_status IN ('PENDING', 'PASSED', 'FAILED', 'SKIPPED')),
    revision_round INT NOT NULL DEFAULT 0,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time    TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_scenes_ch_no_alive ON chapter_scenes(chapter_id, scene_no) WHERE is_deleted = FALSE;

-- 章摘要（事实账：谁做了什么/状态变化/伏笔埋设与回收）
CREATE TABLE digests (
    id          BIGSERIAL PRIMARY KEY,
    chapter_id  BIGINT NOT NULL REFERENCES chapters(id),
    content_md  TEXT NOT NULL,
    facts       JSONB,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);

-- 门禁报告（机械校验 + AI 评审，可重放审计）
CREATE TABLE gate_reports (
    id         BIGSERIAL PRIMARY KEY,
    chapter_id BIGINT NOT NULL REFERENCES chapters(id),
    scene_id   BIGINT REFERENCES chapter_scenes(id),
    gate_type  VARCHAR(16) NOT NULL CHECK (gate_type IN ('mechanical', 'ai_review')),
    round      INT NOT NULL DEFAULT 0,
    passed     BOOLEAN NOT NULL,
    result     JSONB NOT NULL,
    is_deleted BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE INDEX idx_gate_reports_chapter ON gate_reports(chapter_id, create_time DESC);

-- LLM 调用台账（每次 AI 调用一行：节点/模型/token/耗时/成败，请求与响应全量留档可重放）
CREATE TABLE llm_call_log (
    id                BIGSERIAL PRIMARY KEY,
    node              VARCHAR(64) NOT NULL,
    novel_id          BIGINT,
    chapter_id        BIGINT,
    model             VARCHAR(64) NOT NULL,
    prompt_tokens     INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens      INT NOT NULL DEFAULT 0,
    latency_ms        INT NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL CHECK (status IN ('ok', 'error')),
    error_msg         TEXT,
    reasoning_text    TEXT,
    request_json      JSONB,
    response_json     JSONB,
    is_deleted        BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time       TIMESTAMPTZ
);
CREATE INDEX idx_llm_call_chapter ON llm_call_log(chapter_id, create_time DESC);
CREATE INDEX idx_llm_call_node    ON llm_call_log(node, create_time DESC);
