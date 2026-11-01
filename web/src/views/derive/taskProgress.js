function seconds(ms) {
  return Math.round((ms || 0) / 1000)
}

function elapsed(ms) {
  const total = seconds(ms)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function pollErrorText(error, label) {
  if (!error) return ''
  const message = error.message || String(error)
  return `${label}进度连接中断：${message}——保留最后一次进度，正在重试`
}

/** ⑦ 服务端连跑状态 → 统一展示文案。 */
export function formatWriteProgress(w, pollError) {
  const disconnected = pollErrorText(pollError, '服务端连跑')
  if (disconnected) return disconnected
  if (!w) return ''
  if (w.lastError) {
    return `服务端连跑已停：${w.lastError}（修完可再点「连续写完」续跑）`
  }
  if (w.stopRequested && w.running) {
    return `服务端连跑停止中：当前章写完后收尾——已写 ${w.chaptersDone} 章`
  }
  if (w.stopRequested && !w.running) {
    return `服务端连跑已停止：共写 ${w.chaptersDone} 章，断点保留，可再点「连续写完」续跑`
  }
  if (w.allDone) {
    return '服务端连跑完成：全部正文已写完，run 已转质检 ⑧'
  }
  if (!w.running) return ''
  const chapter = w.lastChapterNo
    ? `已写 ${w.chaptersDone} 章（第 ${w.lastChapterNo} 章 ${seconds(w.lastChapterMs)}s）`
    : `起步中（已写 ${w.chaptersDone} 章）`
  const phase = w.phase ? ` · ${w.phase}` : ''
  return `服务端连跑中：${chapter}${phase} · 总耗时 ${elapsed(w.elapsedMs)} —— 关页面不断`
}

/** 全流程无人值守状态 → 统一展示文案。 */
export function formatAutoProgress(a, pollError) {
  const disconnected = pollErrorText(pollError, '全流程')
  if (disconnected) return disconnected
  if (!a) return ''
  const mode = a.direct ? '全程直通（初稿）' : '全流程无人值守'
  if (a.lastError) {
    return `${mode}已停：${a.lastError}（断点保留，再点按钮续跑）`
  }
  if (a.stopRequested && a.running) {
    return `${mode}停止中：当前步完成后收尾 · ${a.stage}`
  }
  if (a.stopRequested && !a.running) {
    return `${mode}已停止：${a.stage || '断点保留，再点按钮续跑'}`
  }
  if (!a.running && a.done) {
    return `${mode}完成：${a.stage} · 共 ${a.steps} 步`
  }
  if (!a.running) return ''
  return `${mode}中：${a.stage} · ${a.steps} 步 · 已耗时 ${elapsed(a.elapsedMs)} —— 关页面不断`
}
