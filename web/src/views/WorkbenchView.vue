<template>
  <div>
    <h3>工作台</h3>

    <el-card shadow="never" style="margin-bottom: 12px">
      <div style="display: flex; gap: 16px; align-items: center; flex-wrap: wrap">
        <el-select v-model="novelId" style="width: 240px" @change="onNovelChange">
          <el-option v-for="n in novels" :key="n.id" :value="n.id" :label="n.title" />
        </el-select>
        <span>共 {{ novel?.chapterCount || 0 }} 章</span>
        <span>审批模式：
          <el-switch v-model="manual" active-text="人工" inactive-text="自动" @change="switchMode" />
        </span>
        <el-divider direction="vertical" />
        <span>连跑范围：第 <el-input-number v-model="from" :min="1" size="small" /> 至
          <el-input-number v-model="to" :min="from" size="small" /> 章</span>
        <el-button type="primary" size="small" :loading="running" @click="run">启动生成</el-button>
        <el-tag :type="running ? 'warning' : 'info'" size="small">{{ running ? '运行中' : '空闲' }}</el-tag>
        <span style="color:#999;font-size:12px">{{ lastMessage }}</span>
        <router-link to="/chapters" style="font-size: 12px; margin-left: auto">去章节页阅读 →</router-link>
      </div>
    </el-card>

    <el-row :gutter="12">
      <el-col :span="10">
        <el-card shadow="never" header="生成进程（实时）">
          <div ref="logBox" style="height: 560px; overflow-y: auto; font-family: monospace; font-size: 12px; line-height: 1.9">
            <div v-for="(l, i) in logs" :key="i" :style="{ color: l.color }">{{ l.text }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never" header="生成输出（场景流式块）">
          <div style="height: 560px; overflow-y: auto">
            <div v-for="s in scenes" :key="s.key" style="margin-bottom: 16px">
              <div style="color:#999;font-size:12px;margin-bottom:4px">{{ s.title }}（{{ s.phase === 'reused' ? '复用缓存' : '新生成' }}）</div>
              <div style="white-space: pre-wrap; border-left: 3px solid #ddd; padding-left: 10px">{{ s.text }}</div>
            </div>
            <el-empty v-if="!scenes.length" description="启动生成后在此实时显示场景文本" :image-size="60" />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'

const novel = ref(null)
const novels = ref([])
const novelId = ref(null)
const manual = ref(false)
const from = ref(2)
const to = ref(2)
const running = ref(false)
const lastMessage = ref('')
const logs = ref([])
const scenes = ref([])
const logBox = ref(null)
let es = null
let poll = null

const COLORS = {
  run: '#909399', chapter: '#409eff', outline: '#67c23a', scene: '#303133',
  gate: '#e6a23c', assemble: '#909399', chapter_gate: '#67c23a',
  revise: '#e6a23c', review: '#e6a23c', digest: '#67c23a', approve: '#e6a23c'
}

function log(event, data) {
  const d = typeof data === 'string' ? JSON.parse(data) : data
  let text = `[${event}] `
  if (d.chapterNo !== undefined) text += `第${d.chapterNo}章 `
  if (event === 'run') text += d.phase === 'start' ? `连跑 ${d.from}-${d.to} 开始` : `连跑结束（通过 ${d.passed ?? 0} 章）${d.message || ''}`
  else if (event === 'chapter') text += d.phase === 'start' ? `《${d.title}》开始` : d.phase === 'done' ? `完成（${d.chars} 字符）` : `失败：${d.reason}`
  else if (event === 'outline') text += `章纲 ${d.phase}${d.sceneCount ? '，' + d.sceneCount + ' 个场景' : ''}`
  else if (event === 'scene') text += `场景 ${d.sceneNo} ${d.phase}`
  else if (event === 'gate') text += `场景 ${d.sceneNo} 门禁${d.passed ? '通过' : '未过' + (d.rewrite ? '，重写中' : '')}`
  else if (event === 'assemble') text += `拼章完成（${d.chars} 字符），章级门禁检测中`
  else if (event === 'chapter_gate') text += `章级门禁${d.passed ? '通过' : '未过'}`
  else if (event === 'revise') text += `修订轮 ${d.phase}${d.chars ? '（' + d.chars + ' 字符）' : ''}`
  else if (event === 'review') {
    if (d.phase === 'start') text += 'AI 语义审校中'
    else if (d.phase === 'done') text += `审校完成：${d.verdict}${d.blocked ? '，转人工审批' : ''}`
    else text += `审校异常，跳过（${d.message || 'fail-open'}）`
  }
  else if (event === 'digest') text += `事实账落库`
  else if (event === 'approve') text += `待人工审批${d.reason === 'review_blocker' ? '（审校硬伤未清）' : ''}`
  logs.value.push({ text, color: COLORS[event] || '#303133' })
  scrollLog()
  if (event === 'run') running.value = d.phase === 'start'
  if (event === 'scene' && (d.phase === 'draft' || d.phase === 'reused')) pushScene(d)
}

function pushScene(d) {
  const key = `${d.chapterNo}-${d.sceneNo}`
  const found = scenes.value.find((s) => s.key === key)
  const item = { key, title: `第${d.chapterNo}章 场景${d.sceneNo}`, phase: d.phase, text: d.text }
  if (found) Object.assign(found, item)
  else scenes.value.push(item)
}

function scrollLog() {
  nextTick(() => { if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight })
}

function connect() {
  es = new EventSource('/api/pipeline/stream')
  for (const ev of ['run', 'chapter', 'outline', 'scene', 'gate', 'assemble', 'chapter_gate', 'revise', 'review', 'digest', 'approve']) {
    es.addEventListener(ev, (e) => log(ev, e.data))
  }
  es.onerror = () => { /* 断线后 EventSource 自动重连 */ }
}

async function switchMode() {
  try {
    await api.put(`/api/novels/${novel.value.id}/approval-mode`, { mode: manual.value ? 'manual' : 'auto' })
  } catch (e) {
    ElMessage.error(e.message)
    manual.value = !manual.value
  }
}

async function run() {
  try {
    await api.post('/api/pipeline/run', { novel: novel.value.title, from: from.value, to: to.value })
    scenes.value = []
    ElMessage.success('已启动，实时进度见下方')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function pollStatus() {
  try {
    const s = await api.get('/api/pipeline/status')
    running.value = s.running
    lastMessage.value = s.lastMessage
  } catch { /* 忽略轮询错误 */ }
}

function onNovelChange() {
  novel.value = novels.value.find((n) => n.id === novelId.value) || null
  setSelectedNovelId(novelId.value)
  manual.value = novel.value?.approvalMode === 'manual'
}

onMounted(async () => {
  novels.value = await api.get('/api/novels')
  novelId.value = getSelectedNovelId() ?? novels.value[0]?.id
  if (!novels.value.some((n) => n.id === novelId.value)) novelId.value = novels.value[0]?.id
  onNovelChange()
  connect()
  pollStatus()
  poll = setInterval(pollStatus, 3000)
})

onUnmounted(() => {
  if (es) es.close()
  if (poll) clearInterval(poll)
})
</script>
