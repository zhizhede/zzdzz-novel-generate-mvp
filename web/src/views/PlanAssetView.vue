<template>
  <div>
    <PageHeader
      title="规划资产"
      hint="大纲 / 卷纲 / 章纲的统一下游台账（跨书只读）。产出入口：大纲＝「开新书 → AI 生成大纲」或书籍行内续写链；卷纲＝「规划」页 AI 规划一卷；章纲＝生成管线逐章产出或「规划」页章纲批量任务。">
      <el-button size="small" :loading="loading" @click="reloadAll">刷新</el-button>
    </PageHeader>

    <el-radio-group v-model="filters.level" size="small" style="margin-bottom: 10px" @change="onLevelChange">
      <el-radio-button label="OUTLINE">大纲（{{ countByLevel.OUTLINE }}）</el-radio-button>
      <el-radio-button label="VOLUME">卷纲（{{ countByLevel.VOLUME }}）</el-radio-button>
      <el-radio-button label="CHAPTER">章纲（{{ countByLevel.CHAPTER }}）</el-radio-button>
    </el-radio-group>

    <!-- 筛选查询条件：条件全空 = 该层全量，默认按「书 + 卷号 + 章号」通读序 -->
    <FilterBar :loading="loading" @search="load" @reset="resetFilters">
        <el-form-item label="作品">
          <el-select v-model="filters.novelId" placeholder="全部作品" clearable filterable style="width: var(--ctrl-w-xl)" @change="load">
            <el-option v-for="n in novels" :key="n.id" :label="n.title" :value="n.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库类型">
          <el-select v-model="filters.sourceType" style="width: var(--ctrl-w-lg)" @change="load">
            <el-option label="全部类型" value="ALL" />
            <el-option label="手动导入" value="IMPORTED" />
            <el-option label="系统衍生" value="DERIVED" />
            <el-option label="系统纯原创" value="ORIGINAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="filters.keyword" :placeholder="keywordPlaceholder" clearable style="width: var(--ctrl-w-xl)"
                    @keyup.enter="load" @clear="load" @blur="load" />
        </el-form-item>
        <el-form-item v-if="filters.level !== 'OUTLINE'" label="卷号">
          <el-input-number v-model="filters.volumeNo" :min="0" :max="9999" :controls="false" placeholder="不限"
                           size="small" style="width: var(--ctrl-w-sm)" @change="load" />
          <span style="font-size: var(--text-xs); color: var(--meta); margin-left: 4px">0＝未分卷</span>
        </el-form-item>
        <el-form-item v-if="filters.level !== 'OUTLINE'" label="章号">
          <el-input-number v-model="filters.fromChapter" :min="1" :max="99999" :controls="false" placeholder="起"
                           size="small" style="width: var(--ctrl-w-sm)" @change="load" />
          <span style="margin: 0 4px">至</span>
          <el-input-number v-model="filters.toChapter" :min="1" :max="99999" :controls="false" placeholder="止"
                           size="small" style="width: var(--ctrl-w-sm)" @change="load" />
        </el-form-item>
        <el-form-item v-if="filters.level === 'CHAPTER'" label="章状态">
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: var(--ctrl-w-lg)" @change="load">
            <el-option v-for="(label, key) in STATUS_TEXT" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item :label="filters.level === 'OUTLINE' ? '有无大纲' : '有无章纲'">
          <el-select v-model="filters.hasOutline" style="width: var(--ctrl-w-md)" @change="load">
            <el-option label="不限" value="ALL" />
            <el-option label="有" value="YES" />
            <el-option label="无（缺口）" value="NO" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="filters.level !== 'OUTLINE'" label="有无正文">
          <el-select v-model="filters.hasText" style="width: var(--ctrl-w-md)" @change="load">
            <el-option label="不限" value="ALL" />
            <el-option label="有" value="YES" />
            <el-option label="无（缺口）" value="NO" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="filters.level === 'OUTLINE'" label="样本骨架">
          <el-select v-model="filters.skeleton" style="width: var(--ctrl-w-lg)" @change="load">
            <el-option label="不限" value="ALL" />
            <el-option label="只看未改写骨架" value="YES" />
            <el-option label="排除骨架" value="NO" />
          </el-select>
        </el-form-item>
        <el-form-item :label="filters.level === 'OUTLINE' ? '大纲字数' : '正文字数'">
          <el-input-number v-model="filters.minChars" :min="0" :max="99999999" :controls="false" placeholder="下限"
                           size="small" style="width: var(--ctrl-w-sm)" @change="load" />
          <span style="margin: 0 4px">至</span>
          <el-input-number v-model="filters.maxChars" :min="0" :max="99999999" :controls="false" placeholder="上限"
                           size="small" style="width: var(--ctrl-w-sm)" @change="load" />
        </el-form-item>
        <el-form-item label="创建时间">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" unlink-panels
                          start-placeholder="开始" end-placeholder="结束" size="small" style="width: var(--ctrl-w-2xl)"
                          @change="load" />
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="filters.sort" style="width: var(--ctrl-w-xl)" @change="load">
            <el-option label="书+卷+章（通读序）" value="ORDER_ASC" />
            <el-option label="书+卷+章倒序" value="ORDER_DESC" />
            <el-option label="创建时间倒序" value="TIME_DESC" />
            <el-option label="创建时间正序" value="TIME_ASC" />
            <el-option label="字数多→少" value="TEXT_DESC" />
            <el-option label="章纲字数多→少" value="OUTLINE_DESC" />
            <el-option label="书名" value="TITLE_ASC" />
          </el-select>
        </el-form-item>
        <template #hit>
          命中 <b>{{ rows.length }}</b> 条 · {{ hitReadout }}
        </template>
    </FilterBar>

    <!-- 大纲层：一书一行；缺大纲的书也列出来（缺口清单） -->
    <DataTable v-if="filters.level === 'OUTLINE'" :data="rows" :loading="loading" border size="small"
              :row-class-name="gapRowClass">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div style="padding: 4px 12px; font-size: var(--text-sm); line-height: 1.9">
            <div v-if="!row.hasOutline" style="color: var(--warn)">
              这本书还没有大纲——去「书籍管理」点「继续向导 → AI 生成大纲」，或工作台提交生成任务时会自动规划。
            </div>
            <pre v-else style="white-space: pre-wrap; margin: 0; font-family: inherit">{{ row.outline }}</pre>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="作品" min-width="190" show-overflow-tooltip>
        <template #default="{ row }">{{ row.novelTitle }}</template>
      </el-table-column>
      <el-table-column label="入库类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="SOURCE_TYPE[row.sourceType]">{{ SOURCE_LABEL[row.sourceType] || row.sourceType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大纲" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.hasOutline && row.skeleton" size="small" type="warning">样本骨架未改写</el-tag>
          <el-tag v-else-if="row.hasOutline" size="small" type="success">已就绪</el-tag>
          <el-tag v-else size="small" type="danger">缺大纲</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="字数" width="90">
        <template #default="{ row }">{{ row.outlineChars == null ? '-' : row.outlineChars }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button size="small" link :disabled="!row.hasOutline" @click="openText('大纲全文', row.novelTitle, row.outline)">看全文</el-button>
        </template>
      </el-table-column>
    </DataTable>

    <!-- 卷纲层：一书一卷一行（章行聚合 + 卷复盘） -->
    <DataTable v-else-if="filters.level === 'VOLUME'" :data="rows" :loading="loading" border size="small"
              :row-class-name="gapRowClass">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div style="padding: 4px 12px; font-size: var(--text-sm); line-height: 1.9">
            <div v-if="row.hasReview">
              <div><b>卷复盘摘要</b>：{{ row.reviewSummary || '（复盘未给摘要）' }}</div>
              <div style="color: var(--muted)">
                落差 {{ row.reviewDrifts }} 条（其中 major {{ row.reviewMajor }} 条）· 复盘覆盖第 {{ reviewRange(row) }}
              </div>
            </div>
            <div v-else style="color: var(--warn)">
              这一卷还没有卷复盘（复盘在规划下一卷前自动跑；导入书要先有卷纲才有卷可比）。
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="作品" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.novelTitle }}</template>
      </el-table-column>
      <el-table-column label="入库类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="SOURCE_TYPE[row.sourceType]">{{ SOURCE_LABEL[row.sourceType] || row.sourceType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="卷" width="90">
        <template #default="{ row }">
          <span v-if="row.volumeNo">{{ row.volumeNo }}</span>
          <el-tag v-else size="small" type="info">未分卷</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="卷名 / 弧" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.arc || '—' }}</template>
      </el-table-column>
      <el-table-column label="章范围" width="120">
        <template #default="{ row }">第 {{ row.fromChapter }}–{{ row.toChapter }} 章（{{ row.chapterCount }} 章）</template>
      </el-table-column>
      <el-table-column label="章纲覆盖" width="110">
        <template #default="{ row }">
          <span :style="row.outlineChapters === 0 ? 'color:var(--danger)' : ''">{{ row.outlineChapters }}/{{ row.chapterCount }} 章</span>
        </template>
      </el-table-column>
      <el-table-column label="正文覆盖" width="110">
        <template #default="{ row }">
          <span :style="row.textChapters === 0 ? 'color:var(--muted)' : ''">{{ row.textChapters }}/{{ row.chapterCount }} 章</span>
        </template>
      </el-table-column>
      <el-table-column label="本卷正文" width="100">
        <template #default="{ row }">{{ row.textChars ? (row.textChars / 10000).toFixed(1) + ' 万字' : '—' }}</template>
      </el-table-column>
      <el-table-column label="章预算带" width="120">
        <template #default="{ row }">
          <span v-if="row.budgetMin">{{ row.budgetMin }}–{{ row.budgetMax }} 字</span>
          <span class="cell-empty" v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="卷复盘" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.hasReview" size="small" :type="row.reviewMajor ? 'danger' : 'success'">
            {{ row.reviewMajor ? row.reviewMajor + ' 处 major' : '已复盘' }}
          </el-tag>
          <span class="cell-empty" v-else>未复盘</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button size="small" link :disabled="!row.hasReview" @click="openText('卷复盘 JSON', `${row.novelTitle} 第${row.volumeNo}卷`, prettyJson(row.review))">看复盘</el-button>
        </template>
      </el-table-column>
    </DataTable>

    <!-- 章纲层：一章一行 -->
    <DataTable v-else :data="rows" :loading="loading" border size="small"
              :row-class-name="gapRowClass">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div style="padding: 4px 12px; font-size: var(--text-sm); line-height: 1.9">
            <div>目标：{{ row.goal || '—' }}</div>
            <div>钩子：{{ row.hook || '—' }}</div>
            <div>时间跨度：{{ row.timeNote || '—' }}</div>
            <div v-if="!row.hasOutline" style="color: var(--warn)">
              本章还没有章纲——「规划」页可对单章「重新生成章纲」，或提交生成任务时自动产出。
            </div>
            <pre v-else style="white-space: pre-wrap; margin: 6px 0 0; background: var(--surface-warm); padding: 8px; border-radius: 6px">{{ row.outline }}</pre>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="作品" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.novelTitle }}</template>
      </el-table-column>
      <el-table-column label="入库类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="SOURCE_TYPE[row.sourceType]">{{ SOURCE_LABEL[row.sourceType] || row.sourceType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="卷" width="80">
        <template #default="{ row }">
          <span v-if="row.volumeNo">{{ row.volumeNo }}</span>
          <span class="cell-empty" v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="章" width="70">
        <template #default="{ row }">{{ row.chapterNo }}</template>
      </el-table-column>
      <el-table-column label="章名" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.chapterTitle || '—' }}</template>
      </el-table-column>
      <el-table-column label="目标" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.goal || '—' }}</template>
      </el-table-column>
      <el-table-column label="时间跨度" width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.timeNote || '—' }}</template>
      </el-table-column>
      <el-table-column label="章纲" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.hasOutline" size="small" type="success">{{ row.outlineChars }} 字</el-tag>
          <el-tag v-else size="small" type="warning">未生成</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="预算" width="100">
        <template #default="{ row }">
          <span v-if="row.budgetMin">{{ row.budgetMin }}-{{ row.budgetMax }}</span>
          <span class="cell-empty" v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="STATUS_COLOR[row.status] || 'info'">{{ STATUS_TEXT[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="正文" width="90">
        <template #default="{ row }">
          <span v-if="row.hasText">{{ row.textChars }} 字</span>
          <span class="cell-empty" v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="伏笔" width="70">
        <template #default="{ row }">{{ row.foreshadowRefs ? row.foreshadowRefs + ' 条' : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button size="small" link :disabled="!row.hasOutline" @click="openText('章纲 YAML', `${row.novelTitle} 第${row.chapterNo}章`, row.outline)">看章纲</el-button>
        </template>
      </el-table-column>
    </DataTable>

    <el-dialog v-model="textVisible" :title="textTitle" width="var(--dlg-w-xl)">
      <div style="display: flex; justify-content: flex-end; margin-bottom: 6px">
        <el-button size="small" @click="copyText">复制</el-button>
      </div>
      <pre style="white-space: pre-wrap; background: var(--surface-warm); padding: 10px; border-radius: 6px; font-size: var(--text-xs); line-height: 1.7">{{ textBody }}</pre>
      <div class="hint" style="margin-top: 6px">
        此处只读。改大纲去「规划」页（或书籍管理 → 继续向导），改卷纲/章纲去「规划」页对应行；改完这里刷新即可看到。
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import DataTable from '../components/DataTable.vue'
import PageHeader from '../components/PageHeader.vue'
import FilterBar from '../components/FilterBar.vue'

const SOURCE_LABEL = { IMPORTED: '手动导入', DERIVED: '系统衍生', ORIGINAL: '系统纯原创' }
const SOURCE_TYPE = { IMPORTED: 'primary', DERIVED: 'success', ORIGINAL: 'warning' }
// 与章节页同一套状态口径
const STATUS_TEXT = { NEW: '待生成', OUTLINED: '章纲就绪', OUTLINE_APPROVED: '章纲已批', GATE_MECHANICAL: '门禁修订中', GATE_AI_REVIEW: '审校中', REVISING: '修订中', DIGESTED: '已完成', FINAL: '导入正文', PENDING_APPROVAL: '待审批', FAILED: '失败', INTERRUPTED: '已中断' }
const STATUS_COLOR = { NEW: 'info', OUTLINED: 'primary', OUTLINE_APPROVED: 'primary', GATE_MECHANICAL: 'warning', GATE_AI_REVIEW: 'warning', REVISING: 'warning', DIGESTED: 'success', FINAL: 'success', PENDING_APPROVAL: 'warning', FAILED: 'danger', INTERRUPTED: 'danger' }
const KEYWORD_HINT = {
  OUTLINE: '书名 / 大纲正文',
  VOLUME: '书名 / 卷名 / 章名 / 目标',
  CHAPTER: '书名 / 章名 / 目标 / 钩子 / 章纲正文'
}

const novels = ref([])
const rows = ref([])
const eachLevel = reactive({ OUTLINE: [], VOLUME: [], CHAPTER: [] })
const loading = ref(false)
const dateRange = ref(null)
const filters = reactive({
  level: 'CHAPTER', novelId: null, sourceType: 'ALL', keyword: '',
  volumeNo: null, fromChapter: null, toChapter: null, status: '',
  hasOutline: 'ALL', hasText: 'ALL', skeleton: 'ALL',
  minChars: null, maxChars: null, sort: 'ORDER_ASC'
})

const keywordPlaceholder = computed(() => KEYWORD_HINT[filters.level] || '关键字')
const countByLevel = computed(() => ({
  OUTLINE: eachLevel.OUTLINE.length,
  VOLUME: eachLevel.VOLUME.length,
  CHAPTER: eachLevel.CHAPTER.length
}))

/** 缺口读数：三层各自最该被看见的「有多少还没产出」。 */
const hitReadout = computed(() => {
  const r = rows.value
  if (filters.level === 'OUTLINE') {
    const missing = r.filter((x) => !x.hasOutline).length
    const skeleton = r.filter((x) => x.skeleton).length
    return `有大纲 ${r.length - missing} 本 · 缺大纲 ${missing} 本${skeleton ? ` · 其中仍是样本骨架 ${skeleton} 本` : ''}`
  }
  if (filters.level === 'VOLUME') {
    const noOutline = r.filter((x) => !x.outlineChapters).length
    const noReview = r.filter((x) => !x.hasReview).length
    const unassigned = r.filter((x) => x.volumeNo == null).length
    return `有章纲 ${r.length - noOutline} 卷 · 无章纲 ${noOutline} 卷 · 未复盘 ${noReview} 卷${unassigned ? ` · 未分卷组 ${unassigned}` : ''}`
  }
  const noOutline = r.filter((x) => !x.hasOutline).length
  const noText = r.filter((x) => !x.hasText).length
  return `有章纲 ${r.length - noOutline} 章 · 无章纲 ${noOutline} 章 · 无正文 ${noText} 章`
})

/** 装配查询串：只带非空条件，空条件让后端走该层全量。 */
function buildQuery(level) {
  const p = new URLSearchParams()
  p.set('level', level)
  if (filters.novelId) p.set('novelId', filters.novelId)
  if (filters.sourceType && filters.sourceType !== 'ALL') p.set('sourceType', filters.sourceType)
  if (filters.keyword && filters.keyword.trim()) p.set('keyword', filters.keyword.trim())
  if (filters.level !== 'OUTLINE') {
    if (filters.volumeNo !== null && filters.volumeNo !== undefined) p.set('volumeNo', filters.volumeNo)
    if (filters.fromChapter !== null && filters.fromChapter !== undefined) p.set('fromChapter', filters.fromChapter)
    if (filters.toChapter !== null && filters.toChapter !== undefined) p.set('toChapter', filters.toChapter)
  }
  if (filters.level === 'CHAPTER' && filters.status) p.set('status', filters.status)
  if (filters.hasOutline && filters.hasOutline !== 'ALL') p.set('hasOutline', filters.hasOutline)
  if (filters.level !== 'OUTLINE' && filters.hasText && filters.hasText !== 'ALL') p.set('hasText', filters.hasText)
  if (filters.level === 'OUTLINE' && filters.skeleton && filters.skeleton !== 'ALL') p.set('skeleton', filters.skeleton)
  if (filters.minChars !== null && filters.minChars !== undefined) p.set('minChars', filters.minChars)
  if (filters.maxChars !== null && filters.maxChars !== undefined) p.set('maxChars', filters.maxChars)
  if (dateRange.value && dateRange.value.length === 2) {
    p.set('from', dateRange.value[0])
    p.set('to', dateRange.value[1])
  }
  if (filters.sort) p.set('sort', filters.sort)
  return p.toString()
}

/** 按当前条件查当前层。 */
async function load() {
  loading.value = true
  try {
    const qs = buildQuery(filters.level)
    rows.value = await api.get(`/api/plan-assets?${qs}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 三层各拉一次（不带筛选）：tab 上的数量读数用，同时告诉用户「另一层有多少东西」。 */
async function loadCounts() {
  for (const level of ['OUTLINE', 'VOLUME', 'CHAPTER']) {
    try {
      eachLevel[level] = await api.get(`/api/plan-assets?level=${level}`)
    } catch (e) {
      eachLevel[level] = []
    }
  }
}

async function loadNovels() {
  try {
    novels.value = await api.get('/api/novels')
  } catch (e) {
    novels.value = []
  }
}

async function reloadAll() {
  await Promise.all([loadNovels(), loadCounts()])
  await load()
}

/** 切层时把只对某层有意义的条件清掉，避免「在章纲层填的卷号带到大纲层导致空结果」的困惑。 */
function onLevelChange() {
  filters.keyword = ''
  filters.volumeNo = null
  filters.fromChapter = null
  filters.toChapter = null
  filters.status = ''
  filters.hasOutline = 'ALL'
  filters.hasText = 'ALL'
  filters.skeleton = 'ALL'
  filters.minChars = null
  filters.maxChars = null
  load()
}

function resetFilters() {
  filters.novelId = null
  filters.sourceType = 'ALL'
  filters.keyword = ''
  filters.volumeNo = null
  filters.fromChapter = null
  filters.toChapter = null
  filters.status = ''
  filters.hasOutline = 'ALL'
  filters.hasText = 'ALL'
  filters.skeleton = 'ALL'
  filters.minChars = null
  filters.maxChars = null
  dateRange.value = null
  filters.sort = 'ORDER_ASC'
  load()
}

const textVisible = ref(false)
const textTitle = ref('')
const textBody = ref('')

function openText(title, scope, body) {
  textTitle.value = `${title} · ${scope}`
  textBody.value = body || '（空）'
  textVisible.value = true
}

async function copyText() {
  try {
    await navigator.clipboard.writeText(textBody.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器未授权剪贴板，请手动选中复制')
  }
}

function reviewRange(row) {
  try {
    const r = JSON.parse(row.review || '{}')
    return `${r.from_no ?? '?'}–${r.to_no ?? '?'} 章`
  } catch {
    return '—'
  }
}

/** 缺口行给个淡色底：这一页的主要用途就是找缺口。 */
function gapRowClass({ row }) {
  if (filters.level === 'OUTLINE') return row.hasOutline ? '' : 'row-notice'
  if (filters.level === 'VOLUME') return row.outlineChapters ? '' : 'row-notice'
  return row.hasOutline ? '' : 'row-notice'
}

function prettyJson(text) {
  if (!text) return '（无）'
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '-'
}

onMounted(() => reloadAll())
</script>
