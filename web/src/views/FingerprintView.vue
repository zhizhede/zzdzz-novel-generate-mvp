<template>
  <div>
    <PageHeader
      title="文风指纹"
      hint="系统里所有文风指纹提取结果的统一台账：导入样本（导入即提取的分析快照）、品类预设（语料机械提取采纳）、书籍风格包（开书克隆/导入）。指标口径与机械门禁同源。">
      <el-button type="primary" size="small" @click="importOpen = true">导入文章提取指纹</el-button>
      <el-button size="small" :loading="loading" @click="reloadAll">刷新</el-button>
    </PageHeader>

    <!-- 筛选查询条件：条件全空 = 全量按提取时间倒序 -->
    <FilterBar :loading="loading" @search="load" @reset="resetFilters">
        <el-form-item label="来源">
          <el-select v-model="filters.source" style="width: 130px" @change="load">
            <el-option label="全部来源" value="ALL" />
            <el-option v-for="(label, key) in SOURCE_LABEL" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="filters.keyword" placeholder="名称/品类/说明/标签" clearable style="width: 200px"
                    @keyup.enter="load" @clear="load" @blur="load" />
        </el-form-item>
        <el-form-item label="品类">
          <el-select v-model="filters.genre" placeholder="全部品类" clearable filterable style="width: 170px" @change="load">
            <el-option v-for="g in genreOptions" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="置信度">
          <el-select v-model="filters.confidence" style="width: 110px" @change="load">
            <el-option label="全部" value="ALL" />
            <el-option label="正常" value="HIGH" />
            <el-option label="低置信" value="LOW" />
          </el-select>
        </el-form-item>
        <el-form-item label="指标数 ≥">
          <el-input-number v-model="filters.minMetrics" :min="0" :max="200" :controls="false" placeholder="不限"
                           size="small" style="width: 80px" @change="load" />
        </el-form-item>
        <el-form-item label="语料字数">
          <el-input-number v-model="filters.minChars" :min="0" :max="99999999" :controls="false" placeholder="下限"
                           size="small" style="width: 90px" @change="load" />
          <span style="margin: 0 4px">至</span>
          <el-input-number v-model="filters.maxChars" :min="0" :max="99999999" :controls="false" placeholder="上限"
                           size="small" style="width: 90px" @change="load" />
        </el-form-item>
        <el-form-item label="提取时间">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" unlink-panels
                          start-placeholder="开始" end-placeholder="结束" size="small" style="width: 230px"
                          @change="load" />
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="filters.sort" style="width: 150px" @change="load">
            <el-option label="提取时间倒序" value="TIME_DESC" />
            <el-option label="提取时间正序" value="TIME_ASC" />
            <el-option label="指标数多→少" value="METRICS_DESC" />
            <el-option label="字数多→少" value="CHARS_DESC" />
            <el-option label="名称" value="NAME_ASC" />
          </el-select>
        </el-form-item>
        <template #hit>
          命中 <b>{{ rows.length }}</b> 条（全库 {{ allRows.length }} 条）：样本 {{ sourceCount.SAMPLE }} / 预设 {{ sourceCount.PRESET }} / 书籍 {{ sourceCount.BOOK }} ·
          覆盖品类 {{ matchedGenreCount }} 个
        </template>
    </FilterBar>

    <DataTable :data="rows" :loading="loading" border size="small"
              :row-key="(row) => `${row.source}-${row.refId}`"
              :row-class-name="({ row }) => (row.source === 'SAMPLE' && row.refId === highlightSampleId ? 'row-notice' : '')">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div style="padding: 4px 12px; font-size: 13px; line-height: 1.9">
            <div v-if="!row.hasFingerprint" style="color: #e6a23c">
              无指纹基线：这条记录还没有可用的指纹（历史导入的样本未留分析快照，且未采纳预设）
            </div>
            <div v-if="row.notes && row.notes.length">
              <div v-for="(n, i) in row.notes" :key="i" style="color: #909399">{{ n }}</div>
            </div>
            <div v-if="row.similarities && row.similarities.length">
              <div>与现有预设的相似度：</div>
              <div v-for="s in row.similarities" :key="s.presetId" style="color: #606266">
                {{ s.name }}：{{ s.comparable ? Math.round(s.score * 100) + '%' : '不可比' }}
              </div>
            </div>
            <div v-if="row.tags && row.tags.length" style="margin-top: 4px">
              标签：<el-tag v-for="t in row.tags" :key="t" size="small" style="margin-right: 6px">{{ t }}</el-tag>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="SOURCE_TYPE[row.source]">{{ SOURCE_LABEL[row.source] || row.source }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
      <el-table-column label="说明 / 风格包" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.source === 'BOOK' ? (row.stylePackName || '-') : (row.description || '-') }}</template>
      </el-table-column>
      <el-table-column label="品类" width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.genre || '-' }}</template>
      </el-table-column>
      <el-table-column label="语料规模" width="170">
        <template #default="{ row }">{{ scaleText(row) }}</template>
      </el-table-column>
      <el-table-column label="指标数" width="80">
        <template #default="{ row }">{{ row.metricCount == null ? '-' : row.metricCount }}</template>
      </el-table-column>
      <el-table-column label="章长带" width="130">
        <template #default="{ row }">
          <span v-if="row.budgetMin">{{ row.budgetMin }}–{{ row.budgetMax }} 字</span>
          <span v-else style="color: #bbb">-</span>
        </template>
      </el-table-column>
      <el-table-column label="置信度" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.lowConfidence" size="small" type="warning">低置信</el-tag>
          <span v-else-if="row.lowConfidence === false" style="color: #67c23a; font-size: 12px">正常</span>
          <span v-else style="color: #bbb">-</span>
        </template>
      </el-table-column>
      <el-table-column label="关联" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <template v-if="row.source === 'SAMPLE'">
            <el-tag v-if="row.presetId" size="small" type="success">已采纳 #{{ row.presetId }} {{ row.presetName }}</el-tag>
            <span v-else style="color: #e6a23c; font-size: 12px">未采纳预设</span>
          </template>
          <template v-else-if="row.source === 'BOOK'">
            <span v-if="row.sampleId" style="font-size: 12px">源样本 #{{ row.sampleId }}</span>
            <span v-else style="color: #bbb">-</span>
          </template>
          <span v-else style="color: #bbb">-</span>
        </template>
      </el-table-column>
      <el-table-column label="提取时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDetail(row)">指标明细</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有命中的指纹记录——放宽筛选条件再查" />
      </template>
    </DataTable>

    <!-- 指标明细：基线/容差/硬边界 + 原始 JSON（只读，改指纹请去风格包页） -->
    <el-dialog v-model="detailVisible" :title="`指纹明细 · ${detailRow ? detailRow.name : ''}`" width="900px">
      <div v-if="detailRow">
        <div style="font-size: 13px; color: #606266; line-height: 1.9; margin-bottom: 8px">
          <div>来源：{{ SOURCE_LABEL[detailRow.source] }}（#{{ detailRow.refId }}{{ detailRow.source === 'BOOK' ? ' · 风格包' : '' }}）
            <span v-if="detailRow.novelId"> · 书籍 #{{ detailRow.novelId }}</span>
          </div>
          <div v-if="detailRow.stylePackName">风格包：{{ detailRow.stylePackName }}</div>
          <div v-if="detailRow.genre">品类：{{ detailRow.genre }}</div>
          <div v-if="detailRow.description">说明：{{ detailRow.description }}</div>
          <div>提取时间：{{ fmtTime(detailRow.createTime) }}
            <span v-if="detailRow.updateTime && detailRow.updateTime !== detailRow.createTime"> · 最近更新 {{ fmtTime(detailRow.updateTime) }}</span>
          </div>
          <div v-if="detailRow.chunks != null">语料规模：{{ scaleText(detailRow) }}</div>
          <div v-if="detailRow.budgetMin">章长带：{{ detailRow.budgetMin }}–{{ detailRow.budgetMax }} 字（容差 ±{{ detailRow.lengthTolerance }}）</div>
          <div v-if="detailRow.recommendation">提取建议：{{ RECOMMENDATION_LABEL[detailRow.recommendation] || detailRow.recommendation }}</div>
        </div>

        <el-alert type="info" :closable="false" style="margin-bottom: 8px"
                  title="判定口径：abs_min 为显式下限；基线 <3/千字的稀疏指标不设下限（只防滥用）；其余实际区间＝基线×(1±容差)，配了 abs_max 时上限以 abs_max 为准。对白句末标点占比另有 0.5 硬下限。" />

        <FingerprintMetricTable :metrics="detailRow.metrics" :max-height="360" style="margin-bottom: 10px" />

        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px">
          <b style="font-size: 13px">原始指纹 JSON</b>
          <el-button size="small" @click="copyJson">复制</el-button>
        </div>
        <pre style="max-height: 240px; overflow: auto; background: #f7f8fa; padding: 10px; border-radius: 6px; font-size: 12px; line-height: 1.6">{{ prettyJson(detailRow.fingerprintJson) }}</pre>
        <div style="color: #999; font-size: 12px; margin-top: 6px">
          改指纹阈值/门禁请去「素材库 → 质量与风格」（本书）或品类预设页；此处只读。
        </div>
      </div>
    </el-dialog>

    <!-- 导入文章提取指纹：与素材库「导入小说」同一个弹窗、同一条落库口径（切块语料 + 分析快照 + 台账） -->
    <SampleImportDialog v-model="importOpen" @imported="onImported" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import SampleImportDialog from '../components/SampleImportDialog.vue'
import FingerprintMetricTable from '../components/FingerprintMetricTable.vue'
import DataTable from '../components/DataTable.vue'
import PageHeader from '../components/PageHeader.vue'
import FilterBar from '../components/FilterBar.vue'

const SOURCE_LABEL = { SAMPLE: '导入样本', PRESET: '品类预设', BOOK: '书籍风格包' }
const SOURCE_TYPE = { SAMPLE: 'primary', PRESET: 'success', BOOK: 'warning' }
const RECOMMENDATION_LABEL = { match: '可复用现有预设', new: '建议新建品类', choice: '两可' }

const rows = ref([])
const allRows = ref([])
const loading = ref(false)
const dateRange = ref(null)
const filters = reactive({
  source: 'ALL', keyword: '', genre: '', confidence: 'ALL',
  minMetrics: null, minChars: null, maxChars: null, sort: 'TIME_DESC'
})

const genreOptions = computed(() => [...new Set(allRows.value.map((r) => r.genre).filter(Boolean))].sort())
const sourceCount = computed(() => ({
  SAMPLE: rows.value.filter((r) => r.source === 'SAMPLE').length,
  PRESET: rows.value.filter((r) => r.source === 'PRESET').length,
  BOOK: rows.value.filter((r) => r.source === 'BOOK').length
}))
const matchedGenreCount = computed(() => new Set(rows.value.map((r) => r.genre).filter(Boolean)).size)

/** 装配查询串：只带非空条件，空条件让后端走全量。 */
function buildQuery() {
  const p = new URLSearchParams()
  if (filters.source && filters.source !== 'ALL') p.set('source', filters.source)
  if (filters.keyword && filters.keyword.trim()) p.set('keyword', filters.keyword.trim())
  if (filters.genre) p.set('genre', filters.genre)
  if (filters.confidence && filters.confidence !== 'ALL') p.set('confidence', filters.confidence)
  if (filters.minMetrics !== null && filters.minMetrics !== undefined) p.set('minMetrics', filters.minMetrics)
  if (filters.minChars !== null && filters.minChars !== undefined) p.set('minChars', filters.minChars)
  if (filters.maxChars !== null && filters.maxChars !== undefined) p.set('maxChars', filters.maxChars)
  if (dateRange.value && dateRange.value.length === 2) {
    p.set('from', dateRange.value[0])
    p.set('to', dateRange.value[1])
  }
  if (filters.sort) p.set('sort', filters.sort)
  return p.toString()
}

/** 按当前条件查询列表。 */
async function load() {
  loading.value = true
  try {
    const qs = buildQuery()
    rows.value = await api.get(`/api/style-fingerprints${qs ? '?' + qs : ''}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 全量拉一次：品类下拉选项与「全库 N 条」读数用（不受筛选影响）。 */
async function loadAll() {
  try {
    allRows.value = await api.get('/api/style-fingerprints')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function reloadAll() {
  await loadAll()
  await load()
}

const importOpen = ref(false)
const highlightSampleId = ref(null)

/** 导入成功：重拉列表并高亮新行；若当前筛选条件没命中它，明确告知而不是静默「看起来没入库」。 */
async function onImported(result) {
  highlightSampleId.value = result.sampleId
  await loadAll()
  await load()
  const visible = rows.value.some((row) => row.source === 'SAMPLE' && row.refId === result.sampleId)
  if (visible) {
    ElMessage.success(`已提取并入库：${result.chunks} 块语料 · ${result.metricCount} 项指标 · 品类「${result.genre}」（下表已高亮，展开可看相似度）`)
  } else {
    ElMessage.warning(`已入库（品类「${result.genre}」，${result.metricCount} 项指标），但当前筛选条件没命中它——点「重置」即可看到`)
  }
}

function resetFilters() {
  filters.source = 'ALL'
  filters.keyword = ''
  filters.genre = ''
  filters.confidence = 'ALL'
  filters.minMetrics = null
  filters.minChars = null
  filters.maxChars = null
  filters.sort = 'TIME_DESC'
  dateRange.value = null
  load()
}

const detailVisible = ref(false)
const detailRow = ref(null)

function openDetail(row) {
  detailRow.value = row
  detailVisible.value = true
}

async function copyJson() {
  try {
    await navigator.clipboard.writeText(prettyJson(detailRow.value.fingerprintJson))
    ElMessage.success('已复制指纹 JSON')
  } catch {
    ElMessage.warning('浏览器未授权剪贴板，请手动选中复制')
  }
}

function prettyJson(text) {
  if (!text) return '（无指纹基线）'
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

function scaleText(row) {
  const parts = []
  if (row.chunks != null) parts.push(`${row.chunks} 块`)
  if (row.totalChars != null) parts.push(`${(row.totalChars / 10000).toFixed(1)} 万字`)
  return parts.length ? parts.join(' · ') : '-'
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '-'
}

onMounted(() => reloadAll())
</script>
