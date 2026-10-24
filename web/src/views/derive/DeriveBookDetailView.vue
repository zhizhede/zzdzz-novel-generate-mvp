<template>
  <div v-loading="loading">
    <PageHeader :title="`书 #${route.params.id}${detail ? ' · ' + detail.title : ''}`">
      <el-button size="small" @click="router.push('/derive/books')">← 返回列表</el-button>
      <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
      <template #hint>
        <el-tag v-if="detail" size="small" :type="statusTag(BOOK_SOURCE, detail.sourceType).type">
          {{ statusTag(BOOK_SOURCE, detail.sourceType).label }}
        </el-tag>
      </template>
    </PageHeader>

    <template v-if="detail">
      <el-card shadow="never" class="block">
        <template #header>元数据</template>
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="书名">{{ detail.title }}</el-descriptions-item>
          <el-descriptions-item label="作者">{{ detail.author || '—' }}</el-descriptions-item>
          <el-descriptions-item label="平台">{{ detail.platform || '—' }}</el-descriptions-item>
          <el-descriptions-item label="章数/字数">
            {{ detail.chapterCount ?? '—' }} 章 · {{ detail.charCount != null ? detail.charCount.toLocaleString() + ' 字' : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="来源 run">
            <el-button v-if="detail.runId" size="small" link type="primary"
                       @click="router.push(`/derive/runs/${detail.runId}`)">Run #{{ detail.runId }}</el-button>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ fmtTime(detail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="类别标签" :span="3">{{ shortTags(detail.categoryTags) }}</el-descriptions-item>
          <el-descriptions-item label="概括" :span="3">{{ detail.summary || '—' }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          素材处理流水线（②→④d）
          <span class="hint-inline">按顺序点；带 ✓ 的已完成，AI 步骤按书的体量耗时秒~分钟</span>
        </template>
        <div class="pipeline">
          <div v-for="step in steps" :key="step.key" class="pipeline__item">
            <el-button size="small" :type="step.active ? 'primary' : 'default'"
                       :loading="running[step.key]" :disabled="running[step.key]"
                       @click="runStep(step)">
              {{ step.label }}
            </el-button>
            <el-tag v-if="step.done !== null" size="small"
                    :type="step.done ? 'success' : 'info'" class="pipeline__tag">
              {{ step.done ? '✓ 已完成' : '待处理' }}
            </el-tag>
          </div>
        </div>
        <p class="hint-line">
          顺序：②结构抽取 → ③无指向化 → ④a 词表扫查（脚本，看命中）→ ④b 批量打标 → ④c 域格归纳 → ④d 文风抽取（可选）。
          ④a 若报黑名单命中，回 ③ 重跑。
        </p>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>素材处理进度</template>
        <el-row :gutter="12">
          <el-col :span="4" v-for="cell in progressCells" :key="cell.label" class="prog-cell">
            <div class="stat-label">{{ cell.label }}</div>
            <div class="stat-value">{{ cell.value }}</div>
          </el-col>
          <el-col :span="4" class="prog-cell">
            <div class="stat-label">文风卡</div>
            <div class="stat-value">
              <el-tag size="small" :type="detail.hasStyleProfile ? 'success' : 'info'">
                {{ detail.hasStyleProfile ? '已抽取' : '无' }}
              </el-tag>
            </div>
          </el-col>
        </el-row>
        <p class="hint-line">
          源章=①入库；弧/拍/世界=②抽取；已标注弧=③无指向化；标签=④b 打标产物。
        </p>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          源章（{{ chapters.length }}）
          <el-button size="small" link type="primary" style="margin-left: 10px" @click="toggleText">
            {{ withText ? '隐藏正文列' : '附带正文加载' }}
          </el-button>
        </template>
        <DataTable :data="chapters" size="small">
          <el-table-column prop="seq" label="序" width="60" />
          <el-table-column prop="title" label="章题" min-width="180" show-overflow-tooltip />
          <el-table-column prop="charCount" label="字数" width="90" />
          <el-table-column v-if="withText" label="正文预览" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ (row.content || '').slice(0, 100) || '—' }}</template>
          </el-table-column>
        </DataTable>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../../components/PageHeader.vue'
import DataTable from '../../components/DataTable.vue'
import { deriveApi } from '../../deriveApi'
import { BOOK_SOURCE, fmtTime, statusTag } from './deriveLabels'

/** 书详情：元数据 + 素材处理进度读数 + 源章列表（只读）。 */
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const detail = ref(null)
const chapters = ref([])
const withText = ref(false)

const progressCells = computed(() => {
  const d = detail.value
  if (!d) return []
  return [
    { label: '源章', value: d.sourceChapterCount },
    { label: '弧素材', value: d.outlineCount },
    { label: '已标注弧', value: d.outlineLabeledCount },
    { label: '拍素材', value: d.beatCount },
    { label: '世界素材', value: d.worldCount },
    { label: '标签行', value: d.labelCount }
  ]
})

function shortTags(tags) {
  if (!tags) return '—'
  try {
    const obj = typeof tags === 'string' ? JSON.parse(tags) : tags
    return Object.entries(obj).map(([k, v]) => `${k}:${v}`).join('，')
  } catch {
    return String(tags)
  }
}

/** 素材链步骤：done=null 的两步（④a/④c）无持久读数可判，只给动作不给 ✓ 标。 */
const running = ref({})

const steps = computed(() => {
  const d = detail.value
  if (!d) return []
  const list = [
    { key: 'extract', label: '② 结构抽取', url: '/extract', llm: true, done: d.outlineCount > 0 },
    { key: 'deindex', label: '③ 无指向化', url: '/deindex', llm: true, done: d.outlineLabeledCount > 0 },
    { key: 'grep', label: '④a 词表扫查', url: '/grep', llm: false, done: null },
    { key: 'label', label: '④b 批量打标', url: '/label', llm: true, done: d.labelCount > 0 },
    { key: 'domains', label: '④c 域格归纳', url: '/domains', llm: true, done: null },
    { key: 'style', label: '④d 文风抽取', url: '/style', llm: true, done: d.hasStyleProfile }
  ]
  const firstPending = list.findIndex((s) => s.done === false)
  return list.map((s, i) => ({ ...s, active: i === firstPending }))
})

async function runStep(step) {
  const conf = step.llm
    ? `将调用 AI 执行「${step.label}」，按书的体量耗时秒~分钟并计入模型用量。继续？`
    : `执行「${step.label}」（脚本扫查，不调用模型）。继续？`
  try {
    await ElMessageBox.confirm(conf, '确认执行', { type: 'warning', confirmButtonText: '执行' })
  } catch {
    return
  }
  running.value = { ...running.value, [step.key]: true }
  try {
    const r = await deriveApi.post(`/api/derive/books/${route.params.id}${step.url}`)
    if (step.key === 'grep') {
      const black = r?.blacklistHits ?? 0
      const dword = r?.domainWordHits ?? 0
      if (black > 0) {
        ElMessage.warning(`扫查完成：扫描 ${r.scanned} 条，黑名单命中 ${black} ——建议回 ③ 重跑无指向化`)
      } else {
        ElMessage.success(`扫查完成：扫描 ${r.scanned} 条，黑名单 0 命中${dword > 0 ? `，领域词命中 ${dword}（人工判）` : ''}`)
      }
    } else {
      ElMessage.success(`「${step.label}」完成`)
    }
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
    await loadAll()
  } finally {
    running.value = { ...running.value, [step.key]: false }
  }
}

async function loadAll() {
  loading.value = true
  const id = route.params.id
  try {
    const [d, c] = await Promise.all([
      deriveApi.get(`/api/derive/books/${id}`),
      deriveApi.get(`/api/derive/books/${id}/chapters${withText.value ? '?includeText=true' : ''}`)
    ])
    detail.value = d
    chapters.value = c
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function toggleText() {
  withText.value = !withText.value
  await loadAll()
}

onMounted(loadAll)
</script>

<style scoped>
.block {
  margin-bottom: var(--space-3);
}
.prog-cell {
  text-align: center;
  padding: 4px 0;
}
.stat-label {
  font-size: var(--text-xs);
  color: var(--muted);
}
.stat-value {
  font-size: var(--text-lg);
  font-weight: 600;
}
.hint-line {
  margin: 10px 0 0;
  font-size: var(--text-xs);
  color: var(--muted);
}
.hint-inline {
  margin-left: 10px;
  font-size: var(--text-xs);
  font-weight: 400;
  color: var(--muted);
}
.pipeline {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.pipeline__item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.pipeline__tag {
  margin-right: 4px;
}
</style>
