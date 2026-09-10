<template>
  <div style="max-width: 360px; margin: 120px auto">
    <h2>小说生成平台</h2>
    <el-form @submit.prevent>
      <el-form-item><el-input v-model="username" placeholder="用户名" /></el-form-item>
      <el-form-item><el-input v-model="password" type="password" placeholder="密码" show-password @keyup.enter="login" /></el-form-item>
      <el-button type="primary" style="width: 100%" :loading="loading" @click="login">登录</el-button>
    </el-form>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const username = ref('admin')
const password = ref('')
const loading = ref(false)
const router = useRouter()

async function login() {
  loading.value = true
  try {
    await api.post('/api/auth/login', { username: username.value, password: password.value })
    router.push('/')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>
