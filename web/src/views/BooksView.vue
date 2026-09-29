<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 12px">
      <div style="display: flex; align-items: center; gap: 10px">
        <b style="font-size: 15px">书籍管理</b>
        <span style="font-size: 12px; color: #999">全部作品在此查询/编辑/删除；「打开」设为工作台当前书。删除为软删（数据库可恢复）。</span>
        <div style="flex: 1" />
        <el-button type="primary" size="small" @click="importOpen = true">导入书籍</el-button>
        <el-button size="small" @click="router.push('/wizard')">＋ 开新书</el-button>
        <el-button size="small" :loading="loading" @click="reloadAll">刷新</el-button>
      </div>
    </el-card>

    <!-- 查询条件：条件全空 = 全量（默认按创建时间倒序，见 filters.sort） -->
    <el-card shadow="never" style="margin-bottom: 10px">
      <el-form :inline="true" size="small" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="filters.keyword" placeholder="书名/简介" clearable style="width: 180px"
                    @keyup.enter="load" @clear="load" @blur="load" />
        </el-form-item>
        <el-form-item label="入库类型">
          <el-select v-model="filters.sourceType" style="width: 140px" @change="load">
            <el-option label="全部类型" value="ALL" />
            <el-option v-for="(label, key) in SOURCE_LABEL" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" style="width: 110px" @change="load">
            <el-option label="全部" value="ALL" />
            <el-option label="正式" value="active" />
            <el-option label="草稿" value="draft" />
          </el-select>
        </el-form-item>
        <el-form-item label="审批模式">
          <el-select v-model="filters.approvalMode" style="width: 110px" @change="load">
            <el-option label="全部" value="ALL" />
            <el-option label="自动" value="auto" />
            <el-option label="人工" value="manual" />
          </el-select>
        </el-form-item>
        <el-form-item label="无人续跑">
          <el-select v-model="filters.autoContinue" style="width: 100px" @change="load">
            <el-option label="全部" value="ALL" />
            <el-option label="已开" value="ON" />
            <el-option label="已关" value="OFF" />
          </el-select>
        </el-form-item>
        <el-form-item label="章数">
          <el-input-number v-model="filters.minChapters" :min="0" :max="99999" :controls="false" placeholder="下限"
                           size="small" style="width: 80px" @change="load" />
          <span style="margin: 0 4px">至</span>
          <el-input-number v-model="filters.maxChapters" :min="0" :max="99999" :controls="false" placeholder="上限"
                           size="small" style="width: 80px" @change="load" />
        </el-form-item>
        <el-form-item label="创建时间">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" unlink-panels
                          start-placeholder="开始" end-placeholder="结束" size="small" style="width: 230px"
                          @change="load" />
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="filters.sort" style="width: 140px" @change="load">
            <el-option label="创建时间倒序" value="TIME_DESC" />
            <el-option label="创建时间正序" value="TIME_ASC" />
            <el-option label="章数多→少" value="CHAPTERS_DESC" />
            <el-option label="书名" value="TITLE_ASC" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="load">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div style="font-size: 12px; color: #999; margin-bottom: 8px">
        命中 <b>{{ books.length }}</b> 本（全库 {{ allBooks.length }} 本）：
        手动导入 {{ sourceCount.IMPORTED }} / 系统衍生 {{ sourceCount.DERIVED }} / 系统纯原创 {{ sourceCount.ORIGINAL }}
      </div>
      <el-table :data="books" border size="small" v-loading="loading" @row-dblclick="(row) => openBook(row)"
                :row-class-name="({ row }) => (row.id === highlightNovelId ? 'book-highlight' : '')">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="书名" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="{ fontWeight: row.id === currentId ? 600 : 400 }">{{ row.title }}</span>
            <el-tag v-if="row.id === currentId" size="small" style="margin-left: 6px">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="入库类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="SOURCE_TYPE[row.sourceType] || 'info'">{{ SOURCE_LABEL[row.sourceType] || row.sourceType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="简介" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="章数" width="70">
          <template #default="{ row }">{{ row.chapterCount }}</template>
        </el-table-column>
        <el-table-column label="审批模式" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.approvalMode === 'auto' ? 'success' : 'warning'">
              {{ row.approvalMode === 'auto' ? '自动' : '人工' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'draft'" size="small" type="info">草稿</el-tag>
            <el-tag v-else size="small">正式</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="无人续跑" width="130">
          <template #default="{ row }">
            <template v-if="row.autoContinue">
              <el-tag size="small" type="success">开</el-tag>
              <span style="font-size: 12px; color: #999; margin-left: 4px">目标 {{ row.targetChapters ?? '∞' }} 章</span>
            </template>
            <span v-else style="color: #999; font-size: 12px">关</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ (row.createTime || '').toString().replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openBook(row)">打开</el-button>
            <el-button v-if="row.status === 'draft'" size="small" type="warning" link
                       @click="router.push('/wizard')">继续向导</el-button>
            <el-button size="small" link @click="openEdit(row)">编辑</el-button>
            <el-button size="small" link @click="openFingerprint(row)">提指纹</el-button>
            <el-button size="small" link @click="openDigestBackfill(row)">补事实账</el-button>
            <el-button size="small" type="danger" link @click="delBook(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有命中的书籍——放宽查询条件再查" />
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="editOpen" title="编辑书籍信息" width="560px">
      <el-form label-width="70px">
        <el-form-item label="书名" required>
          <el-input v-model="editForm.title" maxlength="256" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="editForm.description" type="textarea" :rows="3" placeholder="一句话简介（可选）" />
        </el-form-item>
      </el-form>
      <div style="font-size: 12px; color: #999">
        改文风/门禁/衍生参数/无人续跑不在本页——分别在工作台「本书生成参数」与素材库对应页签。
      </div>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 导入书籍：粘贴正文或上传 txt/mobi/azw，按「第N章」切章入库（入库类型 = 手动导入） -->
    <el-dialog v-model="importOpen" title="导入书籍" width="700px">
      <el-form label-width="90px" size="small">
        <el-form-item label="书名" required>
          <el-input v-model="importForm.title" maxlength="256" placeholder="必填，全站唯一" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="importForm.description" placeholder="可选" />
        </el-form-item>
        <el-form-item label="文风预设">
          <el-select v-model="importForm.presetId" placeholder="可选——不选则按本书正文自动提指纹"
                     filterable clearable style="width: 100%">
            <el-option v-for="p in presets" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <div style="font-size: 12px; color: #999; line-height: 1.6">
            预设决定文风指纹与门禁阈值。<b>不选</b>：导入后按本书正文自动提指纹（草稿需你确认，比任何预设都贴这本书）；
            <b>选了</b>：克隆该预设口径，之后也可随时「提指纹」重校准。
          </div>
        </el-form-item>
        <el-form-item label="正文">
          <TextFileDropZone style="margin-bottom: 6px"
                            sub-hint="支持 txt / docx（.doc 请先另存为 .docx）与无 DRM 的 mobi、azw；也可直接粘贴到下方"
                            @loaded="onImportFileLoaded" />
          <span v-if="importForm.text" style="font-size: 12px; color: #999">
            已载入 {{ (importForm.text.length / 10000).toFixed(1) }} 万字
          </span>
          <span v-else-if="importForm.fileBase64" style="font-size: 12px; color: #999">已载入文档/电子书</span>
          <el-input v-model="importForm.text" type="textarea" :rows="8" style="margin-top: 6px"
                    placeholder="或直接粘贴正文（整本或已有部分）。按行首标题切章：第N章 / 第一章 / 一、标题；识别不到标题则整篇作为第 1 章。" />
        </el-form-item>
        <el-form-item label="补事实账">
          <div>
            <el-checkbox v-model="importForm.digestOn">导入后为最近
              <el-input-number v-model="importForm.digestRecent" :min="1" :max="20" size="small" style="width: 80px; margin: 0 4px" />
              章生成 AI 事实账
            </el-checkbox>
            <div style="font-size: 12px; color: #999">
              事实账是「续写前情链」的唯一来源（卷规划与后续章节都读它）；不生成则续写没有前情，可能与人设/剧情脱节。
              每章一次 LLM 调用（约 1-2 分钟），计入调用台账。
            </div>
          </div>
        </el-form-item>
        <el-form-item label="提指纹">
          <div>
            <el-checkbox v-model="importForm.fingerprintOn" :disabled="!importForm.presetId">
              导入后按本书正文试提文风指纹
            </el-checkbox>
            <div style="font-size: 12px; color: #999; line-height: 1.6">
              <template v-if="!importForm.presetId">
                未选预设——导入后<b>必定</b>按本书正文提指纹（这是本书门禁阈值的唯一来源，必须走这里）。
              </template>
              <template v-else>
                勾选后弹出草稿让你看清指标再决定是否采纳；采纳会覆盖本书指纹（即所选预设口径），后续门禁宽严随之改判。
              </template>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <div style="font-size: 12px; color: #999">
        导入的章节按「导入正文」终态保存、不进生成管线；之后可在「规划」页按卷规划往后接续。
        支持 txt / docx（旧版 .doc 请先另存为 .docx）与无 DRM 的 mobi/azw；DRM 加密与 KF8 新格式会报错，请先转 txt。
      </div>
      <template #footer>
        <el-button @click="importOpen = false">取消</el-button>
        <el-button type="primary" :loading="importing"
                   :disabled="!importForm.title.trim() || (!importForm.text && !importForm.fileBase64)"
                   @click="submitImport">
          {{ importing ? importStatus : '导入并入库' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 按本书正文提指纹：草稿 → 确认 → 采纳（覆盖本书风格包指纹） -->
    <FingerprintDraftDialog v-model="fpOpen" :novel-id="fpTargetId"
                            @applied="onFingerprintApplied" @closed="onFingerprintClosed" />

    <el-dialog v-model="digestOpen" title="补事实账" width="520px">
      <div style="font-size: 13px; line-height: 1.9">
        <div>为《{{ digestTarget ? digestTarget.title : '' }}》最新章节生成 AI 事实账（续写前情链）。</div>
        <div style="margin: 8px 0">
          最近
          <el-input-number v-model="digestRecent" :min="1" :max="20" size="small" style="width: 90px; margin: 0 4px" />
          章 · 每章一次 LLM 调用（约 1-2 分钟）
        </div>
        <div style="color: #999; font-size: 12px">重复补同一章会重复计费；只补新章时把数字调小。</div>
      </div>
      <template #footer>
        <el-button @click="digestOpen = false">取消</el-button>
        <el-button type="primary" :loading="digesting" @click="runDigestBackfill">开始补账</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'
import TextFileDropZone from '../components/TextFileDropZone.vue'
import FingerprintDraftDialog from '../components/FingerprintDraftDialog.vue'

const SOURCE_LABEL = { IMPORTED: '手动导入', DERIVED: '系统衍生', ORIGINAL: '系统纯原创' }
const SOURCE_TYPE = { IMPORTED: 'warning', DERIVED: 'success', ORIGINAL: 'info' }

const router = useRouter()
const books = ref([])
const allBooks = ref([])
const loading = ref(false)
const currentId = ref(getSelectedNovelId())
const editOpen = ref(false)
const editForm = ref({})
const saving = ref(false)
const dateRange = ref(null)
const highlightNovelId = ref(null)
const filters = reactive({
  keyword: '', sourceType: 'ALL', status: 'ALL', approvalMode: 'ALL',
  autoContinue: 'ALL', minChapters: null, maxChapters: null, sort: 'TIME_DESC'
})

const sourceCount = computed(() => ({
  IMPORTED: books.value.filter((b) => b.sourceType === 'IMPORTED').length,
  DERIVED: books.value.filter((b) => b.sourceType === 'DERIVED').length,
  ORIGINAL: books.value.filter((b) => b.sourceType === 'ORIGINAL').length
}))

/** 装配查询串：只带非空条件；sort 永远显式带（后端无 sort 时保持历史 id 序，那个默认序要给全站作品下拉用）。 */
function buildQuery() {
  const p = new URLSearchParams()
  if (filters.keyword && filters.keyword.trim()) p.set('keyword', filters.keyword.trim())
  for (const key of ['sourceType', 'status', 'approvalMode', 'autoContinue']) {
    if (filters[key] && filters[key] !== 'ALL') p.set(key, filters[key])
  }
  if (filters.minChapters !== null && filters.minChapters !== undefined) p.set('minChapters', filters.minChapters)
  if (filters.maxChapters !== null && filters.maxChapters !== undefined) p.set('maxChapters', filters.maxChapters)
  if (dateRange.value && dateRange.value.length === 2) {
    p.set('from', dateRange.value[0])
    p.set('to', dateRange.value[1])
  }
  if (filters.sort) p.set('sort', filters.sort)
  return p.toString()
}

async function load() {
  loading.value = true
  try {
    books.value = await api.get(`/api/novels?${buildQuery()}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 全量拉一次：只用于「全库 N 本」读数（不受筛选影响）。 */
async function loadAll() {
  try {
    allBooks.value = await api.get('/api/novels')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function reloadAll() {
  await loadAll()
  await load()
}

function resetFilters() {
  filters.keyword = ''
  filters.sourceType = 'ALL'
  filters.status = 'ALL'
  filters.approvalMode = 'ALL'
  filters.autoContinue = 'ALL'
  filters.minChapters = null
  filters.maxChapters = null
  filters.sort = 'TIME_DESC'
  dateRange.value = null
  load()
}

/** 打开＝设为工作台当前书并跳转。 */
function openBook(row) {
  setSelectedNovelId(row.id)
  router.push('/chapters')
}

function openEdit(row) {
  editForm.value = { id: row.id, title: row.title, description: row.description || '' }
  editOpen.value = true
}

async function saveEdit() {
  if (!editForm.value.title.trim()) {
    ElMessage.warning('书名必填')
    return
  }
  saving.value = true
  try {
    await api.put(`/api/novels/${editForm.value.id}`, {
      title: editForm.value.title.trim(),
      description: editForm.value.description
    })
    ElMessage.success('已保存')
    editOpen.value = false
    await reloadAll()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function delBook(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `将删除《${row.title}》（${row.chapterCount} 章）。此操作软删该书（可 psql 恢复），正文保留但界面不再可见；本书的风格包若没被别书共用会一并回收（同名书之后再导入/开书会复用它）。请输入完整书名确认：`,
      '删除书籍', { confirmButtonText: '删除', cancelButtonText: '取消', inputPattern: new RegExp(`^${row.title}$`), inputErrorMessage: '书名不匹配' })
    if (value !== row.title) return
  } catch (e) {
    return
  }
  try {
    await api.delete(`/api/novels/${row.id}`)
    ElMessage.success('已删除')
    if (currentId.value === row.id) {
      const rest = books.value.filter((b) => b.id !== row.id)
      setSelectedNovelId(rest.length ? rest[0].id : null)
      currentId.value = rest.length ? rest[0].id : null
    }
    await reloadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ===== 导入书籍（粘贴正文 / 上传 txt、mobi、azw → 切章入库，入库类型 = 手动导入）=====
const importOpen = ref(false)
const importing = ref(false)
const importStatus = ref('导入并入库')
const importForm = reactive({
  title: '', description: '', presetId: null, text: '', fileBase64: '',
  digestOn: true, digestRecent: 3, fingerprintOn: false
})
const presets = ref([])

async function loadPresets() {
  try {
    presets.value = await api.get('/api/preset/list')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 拖拽/选择载入：txt 给文本，电子书给 base64 由后端提取正文；书名空着时用文件名兜底。 */
function onImportFileLoaded({ name, text, fileBase64 }) {
  importForm.text = text
  importForm.fileBase64 = fileBase64
  if (!importForm.title) importForm.title = name
}

/** 两步：先落库（快、无 LLM），再按勾选决定是否补事实账（慢、计费）——两步结果都单独反馈。 */
async function submitImport() {
  importing.value = true
  importStatus.value = '正在导入…'
  highlightNovelId.value = null
  try {
    const r = await api.post('/api/novels/import', {
      title: importForm.title.trim(),
      description: importForm.description,
      presetId: importForm.presetId,
      text: importForm.text,
      fileBase64: importForm.fileBase64 || undefined
    })
    highlightNovelId.value = r.novelId
    ElMessage.success(`已导入《${r.title}》：${r.chapterCount} 章`)
    if (r.notes && r.notes.length) {
      await ElMessageBox.alert(r.notes.join('\n\n'), '导入完成，请注意口径', { confirmButtonText: '知道了' })
    }
    if (importForm.digestOn) {
      importStatus.value = '正在补事实账…'
      const d = await api.post(`/api/novels/${r.novelId}/digest-backfill`, { recent: importForm.digestRecent })
      if (d.notes && d.notes.length) {
        ElMessage.warning(d.notes.join('；'))
      } else {
        ElMessage.success(`事实账已补 ${d.digested} 章——续写前情已就绪`)
      }
    }
    // 是否要在导入后就地提指纹：没选预设（后端 pendingFingerprint）或用户手动勾了，都要走。
    // 注意顺序——下面会重置表单，所以先算好再清。
    const wantFingerprint = r.pendingFingerprint || importForm.fingerprintOn
    importOpen.value = false
    importForm.title = ''
    importForm.description = ''
    importForm.presetId = null
    importForm.text = ''
    importForm.fileBase64 = ''
    importForm.fingerprintOn = false
    await reloadAll()
    if (!books.value.some((b) => b.id === r.novelId)) {
      ElMessage.warning('当前查询条件没命中这本新书——点「重置」即可看到')
    }
    // 提指纹：草稿 → 确认 → 采纳（覆盖本书风格包指纹）。未选预设时这是必走的一步——否则本书无门禁阈值。
    if (wantFingerprint) {
      if (r.pendingFingerprint) {
        ElMessage.info('未选文风预设——接下来按本书正文提指纹，采纳后本书门禁阈值才生效')
      }
      openFingerprint({ id: r.novelId, title: r.title }, r.pendingFingerprint)
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    importing.value = false
    importStatus.value = '导入并入库'
  }
}

// ===== 按本书正文提指纹（导入后 / 任意已有书都能重校准）=====
const fpOpen = ref(false)
const fpTargetId = ref(null)
/** 本次提指纹是否「必须完成」（未选预设导入的书：不采纳就没有门禁阈值）——用于关窗兜底提示。 */
const fpRequired = ref(false)

function openFingerprint(row, required = false) {
  fpTargetId.value = row.id
  fpRequired.value = required
  fpOpen.value = true
}

/** 采纳后刷新列表与指纹页口径一致（指标数变了，列表本身不显示，但保持数据最新）。 */
function onFingerprintApplied() {
  fpRequired.value = false
  ElMessage.info('本书指纹已更新——到「文风指纹」页可看到新的指标数')
}

/** 关窗兜底：未选预设导入的书若没采纳指纹，门禁仍走平台默认值——提醒可到行内「提指纹」补上。 */
function onFingerprintClosed() {
  if (fpRequired.value) {
    fpRequired.value = false
    ElMessage.warning('本书尚未提指纹——门禁暂用平台默认阈值，随时可在列表行点「提指纹」补上')
  }
}

// ===== 补事实账（导入时没补，或后续想给更多章补：续写前情链的来源）=====
const digestOpen = ref(false)
const digesting = ref(false)
const digestRecent = ref(3)
const digestTarget = ref(null)

function openDigestBackfill(row) {
  digestTarget.value = row
  digestRecent.value = 3
  digestOpen.value = true
}

async function runDigestBackfill() {
  digesting.value = true
  try {
    const d = await api.post(`/api/novels/${digestTarget.value.id}/digest-backfill`, { recent: digestRecent.value })
    if (d.requested === 0) {
      ElMessage.warning('这本书还没有正文，无需补事实账')
    } else if (d.notes && d.notes.length) {
      ElMessage.warning(d.notes.join('；'))
    } else {
      ElMessage.success(`事实账已补 ${d.digested} 章`)
    }
    digestOpen.value = false
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    digesting.value = false
  }
}

onMounted(() => {
  loadPresets()
  reloadAll()
})
</script>

<style scoped>
/* 刚导入的那本：默认按创建时间倒序落在首行 */
:deep(.book-highlight td) {
  background: #ecf5ff !important;
}
</style>
