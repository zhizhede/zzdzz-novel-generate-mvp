// 统一请求封装：Result{code,message,data,detail}（code='00000' 为成功；detail 为失败结构化明细）
// 失败抛出的 Error 附 code/detail 供界面程序化判断；HTTP 401 跳登录
const OK_CODE = '00000'

// 错误码人话层：用户看得懂的下一步指引，全局注入所有 ElMessage.error(e.message)
const CODE_HINTS = {
  A0006: '当前状态不允许这个操作——页面数据可能旧了，刷新看最新状态（章节可能已进入下一阶段）',
  A0007: '同一本书同时只跑一个生成任务：看队列里运行中的任务，等它完成或先停掉',
  A0008: '操作太快了，稍等几秒再试',
  B0001: '系统内部出错：稍后重试；持续出现请反馈并附「调用台账」截图',
  B0002: '服务端缺配置（如 API key）：检查 application-local.yaml',
  C0002: 'AI 服务波动：稍等重试；持续失败看「调用台账」页的错误详情',
  C0003: 'AI 连续多次输出不合格：重试一次通常能过；仍失败建议缩小章节范围重跑',
  D0001: '数据库出错：稍后重试；持续出现请反馈'
}

async function req(method, url, body) {
  const resp = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
    credentials: 'same-origin'
  })
  if (resp.status === 401 && !url.includes('/auth/login')) {
    location.hash = '#/login'
    throw new Error('未登录')
  }
  const j = await resp.json()
  if (j.code !== OK_CODE) {
    const hint = CODE_HINTS[j.code]
    const e = new Error(hint ? `${j.message}。${hint}` : (j.message || '请求失败'))
    e.code = j.code
    e.detail = j.detail
    throw e
  }
  return j.data
}

export const api = {
  get: (url) => req('GET', url),
  post: (url, body) => req('POST', url, body),
  put: (url, body) => req('PUT', url, body),
  delete: (url) => req('DELETE', url)
}
