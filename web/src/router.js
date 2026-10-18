import { createRouter, createWebHashHistory } from 'vue-router'

// 随机衍生专用前端（2026-10-17 用户定调：主站小说生成系统前端整体下线，只留 /derive 页组）。
// 登录态由 DeriveShell 的衍生登录门自理（derive_token），不再走主站 /login 与 /api/auth/me 守卫。
const routes = [
  { path: '/', redirect: '/derive/runs' },
  {
    path: '/derive',
    component: () => import('./views/derive/DeriveShell.vue'),
    children: [
      { path: '', redirect: '/derive/runs' },
      { path: 'runs', component: () => import('./views/derive/DeriveRunsView.vue') },
      { path: 'runs/:id', component: () => import('./views/derive/DeriveRunDetailView.vue') },
      { path: 'books', component: () => import('./views/derive/DeriveBooksView.vue') },
      { path: 'books/:id', component: () => import('./views/derive/DeriveBookDetailView.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/derive/runs' }
]

const router = createRouter({ history: createWebHashHistory(), routes })

export default router
