<template>
  <el-container v-if="isLogin" style="height: 100vh">
    <el-main><router-view /></el-main>
  </el-container>
  <el-container v-else style="height: 100vh">
    <el-aside width="180px" style="border-right: 1px solid #eee">
      <div style="padding: 18px 16px; font-weight: bold">小说生成平台</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/">工作台</el-menu-item>
        <el-menu-item index="/chapters">章节</el-menu-item>
        <el-menu-item index="/library">素材库</el-menu-item>
        <el-menu-item index="/logs">调用台账</el-menu-item>
      </el-menu>
      <div style="position: absolute; bottom: 16px; padding: 0 16px">
        <el-button size="small" @click="logout">退出</el-button>
      </div>
    </el-aside>
    <el-main><router-view /></el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const isLogin = computed(() => route.path === '/login')

async function logout() {
  await fetch('/api/auth/logout', { method: 'POST' })
  router.push('/login')
}
</script>
