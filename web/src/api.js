// 统一请求封装：Result{code,message,data,detail}（code='00000' 为成功；detail 为失败结构化明细）
// 失败抛出的 Error 附 code/detail 供界面程序化判断；HTTP 401 跳登录
const OK_CODE = '00000'

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
    const e = new Error(j.message || '请求失败')
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
