// 跨页共享的作品选择（localStorage 持久化）
const KEY = 'selectedNovelId'

export function getSelectedNovelId() {
  const v = localStorage.getItem(KEY)
  return v ? Number(v) : null
}

export function setSelectedNovelId(id) {
  if (id != null) localStorage.setItem(KEY, String(id))
}
