import { reactive, watch } from 'vue'

/**
 * 阅读偏好：字号 / 行高 / 版心 / 字体 / 主题，localStorage 持久化。
 *
 * 抽成独立模块而不是塞在 ReadingPane 里，是因为会话视图的正文块也要吃同一份
 * 偏好（那是另一处长时间盯正文的地方），两边共享一个 reactive 单例即可，
 * 不需要逐层传 props。
 *
 * 坏值/缺字段一律回退默认，不让一份坏 JSON 把阅读面卡死。
 */
const KEY = 'novelgen.reading'

const DEFAULTS = {
  size: 18,        // 正文字号 px
  leading: 2.0,    // 行高（中文长文需要比界面松）
  measure: 46,     // 版心，单位＝全角字数（渲染为 em，随字号缩放）
  serif: true,     // true＝衬线（--font-display），false＝无衬线（--font-body）
  theme: 'light'   // light | sepia | dark
}

export const SIZE_STEPS = [16, 17, 18, 20, 22]
export const LEADING_STEPS = [1.6, 1.8, 2.0, 2.2]
export const MEASURE_STEPS = [32, 38, 46, 52]
export const THEMES = [
  { value: 'light', label: '浅色' },
  { value: 'sepia', label: '护眼' },
  { value: 'dark', label: '深色' }
]

function load() {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return { ...DEFAULTS }
    const saved = JSON.parse(raw)
    const merged = { ...DEFAULTS, ...saved }
    if (!SIZE_STEPS.includes(merged.size)) merged.size = DEFAULTS.size
    if (!LEADING_STEPS.includes(Number(merged.leading))) merged.leading = DEFAULTS.leading
    if (!MEASURE_STEPS.includes(merged.measure)) merged.measure = DEFAULTS.measure
    if (!THEMES.some((t) => t.value === merged.theme)) merged.theme = DEFAULTS.theme
    merged.serif = merged.serif !== false
    return merged
  } catch {
    return { ...DEFAULTS }
  }
}

export const readingSettings = reactive(load())

watch(readingSettings, (v) => {
  try { localStorage.setItem(KEY, JSON.stringify({ ...v })) } catch { /* 隐私模式等：忽略，只是不持久化 */ }
}, { deep: true })

/** 正文按换行切段，去掉原有的行首空白（缩进由排版提供，避免双重缩进）。 */
export function toParagraphs(text) {
  return String(text || '')
    .split('\n')
    .map((s) => s.replace(/^[\s\u3000]+/, '').trimEnd())
    .filter((s) => s.length)
}
