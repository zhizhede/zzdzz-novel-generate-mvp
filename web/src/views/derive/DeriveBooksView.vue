<template>
  <div>
    <PageHeader title="衍生书库" hint="源书与系统生成书统一列表；「入库新书」走①切章入库，详情页跑②③④素材链。">
      <el-button type="primary" size="small" @click="openIngest">入库新书</el-button>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <FilterBar :loading="loading" @search="load" @reset="resetFilters">
      <el-form-item label="关键字">
        <el-input v-model="filters.keyword" placeholder="书名/作者" clearable
                  style="width: var(--ctrl-w-xl)" @keyup.enter="load" @clear="load" />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="filters.sourceType" style="width: var(--ctrl-w-md)" @change="load">
          <el-option label="源书" value="SOURCE" />
          <el-option label="全部" value="ALL" />
          <el-option label="系统生成" value="GENERATED" />
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
          <el-option label="书名" value="TITLE_ASC" />
        </el-select>
      </el-form-item>
      <template #hit>
        命中 <b>{{ books.length }}</b> 本
      </template>
    </FilterBar>

    <el-card shadow="never">
      <DataTable :data="books" :loading="loading" size="small"
                 @row-click="(row) => router.push(`/derive/books/${row.id}`)">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="书名" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(BOOK_SOURCE, row.sourceType).type">
              {{ statusTag(BOOK_SOURCE, row.sourceType).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="author" label="作者" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.author || '—' }}</template>
        </el-table-column>
        <el-table-column prop="platform" label="平台" width="100">
          <template #default="{ row }">{{ row.platform || '—' }}</template>
        </el-table-column>
        <el-table-column prop="chapterCount" label="章数" width="70" />
        <el-table-column label="字数" width="90">
          <template #default="{ row }">{{ row.charCount != null ? row.charCount.toLocaleString() : '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" type="primary" link
                       @click.stop="router.push(`/derive/books/${row.id}`)">详情</el-button>
          </template>
        </el-table-column>
      </DataTable>
    </el-card>

    <el-dialog v-model="ingestOpen" title="① 取样入库（切章入源书）" width="720px"
               :close-on-click-modal="false">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="书名" required>
          <el-input v-model="ingest.title" placeholder="源书书名" maxlength="200" />
        </el-form-item>
        <el-form-item label="作者">
          <el-input v-model="ingest.author" placeholder="原书作者（可空）" />
        </el-form-item>
        <el-form-item label="平台">
          <el-input v-model="ingest.platform" placeholder="晋江/起点/名著…（可空）" />
        </el-form-item>
        <el-form-item label="类别标签">
          <el-input v-model="ingest.tagsText" placeholder="逗号分隔，如：推理,悬疑（可空）" />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input v-model="ingest.text" type="textarea" :rows="12"
                    placeholder="粘贴全文。自动按「第N章 / 第一章 / 一、标题」切章；前言并入首章，断号自动重排。" />
          <div class="hint-line">{{ ingest.text.length.toLocaleString() }} 字</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ingestOpen = false">取消</el-button>
        <el-button type="primary" :loading="ingesting" @click="submitIngest">入库</el-button>
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
import { BOOK_SOURCE, fmtTime, statusTag } from './deriveLabels'

const router = useRouter()
const loading = ref(false)
const books = ref([])
const dateRange = ref(null)
const filters = ref({ keyword: '', sourceType: 'SOURCE', sort: 'ID_DESC' })

const ingestOpen = ref(false)
const ingesting = ref(false)
const ingest = ref({ title: '', author: '', platform: '', tagsText: '', text: '' })

function buildQuery() {
  const f = filters.value
  const q = new URLSearchParams()
  if (f.keyword?.trim()) q.set('keyword', f.keyword.trim())
  if (f.sourceType) q.set('sourceType', f.sourceType)
  if (dateRange.value?.[0]) q.set('from', dateRange.value[0])
  if (dateRange.value?.[1]) q.set('to', dateRange.value[1])
  if (f.sort) q.set('sort', f.sort)
  const s = q.toString()
  return s ? `?${s}` : ''
}

async function load() {
  loading.value = true
  try {
    books.value = await deriveApi.get(`/api/derive/books${buildQuery()}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.value = { keyword: '', sourceType: 'SOURCE', sort: 'ID_DESC' }
  dateRange.value = null
  load()
}

function openIngest() {
  ingest.value = { title: '', author: '', platform: '', tagsText: '', text: '' }
  ingestOpen.value = true
}

/** "推理,悬疑" → '{"推理":1,"悬疑":1}'（category_tags 是 NOT NULL jsonb，空标签发 '{}'）。 */
function buildCategoryTags(text) {
  const keys = (text || '').split(/[,，、\s]+/).map((s) => s.trim()).filter(Boolean)
  if (!keys.length) return '{}'
  const obj = {}
  for (const k of keys) obj[k] = 1
  return JSON.stringify(obj)
}

async function submitIngest() {
  const f = ingest.value
  if (!f.title.trim()) {
    ElMessage.warning('请填书名')
    return
  }
  if (!f.text.trim()) {
    ElMessage.warning('请粘贴正文')
    return
  }
  ingesting.value = true
  try {
    const r = await deriveApi.post('/api/derive/books', {
      title: f.title.trim(),
      text: f.text,
      author: f.author.trim() || null,
      platform: f.platform.trim() || null,
      categoryTags: buildCategoryTags(f.tagsText)
    })
    ElMessage.success(`入库完成：《${r.title}》 ${r.chapterCount} 章 / ${r.totalChars} 字`)
    ingestOpen.value = false
    filters.value.sourceType = 'SOURCE'
    await load()
    router.push(`/derive/books/${r.novelId}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    ingesting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.hint-line {
  margin-top: 4px;
  font-size: var(--text-xs);
  color: var(--muted);
}
</style>
