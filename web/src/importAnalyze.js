/**
 * 导入书籍「解析链」的步骤清单（前端单选一份，与后端 ImportAnalyzeStep 枚举逐项对齐）：
 * key = 后端 wire 值；label/hint 只用于勾选界面与进度标题。
 * 顺序即执行顺序（也是依赖顺序）：事实账 → 大纲 → 素材卡 → 世界观 → 文风规则 → 向量索引 → 卷纲 → 章纲。
 */
export const ANALYZE_STEPS = [
  { key: 'DIGESTS', label: '事实账 + 世界状态 + 伏笔提议', hint: '逐章 LLM（默认取末尾 20 章，已有事实账的章自动跳过）——续写前情链的唯一来源' },
  { key: 'OUTLINE', label: '全书大纲', hint: '依据章节结构与事实账合成，写 canon「大纲」（已写过则覆盖）' },
  { key: 'CARDS', label: '素材卡（角色/物品/地点…）', hint: '抽设定层实体卡写素材库；已有同名同类卡不覆盖，只补新的' },
  { key: 'WORLD', label: '世界观文档', hint: '合成世界观写 canon「世界观」（已写过则覆盖）' },
  { key: 'RULES', label: '文风规则（写回风格包）', hint: '按本书语料提炼规则，写回风格包 rules_md——场景生成直接采用' },
  { key: 'EMBEDDINGS', label: '向量索引（RAG 召回前置）', hint: '事实账与素材卡向量化；不开则场景生成召回不到本书内容' },
  { key: 'VOLUME_PLAN', label: '卷纲（规划下一卷）', hint: '接在已有正文之后规划一卷（含前置卷复盘），auto 模式直接落库、manual 出草稿；约 2-10 分钟' },
  { key: 'CHAPTER_OUTLINES', label: '章纲（批量入队）', hint: '把新规划那卷的章纲提交给生成队列（本链只负责入队，进度看工作台）' }
]

/** 默认全勾（用户可逐项取消）。 */
export function allStepKeys () {
  return ANALYZE_STEPS.map((s) => s.key)
}

/** wire 值 → 中文名（进度面板标题用；后端也会回 label，这里只作兜底）。 */
export function stepLabel (key) {
  return (ANALYZE_STEPS.find((s) => s.key === key) || {}).label || key
}
