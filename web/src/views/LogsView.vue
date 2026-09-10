<template>
  <div>
    <h3>调用台账</h3>
    <el-card shadow="never" style="margin-bottom: 12px">
      <div style="display: flex; gap: 24px; font-size: 14px">
        <span>调用：<b>{{ totals.calls }}</b> 次</span>
        <span>Prompt：<b>{{ totals.promptTokens }}</b></span>
        <span>Completion：<b>{{ totals.completionTokens }}</b></span>
        <span>总 tokens：<b>{{ totals.totalTokens }}</b></span>
        <span>平均耗时：<b>{{ totals.avgLatencyMs }}ms</b></span>
      </div>
    </el-card>

    <el-table :data="items" border size="small" @row-click="open">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="node" label="节点" width="140" />
      <el-table-column prop="chapterId" label="章ID" width="70" />
      <el-table-column prop="model" label="模型" width="130" />
      <el-table-column prop="promptTokens" label="Prompt" width="90" />
      <el-table-column prop="completionTokens" label="Completion" width="110" />
      <el-table-column prop="totalTokens" label="总tokens" width="90" />
      <el-table-column prop="latencyMs" label="耗时ms" width="90" />
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ok' ? 'success' : 'danger'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reasoningChars" label="think字符" width="90" />
    </el-table>
    <el-pagination style="margin-top: 10px" layout="prev, pager, next" :total="total"
      :page-size="size" v-model:current-page="page" @current-change="load" />

    <el-drawer v-model="drawer" :title="detail ? `#${detail.id} ${detail.node}` : ''" size="55%">
      <template v-if="detail">
        <div style="font-size: 12px; color: #999; margin-bottom: 8px">
          {{ detail.model }}｜prompt {{ detail.promptTokens }} + completion {{ detail.completionTokens }} = {{ detail.totalTokens }} tokens｜{{ detail.latencyMs }}ms｜{{ detail.status }}
        </div>
        <el-collapse>
          <el-collapse-item title="AI 思考过程（think）">
            <div style="white-space: pre-wrap; font-size: 12px; color: #666; max-height: 400px; overflow-y: auto">{{ detail.reasoningText || '（无）' }}</div>
          </el-collapse-item>
        </el-collapse>
        <div style="margin-top: 10px; font-weight: bold">输出正文</div>
        <div style="white-space: pre-wrap; line-height: 1.8; max-height: 480px; overflow-y: auto; border: 1px solid #eee; padding: 10px">{{ detail.content || detail.errorMsg || '（无）' }}</div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const totals = ref({})
const detail = ref(null)
const drawer = ref(false)

async function load() {
  const p = await api.get(`/api/llm-logs?page=${page.value}&size=${size}`)
  items.value = p.items
  total.value = p.total
  totals.value = await api.get('/api/llm-logs/totals')
}

async function open(row) {
  detail.value = await api.get(`/api/llm-logs/${row.id}`)
  drawer.value = true
}

onMounted(load)
</script>
