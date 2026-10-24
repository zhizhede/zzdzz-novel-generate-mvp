<template>
  <div>
    <PageHeader title="衍生任务" hint="随机衍生 run 列表；「新建 run」走⑤约束采样，详情页跑⑥⑦⑧。">
      <el-button type="primary" size="small" @click="openCreate">新建 run</el-button>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <FilterBar :loading="loading" @search="load" @reset="resetFilters">
      <el-form-item label="关键字">
        <el-input v-model="filters.keyword" placeholder="成品书名/创作思路" clearable
                  style="width: var(--ctrl-w-xl)" @keyup.enter="load" @clear="load" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" style="width: var(--ctrl-w-md)" @change="load">
          <el-option label="全部" value="ALL" />
          <el-option v-for="(m, key) in RUN_STATUS" :key="key" :label="m.label" :value="key" />
        </el-select>
      </el-form-item>
      <el-form-item label="创建时间">
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" unlink-panels
                        start-placeholder="开始" end-placeholder="结束" size="small"
                        style="width: var(--ctrl-w-2xl)" @change="load" />
      </el-form-item>
      <el-form-item label="排序">
        <el-select v-model="filters.sort" style="width: var(--ctrl-w-lg)" @change="load">
          <el-option label="最新在前" value="ID_DESC" />
          <el-option label="最早在前" value="ID_ASC" />
          <el-option label="创建时间倒序" value="TIME_DESC" />
          <el-option label="创建时间正序" value="TIME_ASC" />
        </el-select>
      </el-form-item>
      <template #hit>
        命中 <b>{{ runs.length }}</b> 个 run
      </template>
    </FilterBar>

    <el-card shadow="never">
      <DataTable :data="runs" :loading="loading" size="small" @row-click="(row) => router.push(`/derive/runs/${row.runId}`)">
        <el-table-column prop="runId" label="ID" width="70" />
        <el-table-column label="成品书" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.novelTitle || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(RUN_STATUS, row.status).type">
              {{ statusTag(RUN_STATUS, row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字数带" width="120">
          <template #default="{ row }">{{ row.wordsMin ?? '—' }} ~ {{ row.wordsMax ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="类别" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ shortTags(row.tags) }}</template>
        </el-table-column>
        <el-table-column label="形态" width="110">
          <template #default="{ row }">{{ row.form || '—' }}</template>
        </el-table-column>
        <el-table-column prop="qcRound" label="质检轮" width="70">
          <template #default="{ row }">{{ row.qcRound ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" type="primary" link
                       @click.stop="router.push(`/derive/runs/${row.runId}`)">详情</el-button>
          </template>
        </el-table-column>
      </DataTable>
    </el-card>

    <el-dialog v-model="createOpen" title="⑤ 约束采样（新建 run）" width="640px"
               :close-on-click-modal="false">
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="字数带">
          <el-input-number v-model="form.wordsMin" :min="1000" :max="1000000" :step="1000"
                           size="small" style="width: 150px" placeholder="空" />
          <span style="margin: 0 6px">~</span>
          <el-input-number v-model="form.wordsMax" :min="1000" :max="1000000" :step="1000"
                           size="small" style="width: 150px" placeholder="空" />
          <span class="hint-inline">都空=AI 估档（按思路/标签自动定篇幅与字数带，多约半分钟）</span>
        </el-form-item>
        <el-form-item label="类别 tags" required>
          <el-input v-model="form.tagsText" placeholder="逗号分隔，首位=主标签，如：推理,悬疑" />
        </el-form-item>
        <el-form-item label="节奏 pace" required>
          <el-select v-model="form.pace" style="width: 220px">
            <el-option v-for="p in PACES" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>

        <el-collapse>
          <el-collapse-item title="高级参数（可选）" name="adv">
            <el-form label-width="96px">
              <el-form-item label="随机种子">
                <el-input-number v-model="form.seed" :precision="0" size="small"
                                 style="width: 220px" placeholder="空=服务端随机" />
                <span class="hint-inline">同 seed ⇒ 同结果</span>
              </el-form-item>
              <el-form-item label="形态 form">
                <el-select v-model="form.form" clearable style="width: 240px" placeholder="空=AI 定（字数留空时）">
                  <el-option label="长篇 LONG" value="LONG" />
                  <el-option label="独立短篇 SHORT_STANDALONE" value="SHORT_STANDALONE" />
                  <el-option label="轻小说 LIGHT_NOVEL" value="LIGHT_NOVEL" />
                  <el-option label="长中带短 LONG_INNER_SHORT" value="LONG_INNER_SHORT" />
                  <el-option label="轻中带短 LN_INNER_SHORT" value="LN_INNER_SHORT" />
                </el-select>
                <span class="hint-inline">字数留空时：空=AI 连形态一起定；选了=只估字数。字数已填时：空=seed 抽</span>
              </el-form-item>
              <el-form-item label="结局 outcome">
                <el-select v-model="form.outcome" clearable style="width: 180px" placeholder="空=seed 抽">
                  <el-option label="闭合" value="闭合" />
                  <el-option label="开放" value="开放" />
                  <el-option label="循环" value="循环" />
                </el-select>
              </el-form-item>
              <el-form-item label="防复刻">
                <el-switch v-model="form.antiRepro" />
                <span class="hint-inline">默认开=九轮原口径硬判（防跨稿复刻）；关=一致性优先</span>
              </el-form-item>
              <el-form-item label="字数校验">
                <el-switch v-model="form.lenCheck" />
                <span class="hint-inline">默认关：⑦ 不因每章 ±10% 打回</span>
              </el-form-item>
              <el-form-item label="创作思路">
                <el-input v-model="form.intent" type="textarea" :rows="4"
                          placeholder="题材 brief（⑥b/⑦ 注入；开下面的 AI 预审时必填）" />
              </el-form-item>
              <el-form-item label="AI 预审思路">
                <el-switch v-model="form.intakeOptimize" :disabled="!form.intent.trim()" />
                <span class="hint-inline">建 run 前先让 AI 审思路（需已填思路）</span>
              </el-form-item>
              <el-form-item label="目标文风">
                <el-input v-model="form.style" type="textarea" :rows="2"
                          placeholder="自由文本，如「半文言、短句、冷峻」；优先于源书文风卡" />
              </el-form-item>
            </el-form>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">开始采样</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '../../components/PageHeader.vue'
import FilterBar from '../../components/FilterBar.vue'
import DataTable from '../../components/DataTable.vue'
import { deriveApi } from '../../deriveApi'
import { RUN_STATUS, fmtTime, statusTag } from './deriveLabels'

const router = useRouter()
const loading = ref(false)
const runs = ref([])
const dateRange = ref(null)
const filters = ref({ keyword: '', status: 'ALL', sort: 'ID_DESC' })

const PACES = [
  { value: '0.00', label: '0.00 最慢热' },
  { value: '0.25', label: '0.25 慢热' },
  { value: '0.50', label: '0.50 均衡' },
  { value: '0.75', label: '0.75 快热' },
  { value: '1.00', label: '1.00 极快热' }
]

const createOpen = ref(false)
const creating = ref(false)
const form = ref(defaultForm())

function defaultForm() {
  return {
    wordsMin: undefined,
    wordsMax: undefined,
    tagsText: '',
    pace: '0.50',
    seed: undefined,
    form: '',
    outcome: '',
    antiRepro: true,
    lenCheck: false,
    intent: '',
    style: '',
    intakeOptimize: false
  }
}

function openCreate() {
  form.value = defaultForm()
  createOpen.value = true
}

async function submitCreate() {
  const f = form.value
  const tags = (f.tagsText || '').split(/[,，、\s]+/).map((s) => s.trim()).filter(Boolean)
  const hasMin = f.wordsMin != null
  const hasMax = f.wordsMax != null
  if (hasMin !== hasMax) {
    ElMessage.warning('字数带：要么都填，要么都空（空=按形态默认带宽）')
    return
  }
  if (hasMin && (!(f.wordsMin > 0) || !(f.wordsMax > f.wordsMin))) {
    ElMessage.warning('字数带：下限>0 且上限>下限')
    return
  }
  if (!tags.length) {
    ElMessage.warning('类别 tags 至少一个（首位=主标签）')
    return
  }
  if (!f.pace) {
    ElMessage.warning('节奏必须选五锚点之一')
    return
  }
  if (f.intakeOptimize && !f.intent.trim()) {
    ElMessage.warning('AI 预审思路需先填创作思路')
    return
  }
  creating.value = true
  try {
    const r = await deriveApi.post('/api/derive/runs', {
      wordsMin: f.wordsMin ?? null,
      wordsMax: f.wordsMax ?? null,
      tags,
      pace: f.pace,
      seed: f.seed ?? null,
      form: f.form || null,
      outcome: f.outcome || null,
      antiRepro: f.antiRepro,
      intent: f.intent.trim() || null,
      style: f.style.trim() || null,
      intakeOptimize: f.intakeOptimize,
      lenCheck: f.lenCheck
    })
    ElMessage.success(`采样完成：run #${r.runId} · seed ${r.seed} · ${r.status}`)
    createOpen.value = false
    await load()
    router.push(`/derive/runs/${r.runId}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

function buildQuery() {
  const f = filters.value
  const q = new URLSearchParams()
  if (f.keyword?.trim()) q.set('keyword', f.keyword.trim())
  if (f.status && f.status !== 'ALL') q.set('status', f.status)
  if (dateRange.value?.[0]) q.set('from', dateRange.value[0])
  if (dateRange.value?.[1]) q.set('to', dateRange.value[1])
  if (f.sort) q.set('sort', f.sort)
  const s = q.toString()
  return s ? `?${s}` : ''
}

async function load() {
  loading.value = true
  try {
    runs.value = await deriveApi.get(`/api/derive/runs${buildQuery()}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.value = { keyword: '', status: 'ALL', sort: 'ID_DESC' }
  dateRange.value = null
  load()
}

function shortTags(tags) {
  if (!tags) return '—'
  try {
    const obj = typeof tags === 'string' ? JSON.parse(tags) : tags
    return Object.keys(obj).join(' / ')
  } catch {
    return String(tags)
  }
}

onMounted(load)
</script>

<style scoped>
.hint-inline {
  margin-left: 8px;
  font-size: var(--text-xs);
  color: var(--muted);
}
</style>
