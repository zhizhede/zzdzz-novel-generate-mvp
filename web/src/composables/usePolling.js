import { onScopeDispose, ref } from 'vue'

/**
 * 通用轻量轮询：只负责定时拉取、串行化和错误暴露，不承载业务语义。
 * 轮询失败时保留最后一次成功数据，同时把错误暴露给页面显示「进度连接中断」。
 */
export function usePolling(fetcher, options = {}) {
  const intervalMs = options.intervalMs || 2500
  const data = ref(null)
  const error = ref(null)
  let timer = null
  let inFlight = false
  let disposed = false

  async function poll() {
    if (disposed || inFlight) return
    inFlight = true
    try {
      data.value = await fetcher()
      error.value = null
    } catch (e) {
      error.value = e || new Error('轮询失败')
    } finally {
      inFlight = false
    }
  }

  function start() {
    stop()
    timer = setInterval(poll, intervalMs)
  }

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  onScopeDispose(() => {
    disposed = true
    stop()
  })

  return { data, error, poll, start, stop }
}