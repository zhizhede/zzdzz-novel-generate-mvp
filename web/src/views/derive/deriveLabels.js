// 随机衍生页组的状态/类型标签与格式化（口径与后端 DO 注释一致）
export const RUN_STATUS = {
  SAMPLING: { label: '采样中', type: 'info' },
  PLANNING: { label: '规划中', type: 'primary' },
  DRAFTING: { label: '写作中', type: 'warning' },
  QC: { label: '质检中', type: 'warning' },
  PASSED: { label: '已通过', type: 'success' },
  REWORK: { label: '回炉', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' }
}

export const CHAPTER_STATUS = {
  PLANNED: { label: '已计划', type: 'info' },
  DRAFTED: { label: '已成稿', type: 'warning' },
  QC_PASS: { label: '质检通过', type: 'success' },
  REWORK: { label: '回炉', type: 'danger' }
}

export const BEAT_STATUS = {
  PLANNED: { label: '已计划', type: 'info' },
  WRITTEN: { label: '已写入', type: 'primary' },
  VERIFIED: { label: '已核销', type: 'success' },
  SKIPPED: { label: '已跳过', type: 'info' }
}

export const VERDICT = {
  PASS: { label: '通过', type: 'success' },
  REWORK: { label: '回炉', type: 'warning' },
  FAIL: { label: '熔断', type: 'danger' }
}

export const BOOK_SOURCE = {
  SOURCE: { label: '源书', type: 'info' },
  GENERATED: { label: '系统生成', type: 'success' }
}

export function fmtTime(s) {
  return s ? String(s).replace('T', ' ').slice(0, 16) : '—'
}

export function statusTag(map, key) {
  return map[key] || { label: key || '—', type: 'info' }
}
