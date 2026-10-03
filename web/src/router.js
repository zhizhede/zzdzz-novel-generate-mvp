import { createRouter, createWebHashHistory } from 'vue-router'
import { api } from './api'

const routes = [
  { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
  { path: '/', component: () => import('./views/WorkbenchView.vue') },
  { path: '/wizard', component: () => import('./views/WizardView.vue') },
  { path: '/books', component: () => import('./views/BooksView.vue') },
  { path: '/chapters', component: () => import('./views/ChaptersView.vue') },
  { path: '/planning', component: () => import('./views/PlanningView.vue') },
  { path: '/logs', component: () => import('./views/LogsView.vue') },
  { path: '/library', component: () => import('./views/LibraryView.vue') },
  { path: '/fingerprints', component: () => import('./views/FingerprintView.vue') },
  { path: '/plans', component: () => import('./views/PlanAssetView.vue') }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach(async (to) => {
  if (to.matched.length === 0) return '/'
  if (to.meta.public) return true
  try {
    await api.get('/api/auth/me')
    return true
  } catch {
    return '/login'
  }
})

export default router
