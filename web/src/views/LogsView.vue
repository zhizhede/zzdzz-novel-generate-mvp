<template>
  <div>
    <h3>日志</h3>
    <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
      <span style="font-size: 13px; color: #666">筛选：</span>
      <el-select v-model="novelId" placeholder="全部作品" clearable size="small" style="width: 220px" @change="loadAll">
        <el-option v-for="n in novels" :key="n.id" :value="n.id" :label="n.title" />
      </el-select>
      <template v-if="tab === 'calls'">
        <el-input-number v-model="chapterId" :min="1" size="small" placeholder="章ID" controls-position="right" style="width: 130px" />
      </template>
      <template v-else>
        <el-input-number v-model="eventChapterNo" :min="1" size="small" placeholder="章号(可选)" controls-position="right" style="width: 140px" />
      </template>
      <el-button size="small" @click="loadAll">查询</el-button>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="调用台账" name="calls">
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
      </el-tab-pane>

      <el-tab-pane label="事件流水" name="events">
        <div style="font-size: 12px; color: #999; margin-bottom: 10px">
          生成履历回放：每次门禁失败原因、重写/修订轮次、场景中途草稿都在此可追溯；点开单条可看原始负载。
        </div>
        <el-empty v-if="!events.length" description="暂无事件（新跑的章节会自动记录）" />
        <el-timeline v-else>
          <el-timeline-item v-for="e in events" :key="e.id" :timestamp="e.createTime" placement="top"
            :type="EVENT_COLOR[e.stage] || 'primary'">
            <div style="font-size: 13px; line-height: 1.7">
              <el-tag size="small" style="margin-right: 6px">{{ STAGE_LABEL[e.stage] || e.stage }}</el-tag>
              <span v-if="e.chapterNo" style="color: #409eff">第{{ e.chapterNo }}章 </span>
              <span>{{ eventText(e) }}</span>
            </div>
            <el-collapse style="margin-top: 2px">
              <el-collapse-item title="原始负载">
                <pre style="font-size: 11px; white-space: pre-wrap; margin: 0; max-height: 320px; overflow-y: auto">{{ pretty(e.payloadJson) }}</pre>
              </el-collapse-item>
            </el-collapse>
          </el-timeline-item>
        </el-timeline>
      </el-tab-pane>
    </el-tabs>

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

const tab = ref('calls')
const items = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const totals = ref({})
const detail = ref(null)
const drawer = ref(false)
const novels = ref([])
const novelId = ref(null)
const chapterId = ref(null)
const events = ref([])
const eventChapterNo = ref(null)

const STAGE_LABEL = { run: '运行', chapter: '章', outline: '章纲', scene: '场景', gate: '门禁', assemble: '拼章', chapter_gate: '章门', revise: '修订', review: '审校', digest: '事实账', approve: '审批' }
const EVENT_COLOR = { gate: 'warning', chapter_gate: 'warning', revise: 'warning', review: 'warning', approve: 'warning', digest: 'success', run: 'info' }

function filterQuery() {
  const q = new URLSearchParams()
  if (novelId.value != null) q.set('novelId', novelId.value)
  if (chapterId.value != null) q.set('chapterId', chapterId.value)
  return q.toString()
}

async function load() {
  const f = filterQuery()
  const p = await api.get(`/api/llm-logs?page=${page.value}&size=${size}${f ? '&' + f : ''}`)
  items.value = p.items
  total.value = p.total
  totals.value = await api.get(`/api/llm-logs/totals${f ? '?' + f : ''}`)
}

async function loadEvents() {
  if (novelId.value == null) { events.value = []; return }
  const q = new URLSearchParams({ novelId: novelId.value, limit: '300' })
  if (eventChapterNo.value != null) q.set('chapterNo', eventChapterNo.value)
  const list = await api.get(`/api/llm-logs/events?${q}`)
  events.value = [...list].reverse()
}

async function loadAll() {
  page.value = 1
  await Promise.all([load(), loadEvents()])
}

function pretty(p) {
  try { return JSON.stringify(JSON.parse(p), null, 2) } catch { return p }
}

function eventText(e) {
  let d = {}
  try { d = JSON.parse(e.payloadJson) } catch { return '' }
  switch (e.stage) {
    case 'run': return d.phase === 'start' ? `连跑 ${d.from}-${d.to} 开始《${d.novel}》`
      : d.phase === 'done' ? `连跑结束（通过 ${d.passed}/${d.total} 章）` : `异常：${d.message || ''}`
    case 'chapter': return d.phase === 'start' ? `《${d.title}》开始`
      : d.phase === 'done' ? `完成（${d.chars} 字符）` : `失败：${d.reason}`
    case 'outline': return d.phase === 'start' ? '章纲生成中' : '章纲落库'
    case 'scene': return d.phase === 'draft' ? `场景${d.sceneNo} 产出草稿`
      : d.phase === 'reused' ? `场景${d.sceneNo} 复用已过草稿` : `场景${d.sceneNo} ${d.phase || ''}`
    case 'gate': return `场景${d.sceneNo} 门禁${d.passed ? '通过' : '未过'}${d.round ? `（重写第${d.round}轮）` : ''}${d.reason ? '：' + d.reason : ''}`
    case 'assemble': return `拼章完成（${d.chars} 字符）`
    case 'chapter_gate': return `章级门禁${d.passed ? '通过' : '未过'}${d.reason ? '：' + d.reason : ''}`
    case 'revise': return d.phase === 'start' ? `修订第${d.round}轮开始`
      : d.phase === 'done' ? `修订第${d.round}轮完成（${d.chars} 字符）`
      : d.phase === 'rejected' ? `修订第${d.round}轮稿弃用（长度异常）` : '复用已存正文'
    case 'review': return d.phase === 'start' ? 'AI 语义审校中'
      : d.phase === 'done' ? `审校完成：${d.verdict}${d.blocked ? '，转人工审批' : ''}` : `审校异常跳过：${d.message || ''}`
    case 'digest': return '事实账落库'
    case 'approve': return `待人工审批${d.reason === 'review_blocker' ? '（审校硬伤未清）' : ''}`
    default: return d.phase || ''
  }
}

async function open(row) {
  detail.value = await api.get(`/api/llm-logs/${row.id}`)
  drawer.value = true
}

onMounted(async () => {
  novels.value = await api.get('/api/novels')
  await loadAll()
})
</script>
