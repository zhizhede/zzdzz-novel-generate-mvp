-- ============================================================================
-- V100__derive_init.sql（草案 v1.0 · 实装时以本文件为准改名入库）
-- 依据：表结构设计-v1.2.md（15 张系统表逐字段）+ 架构设计-v1.0.md D7（首日 18 张）
-- 目标库：derive_gen（pg-vector 容器内新建库，与 zzdzz 旧库隔离——novels/chapters 同名不同构）
-- 编号：Flyway 从 V100 起，与 zzdzz V1–V43 永不撞号
-- 约定：
--   * 系统域 15 张：共用字段 created_time/updated_time/created_user_id/updated_user_id，
--     不设 is_deleted（软删已下线），枚举一律 text + CHECK（加值只动约束不动数据）
--   * 支撑域 3 张（users/llm_providers/llm_call_log）：照搬旧库字段与命名（create_time/update_time），
--     剥去软删双列，唯一索引改普通唯一索引（复用旧实体与登录/接入代码）
--   * pacing 一律 numeric(3,2)，0 慢 1 快，锚点 {0.00,0.25,0.50,0.75,1.00}
-- ============================================================================

-- ============================================================================
-- 一、支撑域（照搬旧库：V1 users / V33+V34 llm_providers / V1 llm_call_log）
-- ============================================================================

-- 用户（照搬旧库 V1，去软删；唯一索引改普通唯一）
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'admin',
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- LLM 接入配置（照搬旧库 V33 + V34 的 role 列，去软删）
-- api_key 只存 AES-GCM 密文；主密钥在 application-local.yaml（novelgen.llm.master-key，gitignore）
-- 表空时运行时回退 yaml 静态配置（fail-open，与旧库一致）
CREATE TABLE llm_providers (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(64)  NOT NULL,
    base_url          VARCHAR(256) NOT NULL,
    api_key_cipher    TEXT         NOT NULL,
    model             VARCHAR(64),
    role              VARCHAR(16)  NOT NULL DEFAULT 'chat'
                    CONSTRAINT ck_llm_providers_role CHECK (role IN ('chat', 'embedding')),
    connect_timeout_ms INTEGER,
    read_timeout_ms    INTEGER,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    remark             VARCHAR(256),
    create_time        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_llm_providers_name UNIQUE (name)
);
CREATE INDEX idx_llm_providers_role ON llm_providers (role);

-- LLM 调用台账（照搬旧库 V1；执行期 L1 层数据 · 架构 §5）
-- run_id 为衍生系统关联列（照搬基础上加列，旧实体不映射该列，插入不受影响）
CREATE TABLE llm_call_log (
    id                BIGSERIAL PRIMARY KEY,
    node              VARCHAR(64) NOT NULL,
    novel_id          BIGINT,
    chapter_id        BIGINT,
    run_id            BIGINT,
    model             VARCHAR(64) NOT NULL,
    prompt_tokens     INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens      INT NOT NULL DEFAULT 0,
    latency_ms        INT NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL CONSTRAINT ck_llm_call_status CHECK (status IN ('ok', 'error')),
    error_msg         TEXT,
    reasoning_text    TEXT,
    request_json      JSONB,
    response_json     JSONB,
    create_time       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_llm_call_chapter ON llm_call_log (chapter_id, create_time DESC);
CREATE INDEX idx_llm_call_node    ON llm_call_log (node, create_time DESC);
CREATE INDEX idx_llm_call_run     ON llm_call_log (run_id, create_time DESC);

-- ============================================================================
-- 二、系统域 15 张（v1.2 §3 逐字段；创建顺序 = 外键依赖顺序）
--    novels.run_id ↔ run.novel_id 循环外键 → 末尾 ALTER 补
-- ============================================================================

-- 3.1 novels（书 · 源书+成品统一）
CREATE TABLE novels (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(256) NOT NULL,
    source_type     VARCHAR(16)  NOT NULL
                  CONSTRAINT ck_novels_source_type CHECK (source_type IN ('SOURCE', 'GENERATED')),
    category_tags   JSONB        NOT NULL DEFAULT '{}',
    char_count      INT,
    form            VARCHAR(24)
                  CONSTRAINT ck_novels_form CHECK (form IS NULL OR form IN
                    ('LONG', 'SHORT_STANDALONE', 'LIGHT_NOVEL', 'LONG_INNER_SHORT', 'LN_INNER_SHORT')),
    chapter_count   INT,
    author          VARCHAR(128),
    platform        VARCHAR(64),
    summary         TEXT,
    run_id          BIGINT,  -- 生成它的运行（循环外键，文末 ALTER）；源书为 NULL
    -- jsonb 留缝（架构 D10 / 附录 B）：实例化世界观与角色/场景/道具/关系不建表
    story_settings  JSONB,
    story_assets    JSONB,
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id)
);
COMMENT ON COLUMN novels.story_settings IS 'jsonb 留缝：实例化世界观 {"v":1,"worldview":[…],"rules":[…]}；提表触发条件见架构附录 A-5';
COMMENT ON COLUMN novels.story_assets   IS 'jsonb 留缝：角色/场景/道具/关系 {"v":1,"characters":[…],…}；元素至少含 name 供 GIN 检索；提表触发条件见架构附录 A-3/A-4';
CREATE INDEX idx_novels_story_settings ON novels USING GIN (story_settings jsonb_path_ops);
CREATE INDEX idx_novels_story_assets   ON novels USING GIN (story_assets   jsonb_path_ops);
CREATE INDEX idx_novels_run_id         ON novels (run_id);

-- 3.2 source_chapter（源书切章 · D2 拍板：正文入表）
CREATE TABLE source_chapter (
    id              BIGSERIAL PRIMARY KEY,
    novel_id        BIGINT NOT NULL REFERENCES novels (id),
    seq             INT    NOT NULL,
    title           VARCHAR(256),
    content         TEXT   NOT NULL,
    char_count      INT    NOT NULL DEFAULT 0,
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id),
    CONSTRAINT uq_source_chapter_novel_seq UNIQUE (novel_id, seq)
);

-- 3.6 world_kind（先于 asset_world 创建）
CREATE TABLE world_kind (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(8)  NOT NULL,
    name            VARCHAR(64) NOT NULL,
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id),
    CONSTRAINT uq_world_kind_code UNIQUE (code)
);

-- 3.3 asset_outline（弧素材 · 分表维持 D5；共用列铁律见架构 §6.1）
CREATE TABLE asset_outline (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(16) NOT NULL,             -- "O-P01"，④全局重编号；跨表前缀注册制
    src_novel_id    BIGINT      NOT NULL REFERENCES novels (id),
    extracted_text  TEXT,                             -- ②抽取稿（有指向血缘，永久保留）
    label_text      TEXT,                             -- ③标签层因果链
    func_tag        VARCHAR(128),                     -- ③功能标签
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id),
    CONSTRAINT uq_asset_outline_code UNIQUE (code)
);
CREATE INDEX idx_asset_outline_src ON asset_outline (src_novel_id);

-- 3.4 asset_beat（拍素材）
CREATE TABLE asset_beat (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(16) NOT NULL,             -- "S-P01"
    src_novel_id    BIGINT      NOT NULL REFERENCES novels (id),
    extracted_text  TEXT,
    label_text      TEXT,
    func_tag        VARCHAR(128),
    parent_arc_id   BIGINT REFERENCES asset_outline (id),  -- 原生父弧（供"抽弧连带原生拍链"）
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id),
    CONSTRAINT uq_asset_beat_code UNIQUE (code)
);
CREATE INDEX idx_asset_beat_src  ON asset_beat (src_novel_id);
CREATE INDEX idx_asset_beat_arc  ON asset_beat (parent_arc_id);

-- 3.5 asset_world（世界观素材）
CREATE TABLE asset_world (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(16) NOT NULL,             -- "W-P01"
    src_novel_id    BIGINT      NOT NULL REFERENCES novels (id),
    kind            VARCHAR(8)  NOT NULL REFERENCES world_kind (code),
    extracted_text  TEXT,
    label_text      TEXT,
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id),
    CONSTRAINT uq_asset_world_code UNIQUE (code)
);
CREATE INDEX idx_asset_world_src  ON asset_world (src_novel_id);
CREATE INDEX idx_asset_world_kind ON asset_world (kind);

-- 3.7 label_index（标签索引 · 单一事实源；任何素材表/章节表不再重复存标签）
CREATE TABLE label_index (
    id              BIGSERIAL PRIMARY KEY,
    entry_type      VARCHAR(16) NOT NULL
                  CONSTRAINT ck_label_entry_type CHECK (entry_type IN ('OUTLINE', 'BEAT', 'WORLD')),
    entry_type_id   BIGINT      NOT NULL,             -- 多态关联三张素材表 id；多态无外键，应用层保证
    cat             JSONB       NOT NULL DEFAULT '{}',   -- {类别:0~1} 无序多标签
    beat_type       VARCHAR(8)
                  CONSTRAINT ck_label_beat_type CHECK (beat_type IS NULL OR beat_type IN
                    ('铺垫', '情绪', '事件', '反转', '抉择', '收束')),  -- WORLD 行 NULL，单值不权重化
    pacing          NUMERIC(3,2) NOT NULL
                  CONSTRAINT ck_label_pacing CHECK (pacing >= 0.00 AND pacing <= 1.00),
    pacing_src      VARCHAR(8)  NOT NULL
                  CONSTRAINT ck_label_pacing_src CHECK (pacing_src IN ('LLM', 'DERIVED', 'MANUAL')),
    arc_fit         JSONB       NOT NULL DEFAULT '{}',   -- {开头,中段,转折,收尾} 取值 0/0.5/1.0
    standalone      BOOLEAN     NOT NULL DEFAULT FALSE,
    src_novel_id    BIGINT REFERENCES novels (id),       -- 冗余血缘（快速过滤），以素材表为准
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id),
    CONSTRAINT uq_label_index_entry UNIQUE (entry_type, entry_type_id),
    -- LLM 打标行强制锚点；DERIVED/MANUAL 允许两位小数微调（v1.2 §3.7）
    CONSTRAINT ck_label_pacing_llm_anchor
        CHECK (pacing_src <> 'LLM' OR pacing IN (0.00, 0.25, 0.50, 0.75, 1.00))
);
CREATE INDEX idx_label_index_src ON label_index (src_novel_id);
CREATE INDEX idx_label_index_cat ON label_index USING GIN (cat jsonb_path_ops);

-- 3.8 domain_cell（域格）
CREATE TABLE domain_cell (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(64) NOT NULL,             -- 如"食堂 / 小卖部"
    grp             VARCHAR(32) NOT NULL,             -- 组（校园域/都市域/…），同组跨格规则依据
    cats            JSONB       NOT NULL DEFAULT '{}',   -- {类别:0~1} 与 label_index.cat 同轴同构
    beat_types      TEXT[]      NOT NULL DEFAULT '{}',   -- 可承载拍型（抽拍后校验用）
    relation_note   TEXT,                                -- 关系动作落位（⑥b 提示词素材）
    counter_words   TEXT[]      NOT NULL DEFAULT '{}',   -- 反域词（组级共享+格级增量）
    ref_ids         JSONB       NOT NULL DEFAULT '[]',   -- 归纳依据 ["W-A02",…]，多源归纳不挂单一书
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    sort            INT         NOT NULL DEFAULT 0,
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id)
);
CREATE INDEX idx_domain_cell_grp  ON domain_cell (grp);
CREATE INDEX idx_domain_cell_cats ON domain_cell USING GIN (cats jsonb_path_ops);

-- 3.9 wordlist（词表 · ④/⑧脚本改读表，不再散落脚本内）
CREATE TABLE wordlist (
    id              BIGSERIAL PRIMARY KEY,
    kind            VARCHAR(16) NOT NULL
                  CONSTRAINT ck_wordlist_kind CHECK (kind IN
                    ('BLACKLIST', 'DOMAIN_WORD', 'CATEGORY_TERM', 'ANTI_DOMAIN')),
    word            VARCHAR(64) NOT NULL,
    grp             VARCHAR(32),                        -- 反域词按组挂载
    category        VARCHAR(32),                        -- CATEGORY_TERM 时填
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id)
);
-- 不设唯一约束：同词可合法归属不同组（ANTI_DOMAIN 组级共享）；④入库时应用层去重
CREATE INDEX idx_wordlist_kind_word ON wordlist (kind, word);

-- 3.10 run（装配卡 · 聚合根；run 自身即任务行 D9；step_status 为 L2 层）
CREATE TABLE run (
    id                 BIGSERIAL PRIMARY KEY,
    seed               BIGINT      NOT NULL,           -- ⑤随机种子（断点复现三要素之一）
    param_words_min    INT         NOT NULL,           -- ⑤·用户钉死
    param_words_max    INT         NOT NULL,
    param_tags         JSONB       NOT NULL DEFAULT '{}',  -- 首位=主标签权重×2
    param_pace         NUMERIC(3,2) NOT NULL
                     CONSTRAINT ck_run_param_pace CHECK (param_pace >= 0.00 AND param_pace <= 1.00),
    param_form         VARCHAR(24)
                     CONSTRAINT ck_run_param_form CHECK (param_form IS NULL OR param_form IN
                       ('LONG', 'SHORT_STANDALONE', 'LIGHT_NOVEL', 'LONG_INNER_SHORT', 'LN_INNER_SHORT')),
    param_outcome      VARCHAR(8)  NOT NULL            -- ⑤seed 抽
                     CONSTRAINT ck_run_param_outcome CHECK (param_outcome IN ('循环', '开放', '闭合')),
    domain_id          BIGINT REFERENCES domain_cell (id),
    domain_snapshot    JSONB,                          -- 域格快照（卡片自包含，⑥⑦⑧不回表）
    b0                 INT,                            -- ⑤5.1 每拍字数基线
    p_min              INT,                            -- 预测总拍数区间
    p_max              INT,
    k_outline          INT,                            -- 抽取弧条数
    m_beat             INT,                            -- 抽取拍条数
    tolerance_trace    JSONB,                          -- ⑤5.2 宽容度轨迹（各级降级命中数）
    picked_outline_ids JSONB,                          -- 抽样结果
    picked_beat_ids    JSONB,
    picked_world_id    BIGINT REFERENCES asset_world (id),
    welded             JSONB,                          -- ⑤5.3 焊接产物 O-Fxx（K≥2 时）
    novel_id           BIGINT REFERENCES novels (id),  -- 生成的成品书（⑥创建后回填）
    status             VARCHAR(16) NOT NULL DEFAULT 'SAMPLING'
                     CONSTRAINT ck_run_status CHECK (status IN
                       ('SAMPLING', 'PLANNING', 'DRAFTING', 'QC', 'PASSED', 'REWORK', 'FAILED')),
    qc_round           INT         NOT NULL DEFAULT 0, -- ⑧（≤2 回炉上限）
    step_status        JSONB       NOT NULL DEFAULT '{"v":1,"steps":{}}',  -- L2 层（架构 §4）
    created_time       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id    BIGINT REFERENCES users (id),
    updated_user_id    BIGINT REFERENCES users (id)
);
COMMENT ON COLUMN run.step_status IS 'L2 步状态 {"v":1,"steps":{"SAMPLE":{"status":"DONE",…}}}；失败步幂等重做；跨run调度需求满足时提 run_task 表（架构附录 A-1）';
CREATE INDEX idx_run_status ON run (status);

-- 3.11 beat_instance（拍实例 · 挂拍；⑧"每拍恰好一次"= (run_id,seq)+chapter_no 核对）
CREATE TABLE beat_instance (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES run (id),
    seq          INT         NOT NULL,                 -- 全局拍序
    source       VARCHAR(8)  NOT NULL
               CONSTRAINT ck_beat_instance_source CHECK (source IN ('ARC_SEG', 'ASSET')),
    asset_id     BIGINT REFERENCES asset_beat (id),    -- source=ASSET 时填（拍素材直采）
    arc_id       BIGINT REFERENCES asset_outline (id), -- source=ARC_SEG 时填（弧段拆拍）
    arc_seg_no   INT,                                  -- 段内序号
    beat_type    VARCHAR(8)  NOT NULL                  -- 快照（⑤/⑥a）
               CONSTRAINT ck_beat_instance_beat_type CHECK (beat_type IN
                 ('铺垫', '情绪', '事件', '反转', '抉择', '收束')),
    pacing       NUMERIC(3,2) NOT NULL                 -- 快照（⑤）
               CONSTRAINT ck_beat_instance_pacing CHECK (pacing >= 0.00 AND pacing <= 1.00),
    chapter_no   INT,                                  -- 归属章（⑥a；未分配为 NULL）
    filled_text  TEXT,                                 -- ⑥b 填实后的具体情节点
    status       VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
               CONSTRAINT ck_beat_instance_status CHECK (status IN
                 ('PLANNED', 'WRITTEN', 'VERIFIED')),
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_beat_instance_run_seq UNIQUE (run_id, seq)
);
CREATE INDEX idx_beat_instance_chapter ON beat_instance (run_id, chapter_no);

-- 3.12 chapters（章节 · 计划+正文一体；v1 四列大纲/节拍已撤，见 v1.2 §3.12）
CREATE TABLE chapters (
    id              BIGSERIAL PRIMARY KEY,
    novel_id        BIGINT      NOT NULL REFERENCES novels (id),
    chapter_no      INT         NOT NULL,
    title           VARCHAR(256),                       -- 缺省"第N章"，⑦定稿
    budget          INT         NOT NULL,               -- 预算字数（±10%硬校验）
    char_count      INT,                                -- ⑧脚本回填
    draft_outline   TEXT,                               -- ⑥b 本章具体大纲（非无指向）
    content         TEXT,                               -- ⑦正文（覆盖式留终稿，D8）
    pace_plan       NUMERIC(3,2)                        -- ⑥a 计划节奏（可由挂拍均值派生，允许两位小数）
                  CONSTRAINT ck_chapters_pace_plan CHECK (pace_plan IS NULL OR (pace_plan >= 0.00 AND pace_plan <= 1.00)),
    status          VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
                  CONSTRAINT ck_chapters_status CHECK (status IN
                    ('PLANNED', 'DRAFTED', 'QC_PASS', 'REWORK')),
    comment         TEXT,                                -- 章注（QC 用，正文剥离后扫描）
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id),
    CONSTRAINT uq_chapters_novel_no UNIQUE (novel_id, chapter_no)
);

-- 3.13 qc_report（质检报告）
CREATE TABLE qc_report (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES run (id),
    round_no     INT         NOT NULL,                  -- 1 起
    script_result JSONB,                                -- ⑧脚本硬判（字数/挂拍/黑名单/反域/浓度/指纹 逐项）
    llm_result   JSONB,                                 -- ⑧LLM 快检（兑现/节奏/语气/域 四项）
    verdict      VARCHAR(8)  NOT NULL
               CONSTRAINT ck_qc_verdict CHECK (verdict IN ('PASS', 'REWORK', 'FAIL')),
    issues       JSONB,                                 -- 问题清单
    repair_log   JSONB,                                 -- 修复记录（≤2 轮，只记动作不存全文 · 架构 §5 L4）
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_qc_report_run_round UNIQUE (run_id, round_no)
);

-- 3.14 calibration（标定记录；B0 换算表每 10 轮从本表滚动回归，替代 calibration.jsonl 文件形态）
CREATE TABLE calibration (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT       NOT NULL REFERENCES run (id),
    pace               NUMERIC(3,2) NOT NULL           -- 本轮节奏（⑤）
                     CONSTRAINT ck_calibration_pace CHECK (pace >= 0.00 AND pace <= 1.00),
    b0                 INT          NOT NULL,          -- 采用基线（⑤）
    p_pred             INT,                             -- 预测拍数（⑤）
    p_actual           INT,                             -- 实测拍数（⑧）
    words_pred         INT,                             -- 预测字数（⑤，clamp 后）
    words_actual       INT,                             -- 实测字数（⑧）
    chapter_avg_actual INT,                             -- 章均字数实测（⑧）
    created_time       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_time       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_calibration_run ON calibration (run_id);

-- 3.15 work_fingerprint（成品查重登记 · ⑧中央共享库；跨 run 全局查重的数据源）
CREATE TABLE work_fingerprint (
    id           BIGSERIAL PRIMARY KEY,
    novel_id     BIGINT      NOT NULL REFERENCES novels (id),
    kind         VARCHAR(16) NOT NULL
               CONSTRAINT ck_work_fp_kind CHECK (kind IN
                 ('PROTAGONIST_NAME', 'BRIDGE', 'TROUPE')),
    value        VARCHAR(128) NOT NULL,                -- 如主角名"沈X"、桥段指纹"记账女主"
    fingerprint  VARCHAR(64),                           -- 标签组合哈希（同构弧检测）
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_work_fp_novel_kind_value UNIQUE (novel_id, kind, value)
);
CREATE INDEX idx_work_fp_value ON work_fingerprint (value);

-- ============================================================================
-- 三、循环外键（novels.run_id ↔ run.novel_id，文末补）
-- ============================================================================

ALTER TABLE novels
    ADD CONSTRAINT fk_novels_run FOREIGN KEY (run_id) REFERENCES run (id);

-- ============================================================================
-- 四、统一读取视图（架构 §6.1：新增素材类型 = 建表 + 本视图 UNION 补一段，调用方不改）
-- ============================================================================

CREATE VIEW v_asset_all AS
SELECT 'OUTLINE'::VARCHAR(8) AS kind,
       id, code, src_novel_id, extracted_text, label_text, func_tag,
       NULL::BIGINT      AS parent_arc_id,
       NULL::VARCHAR(8)  AS world_kind,
       created_time
FROM asset_outline
UNION ALL
SELECT 'BEAT'::VARCHAR(8), id, code, src_novel_id, extracted_text, label_text, func_tag,
       parent_arc_id, NULL::VARCHAR(8), created_time
FROM asset_beat
UNION ALL
SELECT 'WORLD'::VARCHAR(8), id, code, src_novel_id, extracted_text, label_text,
       NULL::VARCHAR(128) AS func_tag,
       NULL::BIGINT, kind, created_time
FROM asset_world;

-- ============================================================================
-- 五、种子数据
-- ============================================================================

INSERT INTO world_kind (code, name) VALUES ('RULE', '硬规则'), ('FACT', '世界事实')
ON CONFLICT (code) DO NOTHING;

-- ============================================================================
-- 六、表与列注释（2026-10-12 需求：建表必须给表和每一列加用处说明）
--    中文说明取自 表结构设计-v1.2.md 三元组的「中文名」列，两处如有出入以 v1.2 为准
-- ============================================================================

-- ---- 支撑域 ----
COMMENT ON TABLE users IS '用户（照搬旧库 V1，去软删；MVP 仅 admin）';
COMMENT ON COLUMN users.id IS '主键';
COMMENT ON COLUMN users.username IS '用户名（唯一）';
COMMENT ON COLUMN users.password_hash IS '密码哈希';
COMMENT ON COLUMN users.role IS '角色（默认 admin）';
COMMENT ON COLUMN users.create_time IS '创建时间';
COMMENT ON COLUMN users.update_time IS '更新时间';

COMMENT ON TABLE llm_providers IS 'LLM 接入配置（照搬旧库 V33+V34，去软删；api_key 存 AES-GCM 密文，表空时回退 yaml 静态配置）';
COMMENT ON COLUMN llm_providers.id IS '主键';
COMMENT ON COLUMN llm_providers.name IS '接入名（唯一）';
COMMENT ON COLUMN llm_providers.base_url IS '服务地址 baseUrl';
COMMENT ON COLUMN llm_providers.api_key_cipher IS 'API Key 密文（AES-GCM，主钥在 application-local.yaml，永不入库明文）';
COMMENT ON COLUMN llm_providers.model IS '默认模型名';
COMMENT ON COLUMN llm_providers.role IS '接入用途：chat=会话（/chat/completions）/ embedding=向量化（/embeddings），同用途单活';
COMMENT ON COLUMN llm_providers.connect_timeout_ms IS '连接超时（毫秒）';
COMMENT ON COLUMN llm_providers.read_timeout_ms IS '读超时（毫秒）';
COMMENT ON COLUMN llm_providers.enabled IS '是否启用';
COMMENT ON COLUMN llm_providers.remark IS '备注';
COMMENT ON COLUMN llm_providers.create_time IS '创建时间';
COMMENT ON COLUMN llm_providers.update_time IS '更新时间';

COMMENT ON TABLE llm_call_log IS 'LLM 调用台账（照搬旧库 V1 去软删；每次 AI 调用一行，请求响应全量留档可重放——执行期 L1 层数据，排障唯一依据）';
COMMENT ON COLUMN llm_call_log.id IS '主键';
COMMENT ON COLUMN llm_call_log.node IS 'LLM 节点名（如采样/打标/生成等步骤节点）';
COMMENT ON COLUMN llm_call_log.novel_id IS '关联书 id（无书阶段为 NULL）';
COMMENT ON COLUMN llm_call_log.chapter_id IS '关联章 id';
COMMENT ON COLUMN llm_call_log.run_id IS '关联运行 id（衍生系统照搬时新增的关联列，旧实体不映射）';
COMMENT ON COLUMN llm_call_log.model IS '实际调用模型';
COMMENT ON COLUMN llm_call_log.prompt_tokens IS '输入 token 数';
COMMENT ON COLUMN llm_call_log.completion_tokens IS '输出 token 数';
COMMENT ON COLUMN llm_call_log.total_tokens IS '总 token 数';
COMMENT ON COLUMN llm_call_log.latency_ms IS '耗时（毫秒）';
COMMENT ON COLUMN llm_call_log.status IS '成败：ok / error';
COMMENT ON COLUMN llm_call_log.error_msg IS '错误信息';
COMMENT ON COLUMN llm_call_log.reasoning_text IS '推理文本（如有）';
COMMENT ON COLUMN llm_call_log.request_json IS '请求全量留档';
COMMENT ON COLUMN llm_call_log.response_json IS '响应全量留档（取原文用 choices->0->message->content）';
COMMENT ON COLUMN llm_call_log.create_time IS '创建时间';
COMMENT ON COLUMN llm_call_log.update_time IS '更新时间';

-- ---- 系统域 3.1 novels ----
COMMENT ON TABLE novels IS '书（源书+成品统一，v1.2 §3.1）';
COMMENT ON COLUMN novels.id IS '主键';
COMMENT ON COLUMN novels.title IS '书名（①录入 / ⑥b起名）';
COMMENT ON COLUMN novels.source_type IS '来源类型：SOURCE=源书 / GENERATED=系统生成（①⑦）';
COMMENT ON COLUMN novels.category_tags IS '类别标签 {言情:1,校园:1,…}（源书=①标注；成品=⑤param_tags 回填）';
COMMENT ON COLUMN novels.char_count IS '全书字数（⑧汇总回填）';
COMMENT ON COLUMN novels.form IS '产品形态：LONG / SHORT_STANDALONE / LIGHT_NOVEL / LONG_INNER_SHORT / LN_INNER_SHORT（⑤param_form）';
COMMENT ON COLUMN novels.chapter_count IS '章节数（⑧回填，=chapters 计数）';
COMMENT ON COLUMN novels.author IS '书籍作者（源书=原作者；成品=系统生成）';
COMMENT ON COLUMN novels.platform IS '书籍平台（晋江/起点/名著/系统生成…）';
COMMENT ON COLUMN novels.summary IS '全书一句话概括（⑥b）';
COMMENT ON COLUMN novels.run_id IS '生成它的运行 run.id（成品回链，源书为 NULL）';
COMMENT ON COLUMN novels.created_time IS '创建时间';
COMMENT ON COLUMN novels.updated_time IS '更新时间';
COMMENT ON COLUMN novels.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN novels.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.2 source_chapter ----
COMMENT ON TABLE source_chapter IS '源书切章（①；D2 拍板正文入表，不与成品 chapters 混存）';
COMMENT ON COLUMN source_chapter.id IS '主键';
COMMENT ON COLUMN source_chapter.novel_id IS '所属源书 → novels.id';
COMMENT ON COLUMN source_chapter.seq IS '章序（①切章，与 novel_id 唯一）';
COMMENT ON COLUMN source_chapter.title IS '章题（①）';
COMMENT ON COLUMN source_chapter.content IS '原文正文（①）';
COMMENT ON COLUMN source_chapter.char_count IS '字数（①）';
COMMENT ON COLUMN source_chapter.created_time IS '创建时间';
COMMENT ON COLUMN source_chapter.updated_time IS '更新时间';
COMMENT ON COLUMN source_chapter.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN source_chapter.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.6 world_kind ----
COMMENT ON TABLE world_kind IS '世界观种类字典（④初始化；被 asset_world.kind 依赖，故先于 asset_world 创建）';
COMMENT ON COLUMN world_kind.id IS '主键';
COMMENT ON COLUMN world_kind.code IS '种类代号（RULE=硬规则 / FACT=世界事实，唯一）';
COMMENT ON COLUMN world_kind.name IS '种类名';
COMMENT ON COLUMN world_kind.created_time IS '创建时间';
COMMENT ON COLUMN world_kind.updated_time IS '更新时间';
COMMENT ON COLUMN world_kind.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN world_kind.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.3 asset_outline ----
COMMENT ON TABLE asset_outline IS '弧素材（原"无指向大纲"，②③；三张 asset 分表维持 D5，共用列铁律见架构 §6.1）';
COMMENT ON COLUMN asset_outline.id IS '主键';
COMMENT ON COLUMN asset_outline.code IS '资产编号（"O-P01"，④全局重编号，跨表前缀注册制）';
COMMENT ON COLUMN asset_outline.src_novel_id IS '血缘源书 → novels.id（②）';
COMMENT ON COLUMN asset_outline.extracted_text IS '抽取稿：②有指向原文（血缘列，永久保留）';
COMMENT ON COLUMN asset_outline.label_text IS '标签层因果链（③产物，不含标签头）';
COMMENT ON COLUMN asset_outline.func_tag IS '功能标签（③，如 [绑定][护短]；与 label_text 拼接即展示用内容）';
COMMENT ON COLUMN asset_outline.created_time IS '创建时间';
COMMENT ON COLUMN asset_outline.updated_time IS '更新时间';
COMMENT ON COLUMN asset_outline.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN asset_outline.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.4 asset_beat ----
COMMENT ON TABLE asset_beat IS '拍素材（原"无指向节拍"，②③；④的"每拍恰好一次"与⑧核对的源头粒度）';
COMMENT ON COLUMN asset_beat.id IS '主键';
COMMENT ON COLUMN asset_beat.code IS '资产编号（"S-P01"，④）';
COMMENT ON COLUMN asset_beat.src_novel_id IS '血缘源书 → novels.id（②）';
COMMENT ON COLUMN asset_beat.extracted_text IS '抽取稿（②，≤40字拍）';
COMMENT ON COLUMN asset_beat.label_text IS '标签层"谁做什么发生什么结果"（③）';
COMMENT ON COLUMN asset_beat.func_tag IS '功能标签（③）';
COMMENT ON COLUMN asset_beat.parent_arc_id IS '原生父弧 → asset_outline.id（②，④重编号后保留，供"抽弧连带原生拍链"升级用）';
COMMENT ON COLUMN asset_beat.created_time IS '创建时间';
COMMENT ON COLUMN asset_beat.updated_time IS '更新时间';
COMMENT ON COLUMN asset_beat.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN asset_beat.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.5 asset_world ----
COMMENT ON TABLE asset_world IS '世界观素材（②③，kind 依赖 world_kind 字典故建在其后）';
COMMENT ON COLUMN asset_world.id IS '主键';
COMMENT ON COLUMN asset_world.code IS '资产编号（"W-P01"，④）';
COMMENT ON COLUMN asset_world.src_novel_id IS '血缘源书 → novels.id（②）';
COMMENT ON COLUMN asset_world.kind IS '种类 → world_kind.code（硬规则/世界事实）';
COMMENT ON COLUMN asset_world.extracted_text IS '抽取稿（②）';
COMMENT ON COLUMN asset_world.label_text IS '标签层"条件→后果"（③）';
COMMENT ON COLUMN asset_world.created_time IS '创建时间';
COMMENT ON COLUMN asset_world.updated_time IS '更新时间';
COMMENT ON COLUMN asset_world.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN asset_world.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.7 label_index ----
COMMENT ON TABLE label_index IS '标签索引·标签单一事实源（④b；cat/beat_type/pacing/arc_fit/standalone 只存在本表，素材表章节表不重复存）';
COMMENT ON COLUMN label_index.id IS '主键';
COMMENT ON COLUMN label_index.entry_type IS '条目类型：OUTLINE / BEAT / WORLD（④b）';
COMMENT ON COLUMN label_index.entry_type_id IS '条目 id（对应三张素材表 id，与 entry_type 组复合唯一；多态关联无外键，应用层保证）';
COMMENT ON COLUMN label_index.cat IS '类别亲和 {类别:0~1}（④b，无序多标签）';
COMMENT ON COLUMN label_index.beat_type IS '拍型：铺垫/情绪/事件/反转/抉择/收束（④b；WORLD 行 NULL，单值不权重化）';
COMMENT ON COLUMN label_index.pacing IS '节奏 0~1 锚点 {0.00,0.25,0.50,0.75,1.00}，0慢1快（LLM 行仅锚点，DERIVED/MANUAL 允许两位小数）';
COMMENT ON COLUMN label_index.pacing_src IS '节奏来源：LLM / DERIVED / MANUAL（LLM 行由 CHECK 强制锚点）';
COMMENT ON COLUMN label_index.arc_fit IS '弧位胜任 {开头,中段,转折,收尾} 取值 0/0.5/1.0（④b，多选后脚本转权重）';
COMMENT ON COLUMN label_index.standalone IS '可独立成篇（④b，原"单用"，从 arc_fit 拆出）';
COMMENT ON COLUMN label_index.src_novel_id IS '血缘（冗余列供快速过滤，以素材表为准）';
COMMENT ON COLUMN label_index.created_time IS '创建时间';
COMMENT ON COLUMN label_index.updated_time IS '更新时间';
COMMENT ON COLUMN label_index.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN label_index.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.8 domain_cell ----
COMMENT ON TABLE domain_cell IS '域格（④c；域必须由 seed 随机注入以治原型先验，卡片自包含经 run.domain_snapshot 带出）';
COMMENT ON COLUMN domain_cell.id IS '主键';
COMMENT ON COLUMN domain_cell.name IS '域格名（如"食堂 / 小卖部"）';
COMMENT ON COLUMN domain_cell.grp IS '组（校园域/都市域/…，同组跨格规则依据）';
COMMENT ON COLUMN domain_cell.cats IS '承载类别 {类别:0~1}（成员判定≥阈值，与 label_index.cat 同轴同构）';
COMMENT ON COLUMN domain_cell.beat_types IS '可承载拍型数组（抽拍后校验用）';
COMMENT ON COLUMN domain_cell.relation_note IS '关系动作落位说明（⑥b 提示词素材）';
COMMENT ON COLUMN domain_cell.counter_words IS '反域词数组（⑧ grep；组级共享+格级增量）';
COMMENT ON COLUMN domain_cell.ref_ids IS '归纳依据 ["W-A02",…]（④c，多源归纳不挂单一 novel_id）';
COMMENT ON COLUMN domain_cell.enabled IS '启用（④c）';
COMMENT ON COLUMN domain_cell.sort IS '排序（④c）';
COMMENT ON COLUMN domain_cell.created_time IS '创建时间';
COMMENT ON COLUMN domain_cell.updated_time IS '更新时间';
COMMENT ON COLUMN domain_cell.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN domain_cell.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.9 wordlist ----
COMMENT ON TABLE wordlist IS '词表·黑名单/领域/浓度/反域（④⑧；v1 散落脚本的词表全部入库，脚本改读表）';
COMMENT ON COLUMN wordlist.id IS '主键';
COMMENT ON COLUMN wordlist.kind IS '词表种类：BLACKLIST=来源/作品名 / DOMAIN_WORD=源领域词 / CATEGORY_TERM=类别浓度词 / ANTI_DOMAIN=反域词·组级';
COMMENT ON COLUMN wordlist.word IS '词（④）';
COMMENT ON COLUMN wordlist.grp IS '归属组（ANTI_DOMAIN 按组挂载，同词可合法归属不同组）';
COMMENT ON COLUMN wordlist.category IS '类别（CATEGORY_TERM 时填）';
COMMENT ON COLUMN wordlist.created_time IS '创建时间';
COMMENT ON COLUMN wordlist.updated_time IS '更新时间';
COMMENT ON COLUMN wordlist.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN wordlist.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.10 run ----
COMMENT ON TABLE run IS '装配卡·聚合根（⑤；run 自身即任务行 D9，status 即队列状态，单 worker 串行 8 步）';
COMMENT ON COLUMN run.id IS '主键';
COMMENT ON COLUMN run.seed IS '随机种子（⑤；断点复现三要素之一）';
COMMENT ON COLUMN run.param_words_min IS '字数区间下限（⑤·用户钉死）';
COMMENT ON COLUMN run.param_words_max IS '字数区间上限（⑤·用户钉死）';
COMMENT ON COLUMN run.param_tags IS '类别 tags jsonb（⑤·用户钉死，首位=主标签权重×2）';
COMMENT ON COLUMN run.param_pace IS '目标节奏 0~1 锚点（⑤·用户钉死，同 label_index.pacing 标度，0慢1快）';
COMMENT ON COLUMN run.param_form IS '产品形态（⑤，未给则 seed 抽；枚举同 novels.form）';
COMMENT ON COLUMN run.param_outcome IS '结局形状：循环/开放/闭合（⑤seed 抽）';
COMMENT ON COLUMN run.domain_id IS '抽中域格 → domain_cell.id（⑤）';
COMMENT ON COLUMN run.domain_snapshot IS '域格快照（⑤，卡片自包含，⑥⑦⑧不回表）';
COMMENT ON COLUMN run.b0 IS '本 run 采用的每拍字数基线（⑤5.1，锚点换算 {0:2500,0.25:2500,0.5:1500,0.75:1100,1:1100}）';
COMMENT ON COLUMN run.p_min IS '预测总拍数区间下限（⑤5.1）';
COMMENT ON COLUMN run.p_max IS '预测总拍数区间上限（⑤5.1）';
COMMENT ON COLUMN run.k_outline IS '抽取弧条数（⑤5.1）';
COMMENT ON COLUMN run.m_beat IS '抽取拍条数（⑤5.1）';
COMMENT ON COLUMN run.tolerance_trace IS '宽容度轨迹（⑤5.2，各级降级命中数；候选集不留库时靠 seed 复算）';
COMMENT ON COLUMN run.picked_outline_ids IS '抽中的弧素材 id（⑤5.2）';
COMMENT ON COLUMN run.picked_beat_ids IS '抽中的拍素材 id（⑤5.2）';
COMMENT ON COLUMN run.picked_world_id IS '抽中的世界观素材 id → asset_world.id（⑤5.2）';
COMMENT ON COLUMN run.welded IS '焊接产物 O-Fxx（⑤5.3，K≥2 时）';
COMMENT ON COLUMN run.novel_id IS '生成的成品书 → novels.id（⑥创建后回填；与 novels.run_id 互指为循环外键）';
COMMENT ON COLUMN run.status IS '状态机：SAMPLING→PLANNING→DRAFTING→QC→PASSED / REWORK / FAILED（⑤~⑧）';
COMMENT ON COLUMN run.qc_round IS '已执行质检轮次（⑧，≤2 回炉上限）';
COMMENT ON COLUMN run.created_time IS '创建时间';
COMMENT ON COLUMN run.updated_time IS '更新时间';
COMMENT ON COLUMN run.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN run.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.11 beat_instance ----
COMMENT ON TABLE beat_instance IS '拍实例·挂拍（⑥a/⑦/⑧；⑧"每拍各恰好一次"= 对本表 (run_id, seq) 与 chapter_no 归属核对）';
COMMENT ON COLUMN beat_instance.id IS '主键';
COMMENT ON COLUMN beat_instance.run_id IS '所属运行 → run.id（⑤）';
COMMENT ON COLUMN beat_instance.seq IS '全局拍序（⑥a，与 run_id 唯一）';
COMMENT ON COLUMN beat_instance.source IS '拍来源：ARC_SEG=弧段拆拍 / ASSET=拍素材直采（⑤）';
COMMENT ON COLUMN beat_instance.asset_id IS '来源素材 → asset_beat.id（source=ASSET 时填）';
COMMENT ON COLUMN beat_instance.arc_id IS '来源父弧 → asset_outline.id（source=ARC_SEG 时填）';
COMMENT ON COLUMN beat_instance.arc_seg_no IS '弧内段序号（source=ARC_SEG 时填）';
COMMENT ON COLUMN beat_instance.beat_type IS '拍型快照（⑤/⑥a，枚举同 label_index.beat_type）';
COMMENT ON COLUMN beat_instance.pacing IS '节奏快照（⑤锚点）';
COMMENT ON COLUMN beat_instance.chapter_no IS '归属章号（⑥a，未分配为 NULL）';
COMMENT ON COLUMN beat_instance.filled_text IS '填实后的具体情节点（⑥b）';
COMMENT ON COLUMN beat_instance.status IS '状态：PLANNED→WRITTEN→VERIFIED（⑥a/⑦/⑧）';
COMMENT ON COLUMN beat_instance.created_time IS '创建时间';
COMMENT ON COLUMN beat_instance.updated_time IS '更新时间';

-- ---- 系统域 3.12 chapters ----
COMMENT ON TABLE chapters IS '章节·计划+正文一体（⑥a/⑦/⑧；v1 的无指向/非无指向四列已撤，无指向拍→beat_instance，具体大纲→draft_outline）';
COMMENT ON COLUMN chapters.id IS '主键';
COMMENT ON COLUMN chapters.novel_id IS '所属书 → novels.id（⑥a）';
COMMENT ON COLUMN chapters.chapter_no IS '章节号（⑥a，与 novel_id 唯一）';
COMMENT ON COLUMN chapters.title IS '章节名（缺省"第N章"；⑥a title_hint / ⑦定稿）';
COMMENT ON COLUMN chapters.budget IS '预算字数（⑥a，±10%硬校验）';
COMMENT ON COLUMN chapters.char_count IS '实测字数（⑧脚本回填）';
COMMENT ON COLUMN chapters.draft_outline IS '本章具体大纲（非无指向，⑥b）';
COMMENT ON COLUMN chapters.content IS '正文（⑦；覆盖式只留终稿 D8，中间稿靠 seed 复现）';
COMMENT ON COLUMN chapters.pace_plan IS '计划节奏（⑥a，可由挂拍均值派生，允许两位小数如 avg=0.63）';
COMMENT ON COLUMN chapters.status IS '状态：PLANNED→DRAFTED→QC_PASS→REWORK（⑥a/⑦/⑧）';
COMMENT ON COLUMN chapters.comment IS '章注（挂拍回填/字数注释；QC 用，正文剥离后扫描）';
COMMENT ON COLUMN chapters.created_time IS '创建时间';
COMMENT ON COLUMN chapters.updated_time IS '更新时间';
COMMENT ON COLUMN chapters.created_user_id IS '创建用户 → users.id';
COMMENT ON COLUMN chapters.updated_user_id IS '更新用户 → users.id';

-- ---- 系统域 3.13 qc_report ----
COMMENT ON TABLE qc_report IS '质检报告（⑧；每轮一行，verdict=PASS 才过）';
COMMENT ON COLUMN qc_report.id IS '主键';
COMMENT ON COLUMN qc_report.run_id IS '所属运行 → run.id（⑧）';
COMMENT ON COLUMN qc_report.round_no IS '轮次（1 起，与 run_id 唯一）';
COMMENT ON COLUMN qc_report.script_result IS '脚本硬判结果 jsonb（字数/挂拍/黑名单/反域/浓度/指纹 逐项）';
COMMENT ON COLUMN qc_report.llm_result IS 'LLM 快检结果 jsonb（兑现/节奏/语气/域 四项）';
COMMENT ON COLUMN qc_report.verdict IS '判定：PASS / REWORK / FAIL（⑧）';
COMMENT ON COLUMN qc_report.issues IS '问题清单 jsonb（⑧）';
COMMENT ON COLUMN qc_report.repair_log IS '修复记录 jsonb（⑧，≤2 轮；只记问题与动作不存全文）';
COMMENT ON COLUMN qc_report.created_time IS '创建时间';
COMMENT ON COLUMN qc_report.updated_time IS '更新时间';

-- ---- 系统域 3.14 calibration ----
COMMENT ON TABLE calibration IS '标定记录（⑤/⑧回流；B0 换算表每 10 轮从本表滚动回归，替代 v2 的 calibration.jsonl 文件形态）';
COMMENT ON COLUMN calibration.id IS '主键';
COMMENT ON COLUMN calibration.run_id IS '所属运行 → run.id（⑧）';
COMMENT ON COLUMN calibration.pace IS '本轮节奏锚点（⑤）';
COMMENT ON COLUMN calibration.b0 IS '采用基线（⑤5.1）';
COMMENT ON COLUMN calibration.p_pred IS '预测拍数（⑤）';
COMMENT ON COLUMN calibration.p_actual IS '实测拍数（⑧）';
COMMENT ON COLUMN calibration.words_pred IS '预测字数（⑤，clamp 到 [Nmin,Nmax] 后）';
COMMENT ON COLUMN calibration.words_actual IS '实测字数（⑧）';
COMMENT ON COLUMN calibration.chapter_avg_actual IS '章均字数实测（⑧）';
COMMENT ON COLUMN calibration.created_time IS '创建时间';
COMMENT ON COLUMN calibration.updated_time IS '更新时间';

-- ---- 系统域 3.15 work_fingerprint ----
COMMENT ON TABLE work_fingerprint IS '成品查重登记（⑧结构指纹；必须中央共享库——跨 run 全局查重的数据源，别放单书维度）';
COMMENT ON COLUMN work_fingerprint.id IS '主键';
COMMENT ON COLUMN work_fingerprint.novel_id IS '成品书 → novels.id（⑧）';
COMMENT ON COLUMN work_fingerprint.kind IS '指纹种类：PROTAGONIST_NAME=主角名 / BRIDGE=桥段 / TROUPE=套路';
COMMENT ON COLUMN work_fingerprint.value IS '登记值（如主角名"沈X"、桥段指纹"记账女主"）';
COMMENT ON COLUMN work_fingerprint.fingerprint IS '结构指纹（标签组合哈希，同构弧检测用）';
COMMENT ON COLUMN work_fingerprint.created_time IS '创建时间';
COMMENT ON COLUMN work_fingerprint.updated_time IS '更新时间';

-- ---- 视图 ----
COMMENT ON VIEW v_asset_all IS '统一素材读取视图（架构 §6.1：全素材搜索/审计/黑名单 grep 走这层；新增素材类型 = 建表 + 本视图 UNION 补一段，调用方不改）';

