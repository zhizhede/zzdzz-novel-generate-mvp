-- V116: 模型思考开关（节点级，2026-10-16 用户定调）
-- 机制：llm_node_config.extra_json 合并进请求体（MiniMaxClient.buildBody），mimo-v2.6-flash 实测
--   {"enable_thinking":false} 生效（思考 99→4 字、延迟 2.3s→0.8s，ping id 589/596 对照）。
-- 口径：**思考默认开启**（全 derive 节点 true）；**每章正文生成 derive_write 默认关闭**（reasoning 是
--   ⑦ 耗时大头）。每阶段可选：UPDATE llm_node_config SET extra_json='{"enable_thinking":false|true}'
--   WHERE node='...'——resolver 每次调用现读，**改库即生效无需重启**。
-- 留档：请求体（含 enable_thinking）全量落 llm_call_log.request_json；思考文本落 reasoning_text。
INSERT INTO llm_node_config (node, extra_json, remark) VALUES
    ('derive_ping',            '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_extract',         '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_deindex',         '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_source_words',    '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_label',           '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_domain',          '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_category_terms',  '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_style',           '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_intake_review',   '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_tag_norm',        '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_plan',            '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_draft',           '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效'),
    ('derive_write',           '{"enable_thinking":false}', '思考开关：正文生成默认关（2026-10-16 用户定调；reasoning 是⑦耗时大头，要开改 extra_json 为 true）'),
    ('derive_qc',              '{"enable_thinking":true}',  '思考开关：true=开（默认）/false=关，改 extra_json 即生效')
ON CONFLICT (node) WHERE is_deleted = FALSE
DO UPDATE SET extra_json = EXCLUDED.extra_json,
              remark     = EXCLUDED.remark,
              update_time = NOW();

COMMENT ON COLUMN llm_node_config.extra_json IS '节点级请求扩展体（合并进 chat/completions body）；思考开关：enable_thinking（mimo-v2.6-flash 实测有效）——true=开（derive 默认）/false=关（derive_write 默认）；改库即生效无需重启；请求全文落 llm_call_log.request_json 留档';
