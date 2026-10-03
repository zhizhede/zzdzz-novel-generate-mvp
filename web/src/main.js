import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 品牌样式：必须在 Element Plus 自带样式之后引入，否则默认值会盖回品牌值。
// 令牌源、映射层与完整设计契约见 web/design-system/DESIGN.md。
import './styles/tokens.css'
import './styles/element-plus.css'
import './styles/app.css'
import { ElMessage } from 'element-plus'
import App from './App.vue'
import router from './router'

const app = createApp(App)
// 任何未捕获的渲染/逻辑错误都弹提示并留痕，禁止页面静默假死。
// 带 code 的是 API 业务失败，按错误码族分话术：A 参数/状态、B 系统、C 第三方（LLM）可重试
app.config.errorHandler = (err, instance, info) => {
  console.error('[vue]', info, err)
  const family = err?.code?.[0]
  if (family === 'C') {
    ElMessage.warning((err?.message || '请求失败') + '（第三方服务繁忙，可稍后重试）')
  } else if (family === 'B') {
    ElMessage.error('系统错误：' + (err?.message || info))
  } else if (family) {
    ElMessage.error(err?.message || '请求失败')
  } else {
    ElMessage.error('页面出错：' + (err?.message || info))
  }
}
app.use(router).use(ElementPlus).mount('#app')
