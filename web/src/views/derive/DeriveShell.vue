<template>
  <div class="derive-shell">
    <el-card v-if="!authed && !checking" shadow="never" class="derive-login">
      <template #header>随机衍生 · 登录</template>
      <el-form label-width="72px" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" @keyup.enter="login" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password"
                    @keyup.enter="login" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="logging" @click="login">登录</el-button>
          <span class="hint">与主站账号相互独立（derive_gen 库）</span>
        </el-form-item>
      </el-form>
    </el-card>

    <template v-else-if="authed">
      <div class="derive-tabs">
        <div class="derive-brand">随机衍生</div>
        <el-tabs :model-value="activeTab" @tab-change="(t) => router.push(t)">
          <el-tab-pane label="任务 Run" name="/derive/runs" />
          <el-tab-pane label="书库" name="/derive/books" />
        </el-tabs>
        <div class="derive-tabs__user">
          <span class="hint">{{ username }}</span>
          <el-button size="small" link type="primary" @click="logout">退出衍生</el-button>
        </div>
      </div>
      <router-view />
    </template>

    <el-empty v-else description="正在校验衍生登录态…" :image-size="60" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { deriveApi, NOT_LOGIN_CODE } from '../../deriveApi'

/**
 * 随机衍生页组外壳：衍生登录门（derive_token，独立于主站）+ 任务/书库页签。
 * 未登录时不渲染子页，避免每个列表页各自处理 A0003。
 */
const route = useRoute()
const router = useRouter()

const authed = ref(false)
const checking = ref(true)
const logging = ref(false)
const username = ref('')
const form = ref({ username: 'admin', password: '' })

const activeTab = computed(() =>
  route.path.startsWith('/derive/books') ? '/derive/books' : '/derive/runs')

async function checkAuth() {
  checking.value = true
  try {
    const me = await deriveApi.get('/api/auth/me')
    username.value = me?.username || ''
    authed.value = true
  } catch (e) {
    authed.value = false
    if (e.code && e.code !== NOT_LOGIN_CODE) {
      ElMessage.warning(e.message)
    }
  } finally {
    checking.value = false
  }
}

async function login() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和口令')
    return
  }
  logging.value = true
  try {
    const me = await deriveApi.post('/api/auth/login', {
      username: form.value.username,
      password: form.value.password
    })
    username.value = me?.username || form.value.username
    authed.value = true
    ElMessage.success('衍生系统已登录')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    logging.value = false
  }
}

async function logout() {
  try {
    await deriveApi.post('/api/auth/logout')
  } catch { /* 已过期也当登出成功 */ }
  authed.value = false
  form.value.password = ''
}

onMounted(checkAuth)
watch(() => route.fullPath, () => { if (!authed.value) checkAuth() })
</script>

<style scoped>
.derive-login {
  max-width: 420px;
  margin: 48px auto;
}
.hint {
  margin-left: 10px;
  font-size: var(--text-xs);
  color: var(--muted);
}
.derive-tabs {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}
.derive-brand {
  flex: none;
  font-weight: 600;
  font-size: var(--text-lg);
  color: var(--fg);
}
.derive-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.derive-tabs__user {
  margin-left: auto;
  display: flex;
  align-items: center;
}
</style>
