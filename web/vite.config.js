import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8090', changeOrigin: true },
      // 随机衍生后端（本地 8091 无 context-path；线上 nginx 挂 /derive 原样透传，前端统一走 /derive 前缀）
      '/derive': { target: 'http://localhost:8091', changeOrigin: true, rewrite: (p) => p.replace(/^\/derive/, '') }
    }
  }
})
