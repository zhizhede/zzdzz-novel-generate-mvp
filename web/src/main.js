import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { ElMessage } from 'element-plus'
import App from './App.vue'
import router from './router'

const app = createApp(App)
// 任何未捕获的渲染/逻辑错误都弹提示并留痕，禁止页面静默假死
app.config.errorHandler = (err, instance, info) => {
  console.error('[vue]', info, err)
  ElMessage.error('页面出错：' + (err?.message || info))
}
app.use(router).use(ElementPlus).mount('#app')
