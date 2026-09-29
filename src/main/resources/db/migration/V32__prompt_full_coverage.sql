-- V32 · 提示词全量接库收尾：目录里不再有「仅浏览不接库」的骨架快照行。
-- 一、清退已从 PromptCatalog 移除的过时快照行（内容已被接线的行取代或与真实拼装不符）：
--   scene_draft/system  = style_packs 规则正文（按书落库）+ scene_draft/style_redlines 段，无独立 system 模板；
--   scene_revise/system = 复用场景包 system（同上，无独立文案）；
--   scene_draft/derive  = derive_pov/derive_density/derive_tags 三段之和（旧快照）；
--   scene_draft/redline = scene_draft/derive_redline（旧快照）。
UPDATE prompt_templates SET is_deleted = TRUE, delete_time = NOW()
WHERE is_deleted = FALSE AND custom = FALSE
  AND (node, phase) IN (
      ('scene_draft', 'system'),
      ('scene_revise', 'system'),
      ('scene_draft', 'derive'),
      ('scene_draft', 'redline')
  );

-- 二、模型路由页补齐缺失节点行（行清单以 LlmNode 为准；留空 = 走调用方/全局默认，初始不填模型）。
INSERT INTO llm_node_config (node, remark)
SELECT v.node, v.remark
FROM (VALUES
    ('reader_review',  '判定：读者评审（反无聊闸门，建议保持默认）'),
    ('reader_fix',     '修订：读者评审 BLOCKER 重写/恢复扩写（可路由快模型）'),
    ('volume_review',  '分析：卷级复盘（漂移分析）'),
    ('embedding',      '系统：RAG 语义检索向量化（embo-01）'),
    ('sample_chapter', '抽取：样本逐章解析（摘要/节拍/实体）'),
    ('sample_volume',  '抽取：样本卷级汇总（arc/节奏）'),
    ('sample_outline', '创作：样本全书大纲合成'),
    ('sample_world',   '创作：样本世界观文档合成'),
    ('sample_merge',   '抽取：样本跨章实体名归并判定'),
    ('sample_params',  '判定：衍生参数 AI 推荐（开书向导）'),
    ('sample_tags',    '抽取：样本类型/特征标签提取'),
    ('derive_outline', '创作：开书向导·AI 全书大纲草稿')
) AS v(node, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM llm_node_config c WHERE c.node = v.node AND c.is_deleted = FALSE
);
