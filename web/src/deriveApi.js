// 随机衍生后端请求封装：统一走 /derive 前缀
// 线上 nginx 把 /derive/ 透传给 context-path=/derive 的 8091，路径原样；
// 本地 vite 把 /derive 代理到 8091 并剥掉前缀（本地 jar 无 context-path）。
// 登录态 = HttpOnly cookie derive_token（2026-10-17 与主站 novelgen_token 分名，防同域互覆盖）。
// 未登录返回 code=A0003，由 DeriveShell 弹登录，不在这里跳主站 /login。
const OK_CODE = '00000'

async function req(method, url, body) {
  let resp
  try {
    resp = await fetch(`/derive${url}`, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
      credentials: 'same-origin'
    })
  } catch {
    // fetch 抛错=网络中断/被掐断；动作端点是同步长任务，服务端往往仍在跑
    throw new Error(method !== 'GET'
      ? '请求中断（网络或网关超时）——服务端可能仍在执行，稍后点「刷新」看结果'
      : '随机衍生后端不可达（本地需起 8091）')
  }
  let j
  try {
    j = await resp.json()
  } catch {
    const hint = resp.status === 504
      ? '——网关超时已切断响应，服务端通常仍在执行；刷新看结果，勿连点重复提交'
      : (method !== 'GET' ? '——服务端可能仍在执行，稍后点「刷新」看结果' : '')
    throw new Error(`随机衍生后端返回异常（HTTP ${resp.status}）${hint}`)
  }
  if (j.code !== OK_CODE) {
    const e = new Error(j.message || '请求失败')
    e.code = j.code
    e.detail = j.detail
    throw e
  }
  return j.data
}

export const deriveApi = {
  get: (url) => req('GET', url),
  post: (url, body) => req('POST', url, body)
}

/** 未登录/会话过期（A0003）。 */
export const NOT_LOGIN_CODE = 'A0003'
