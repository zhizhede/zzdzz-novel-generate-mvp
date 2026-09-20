/** LLM 节点/门禁/步骤中文名映射（与后端 LlmNode 注册表、Step 枚举、gate_type 对应；两处视图共用）。 */
export const NODE_LABEL = {
  outline: 'AI 章纲', scene_draft: '场景生成', scene_revise: '场景重写', chapter_revise: '章级修订',
  reader_review: '读者评审', reader_fix: '读者评审修复', ai_review: 'AI 审校', ai_review_revise: '审校修复',
  digest: '总结（digest）', world_state: '世界状态回填', volume_plan: '卷纲规划', volume_plan_review: '卷纲审校',
  volume_review: '卷级复盘', chapter_replan: '卷纲重写（换目标）', embedding: '向量化', smoke: '连通冒烟',
}

export const GATE_LABEL = { mechanical: '机械门禁', ai_review: 'AI 审校', reader_review: '读者评审' }

export const STEP_LABEL = {
  OUTLINE: 'AI 章纲', SCENE: '场景生成+门禁', ASSEMBLE: '拼章+章级门禁+修订',
  READER: '读者评审', AI_REVIEW: 'AI 语义审校', APPROVE: '审批', DIGEST: '事实账',
}
