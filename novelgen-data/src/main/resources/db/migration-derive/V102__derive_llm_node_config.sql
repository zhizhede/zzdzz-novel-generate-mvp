-- V102 · 照搬 llm_node_config（原 V12 逐字拷贝 + 节点种子行）
-- 照搬理由：MiniMaxClient 每次 chat 按节点路由读本表（配置查询失败不拦截调用，但空表比缺表干净）；
-- derive 的节点行后续按需 INSERT，种子行是 zzdzz 节点（本库不用，留着无害、删了会与照搬口径漂移）。
-- V104 预留：启用库内提示词编辑时照搬 V18 prompt_templates（架构附录 A-6 触发条件）。

-- V12 · 按节点模型路由：node 口径的模型/参数覆盖（平台级，所有作品共用）。
-- model/temperature/max_tokens/extra_json 任一留空 = 该项走调用方默认；model 留空即用全局默认
-- （application.yaml novelgen.llm.model）。enabled=false 整行忽略。初始只建行不填模型（路由行留空）。
CREATE TABLE llm_node_config (
    id          BIGSERIAL PRIMARY KEY,
    node        VARCHAR(64) NOT NULL,
    model       VARCHAR(64),
    temperature DOUBLE PRECISION,
    max_tokens  INT,
    extra_json  JSONB,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    remark      VARCHAR(256),
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_llm_node_config_node ON llm_node_config(node) WHERE is_deleted = FALSE;

INSERT INTO llm_node_config (node, remark) VALUES
    ('volume_plan',        '创作：整卷卷纲规划（质量生命线，建议保持默认）'),
    ('volume_plan_review', '判定：卷纲 AI 审校（抓连续性硬伤，建议保持默认）'),
    ('chapter_replan',     '创作：单章卷纲重写/失败自愈'),
    ('outline',            '创作：章纲场景拆解'),
    ('scene_draft',        '创作：场景正文生成（正文质量主战场，建议保持默认）'),
    ('chapter_revise',     '机械：章级门禁修订（最贵最慢，路由快模型首选）'),
    ('scene_revise',       '机械：场景门禁带意见重写（可路由快模型）'),
    ('ai_review_revise',   '机械：语义审校 BLOCKER 修订（可路由快模型）'),
    ('ai_review',          '判定：语义审校（漏字/连续性把关，建议保持默认）'),
    ('digest',             '机械：事实账/世界状态/伏笔提议（喂记忆链，降级需观察质量）'),
    ('world_state',        '机械：世界状态抽取回填（可路由快模型）'),
    ('smoke',              '系统：连通性冒烟');
