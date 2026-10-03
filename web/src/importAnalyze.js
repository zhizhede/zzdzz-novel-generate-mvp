/**
 * 导入书籍「解析链」的步骤清单（前端单源一份，与后端 ImportAnalyzeStep 枚举逐项对齐）：
 * key = 后端 wire 值；label/hint 只用于勾选界面与进度标题。
 * 顺序即执行顺序（也是依赖顺序）：事实账 → 大纲 → 素材卡 → 世界观 → 文风规则 → 向量索引 → 章纲反推。
 *
 * **只解析，不规划**：本链不产生新章、不规划续写卷——导入书的解析不该顺手规划出一卷续写，
 * 规划走规划页（「AI 规划下一卷」）与生成管线，是另一个入口、另一个决定。链里的章纲步是**反推**
 * （读已有正文拆场景），不是给未写的章做规划。
 *
 * existing：该步遇到「已有内容」时的处置口径。
 *   '可选'   = 界面给「跳过已有 / 覆盖重做」开关（默认覆盖＝不跳过），提交时进 skipExistingSteps 的是选了跳过的那些；
 *   其余字符串 = 固定口径，界面上只做说明（不给开关，避免假装能选）。
 */
export const ANALYZE_STEPS = [
  {
    key: 'DIGESTS',
    label: '事实账 + 世界状态 + 伏笔提议',
    hint: '逐章 LLM（取末尾 20 章）——续写前情链的唯一来源；覆盖＝这些章重算并原地更新事实账、重写世界状态快照',
    existing: '可选'
  },
  { key: 'OUTLINE', label: '全书大纲', hint: '依据章节结构与事实账合成，写 canon「大纲」', existing: '固定覆盖' },
  {
    key: 'CARDS',
    label: '素材卡（角色/物品/地点…）',
    hint: '抽设定层实体卡写素材库；覆盖＝用新结果更新已有卡（保留人工钉住与状态），跳过＝保留人工写过的卡',
    existing: '可选'
  },
  { key: 'WORLD', label: '世界观文档', hint: '合成世界观写 canon「世界观」', existing: '固定覆盖' },
  { key: 'RULES', label: '文风规则（写回风格包）', hint: '按本书语料提炼规则，写回风格包 rules_md——场景生成直接采用', existing: '固定覆盖' },
  {
    key: 'EMBEDDINGS',
    label: '向量索引（RAG 召回前置）',
    hint: '事实账与素材卡向量化；覆盖＝清掉本书旧向量全量重嵌（事实账被重算后旧向量即陈旧，不刷新会被召回）',
    existing: '可选'
  },
  {
    key: 'DERIVE_CHAPTER_OUTLINES',
    label: '章纲（从正文反推场景拆解）',
    hint: '读已有正文，把成稿章按实际分场拆出来写章纲与场景——事后描述，正文/章状态/门禁报告都不动；'
      + '约 0.5 分钟/章（默认最多前 30 章）。覆盖＝重写已有章纲，跳过＝只补没有章纲的章',
    existing: '可选'
  }
]

/** 该步是否给「跳过已有 / 覆盖重做」开关。 */
export function isToggleable (key) {
  return (ANALYZE_STEPS.find((s) => s.key === key) || {}).existing === '可选'
}

/** 该步「已有内容」的固定口径说明（没有开关时展示）。 */
export function existingPolicy (key) {
  const s = ANALYZE_STEPS.find((x) => x.key === key)
  return s && s.existing !== '可选' ? s.existing : ''
}

/** 默认全勾（用户可逐项取消）。 */
export function allStepKeys () {
  return ANALYZE_STEPS.map((s) => s.key)
}

/** wire 值 → 中文名（进度面板标题用；后端也会回 label，这里只作兜底）。 */
export function stepLabel (key) {
  return (ANALYZE_STEPS.find((s) => s.key === key) || {}).label || key
}
