<template>
  <div>
    <div style="display: flex; align-items: center; gap: 14px; margin-bottom: 6px">
      <h3 style="margin: 0">规划</h3>
      <el-select v-model="novelId" style="width: 260px" @change="() => { setSelectedNovelId(novelId); loadAll() }">
        <el-option v-for="n in novels" :key="n.id" :value="n.id" :label="n.title" />
      </el-select>
      <span style="color: #999; font-size: 12px">大纲 / 卷纲 / 章纲 三级管理：大纲进生成上下文，卷纲驱动逐章生成，章纲为 AI 场景拆解</span>
    </div>

    <el-tabs>
      <!-- 大纲 -->
      <el-tab-pane label="大纲">
        <el-input v-model="story" type="textarea" :rows="22" />
        <div style="margin-top: 8px">
          <el-button type="primary" @click="saveStory">保存大纲</el-button>
          <span style="color: #999; font-size: 12px; margin-left: 10px">全书脉络 / 主线 / 卷走向；保存后自动进入每章生成的上下文</span>
        </div>
      </el-tab-pane>

      <!-- 卷纲 -->
      <el-tab-pane :label="`卷纲（${planChapters.length} 章规划）`">
        <div style="margin-bottom: 10px; display: flex; gap: 14px; align-items: center">
          <el-button size="small" type="primary" @click="openAdd">新增章规划</el-button>
          <el-button size="small" type="success" :loading="autoPlanBusy" @click="openAutoPlan">AI 规划下一卷</el-button>
          <span style="display: flex; align-items: center; gap: 6px; color: #999; font-size: 12px">
            卷纲人工审核
            <el-switch v-model="planMode" active-value="manual" inactive-value="auto" @change="switchPlanMode" />
            <span>（自动=AI 审校通过直接落库；人工=出草稿，编辑后采纳）</span>
          </span>
        </div>
        <div v-for="v in volumes" :key="v.volNo" style="margin-bottom: 16px">
          <div style="font-weight: bold; margin-bottom: 6px">
            第 {{ v.volNo }} 卷 · {{ v.arc }}（{{ v.chapters.length }} 章）
          </div>
          <el-table :data="v.chapters" border size="small" style="max-width: 980px">
            <el-table-column prop="chapterNo" label="章" width="60" />
            <el-table-column prop="title" label="标题" width="160" />
            <el-table-column prop="goal" label="目标" min-width="220" show-overflow-tooltip />
            <el-table-column prop="hook" label="钩子" min-width="200" show-overflow-tooltip />
            <el-table-column prop="timeNote" label="时间跨度" width="130" show-overflow-tooltip>
              <template #default="{ row }">{{ row.timeNote || '紧接' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag size="small" :type="row.hasText ? 'success' : 'info'">
                  {{ row.hasText ? '已成文' : row.status }}
                </el-tag>
                <span v-if="row.sceneCount" style="font-size: 11px; color: #999"> {{ row.sceneCount }}场</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220">
              <template #default="{ row }">
                <el-button size="small" @click="openEdit(row)">编辑</el-button>
                <el-button size="small" type="warning" plain :loading="regenBusy" @click="regen(row)">重出章纲</el-button>
                <el-button size="small" type="danger" plain @click="removePlan(row)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 章纲 -->
      <el-tab-pane label="章纲（场景拆解）">
        <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
          <span>查看章节：</span>
          <el-input-number v-model="viewChapterNo" :min="1" size="small" />
          <el-button size="small" @click="loadScenes">查看</el-button>
        </div>
        <el-empty v-if="!scenes.length" description="该章还没有章纲（未生成场景拆解）" :image-size="60" />
        <el-table v-else :data="scenes" border size="small" style="max-width: 900px">
          <el-table-column prop="sceneNo" label="场景" width="70" />
          <el-table-column prop="goal" label="场景目标" min-width="320" />
          <el-table-column label="要素" min-width="300">
            <template #default="{ row }">
              <div v-if="(row.present || []).length" style="font-size: 12px">出场：{{ row.present.join('、') }}</div>
              <div v-if="(row.mustReveal || []).length" style="font-size: 12px; color: #67c23a">必揭示：{{ row.mustReveal.join('、') }}</div>
              <div v-if="(row.mustNot || []).length" style="font-size: 12px; color: #f56c6c">禁出现：{{ row.mustNot.join('、') }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="words" label="预算" width="80" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- AI 规划入参 -->
    <el-dialog v-model="autoPlanOpen" title="AI 规划一卷" width="600px">
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
        <span>卷号</span>
        <el-input-number v-model="autoPlanForm.volNo" :min="1" size="small" style="width: 90px" />
        <span>起始章</span>
        <el-input-number v-model="autoPlanForm.from" :min="1" size="small" style="width: 100px" />
        <span>结束章</span>
        <el-input-number v-model="autoPlanForm.to" :min="autoPlanForm.from || 1" size="small" style="width: 100px" placeholder="AI 自定" />
      </div>
      <el-input v-model="autoPlanForm.seedOutline" type="textarea" :rows="6"
                placeholder="本卷种子大纲（可空——留空则 AI 依据全书大纲与事实账/世界状态/伏笔账自主设计本卷主线，并在卷简报里说明四个关键决策）" />
      <div style="color: #999; font-size: 12px; margin-top: 8px">
        流程：生成 → 结构校验 → AI 规划审校（BLOCKER 自动重写 ≤3 轮）→ 落库；引用到的 proposed 伏笔自动采纳排期。
      </div>
      <template #footer>
        <el-button @click="autoPlanOpen = false">取消</el-button>
        <el-button type="primary" :loading="autoPlanBusy" @click="runAutoPlan">开始规划（约 2-10 分钟）</el-button>
      </template>
    </el-dialog>

    <!-- manual 模式草稿编辑 -->
    <el-dialog v-model="draftOpen" title="卷纲草稿（人工审核）" width="920px" top="4vh">
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 8px">
        <span>卷名</span>
        <el-input v-model="draft.arc" style="width: 200px" size="small" />
        <span style="color: #999; font-size: 12px">可直接编辑；采纳后不再过 AI 审校</span>
      </div>
      <el-input v-model="draft.brief" type="textarea" :rows="4" style="margin-bottom: 10px" placeholder="卷简报" />
      <el-table :data="draft.rows" border size="small" max-height="420">
        <el-table-column prop="no" label="章" width="52" />
        <el-table-column label="标题" width="150">
          <template #default="{ row }"><el-input v-model="row.title" size="small" /></template>
        </el-table-column>
        <el-table-column label="目标" min-width="230">
          <template #default="{ row }"><el-input v-model="row.goal" size="small" type="textarea" :rows="2" autosize /></template>
        </el-table-column>
        <el-table-column label="钩子" min-width="150">
          <template #default="{ row }"><el-input v-model="row.hook" size="small" type="textarea" :rows="2" autosize /></template>
        </el-table-column>
        <el-table-column label="时间" width="110">
          <template #default="{ row }"><el-input v-model="row.timeNote" size="small" /></template>
        </el-table-column>
        <el-table-column label="伏笔" width="90">
          <template #default="{ row }"><el-input v-model="row.foreshadowsText" size="small" placeholder="F 编码" /></template>
        </el-table-column>
        <el-table-column label="预算" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.budgetMin" size="small" :min="600" :max="10000" controls-position="right" style="width: 62px" />
            –
            <el-input-number v-model="row.budgetMax" size="small" :min="600" :max="10000" controls-position="right" style="width: 62px" />
          </template>
        </el-table-column>
      </el-table>
      <div v-if="(autoPlanResult?.warnings || []).length" style="color: #e6a23c; font-size: 12px; margin-top: 6px">
        {{ autoPlanResult.warnings.join('；') }}
      </div>
      <template #footer>
        <el-button @click="draftOpen = false">放弃草稿</el-button>
        <el-button type="primary" :loading="adoptBusy" @click="adoptPlan">采纳落库</el-button>
      </template>
    </el-dialog>

    <!-- 章规划编辑 -->
    <el-dialog v-model="planEditor" :title="editing && editing.id ? '编辑章规划' : '新增章规划'" width="640px">
      <template v-if="editing">
        <div style="display: flex; gap: 10px; margin-bottom: 10px">
          <el-input-number v-model="editing.chapterNo" :min="1" size="small" :disabled="!!editing.id" />
          <el-input-number v-model="editing.volNo" :min="1" size="small" placeholder="卷" />
          <el-input v-model="editing.arc" placeholder="卷名/弧名" size="small" style="width: 180px" />
        </div>
        <el-input v-model="editing.title" placeholder="章节标题" style="margin-bottom: 10px" />
        <el-input v-model="editing.goal" type="textarea" :rows="3" placeholder="本章目标（AI 章纲的种子）" style="margin-bottom: 10px" />
        <el-input v-model="editing.hook" type="textarea" :rows="2" placeholder="章末钩子" style="margin-bottom: 10px" />
        <el-input v-model="editing.timeNote" placeholder="时间跨度（距上一章，如：新年祭后第三日；留空=紧接上一章）" style="margin-bottom: 10px" />
        <div style="display: flex; gap: 10px; align-items: center">
          <span>字数预算</span>
          <el-input-number v-model="editing.budgetMin" :min="500" size="small" />
          -
          <el-input-number v-model="editing.budgetMax" :min="500" size="small" />
        </div>
      </template>
      <template #footer>
        <el-button @click="planEditor = false">取消</el-button>
        <el-button type="primary" @click="savePlan">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'

const novels = ref([])
const novelId = ref(null)
const story = ref('')
const volumes = ref([])
const scenes = ref([])
const viewChapterNo = ref(29)
const editing = ref(null)
const planEditor = ref(false)
const regenBusy = ref(false)
const planMode = ref('auto')
const autoPlanOpen = ref(false)
const autoPlanBusy = ref(false)
const autoPlanForm = ref({ volNo: 1, from: 1, to: null, seedOutline: '' })
const autoPlanResult = ref(null)
const draftOpen = ref(false)
const draft = ref({ arc: '', brief: '', rows: [] })
const adoptBusy = ref(false)

const planChapters = computed(() => volumes.value.flatMap((v) => v.chapters))

async function loadAll() {
  if (!novelId.value) return
  const s = await api.get(`/api/novels/${novelId.value}/planning/story`)
  story.value = s.content
  volumes.value = await api.get(`/api/novels/${novelId.value}/planning/volumes`)
  const m = await api.get(`/api/novels/${novelId.value}/planning/mode`)
  planMode.value = m.planMode
}

async function saveStory() {
  try {
    await api.put(`/api/novels/${novelId.value}/planning/story`, { content: story.value })
    ElMessage.success('大纲已保存（后续生成自动携带）')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openAdd() {
  const maxNo = Math.max(0, ...planChapters.value.map((c) => c.chapterNo))
  const lastVol = volumes.value[volumes.value.length - 1]
  editing.value = {
    chapterNo: maxNo + 1, volNo: lastVol ? lastVol.volNo : 1, arc: lastVol ? lastVol.arc : '',
    title: '', goal: '', hook: '', timeNote: '', budgetMin: 1800, budgetMax: 2800
  }
  planEditor.value = true
}

function openEdit(row) {
  editing.value = { ...row, volNo: volumes.value.find((v) => v.chapters.includes(row))?.volNo ?? 1 }
  planEditor.value = true
}

async function savePlan() {
  try {
    if (editing.value.id) {
      await api.put(`/api/novels/${novelId.value}/planning/chapters/${editing.value.id}/plan`, editing.value)
    } else {
      await api.post(`/api/novels/${novelId.value}/planning/chapters`, editing.value)
    }
    ElMessage.success('已保存')
    planEditor.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removePlan(row) {
  try {
    await ElMessageBox.confirm(`删除第 ${row.chapterNo} 章「${row.title}」的规划？`, '确认', { type: 'warning' })
    await api.delete(`/api/novels/${novelId.value}/planning/chapters/${row.id}/plan`)
    await loadAll()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

async function regen(row) {
  try {
    await ElMessageBox.confirm(
      `为第 ${row.chapterNo} 章「${row.title}」重新生成章纲？将清掉旧的场景拆解（约 1-2 分钟）`, '确认', { type: 'warning' })
  } catch {
    return
  }
  regenBusy.value = true
  try {
    const spec = await api.post(`/api/novels/${novelId.value}/planning/chapters/${row.chapterNo}/outline/regenerate`)
    ElMessage.success(`章纲已生成：${spec.length} 个场景`)
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    regenBusy.value = false
  }
}

async function loadScenes() {
  try {
    scenes.value = await api.get(`/api/novels/${novelId.value}/planning/chapters/${viewChapterNo.value}/scenes`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ===== AI 卷纲规划 =====

async function switchPlanMode(v) {
  try {
    await api.put(`/api/novels/${novelId.value}/planning/plan-mode`, { mode: v })
    ElMessage.success(v === 'auto' ? '已切换为自动（AI 审校通过直接落库）' : '已切换为人工审核（出草稿待采纳）')
  } catch (e) {
    planMode.value = v === 'auto' ? 'manual' : 'auto'
    ElMessage.error(e.message)
  }
}

function openAutoPlan() {
  const maxNo = Math.max(0, ...planChapters.value.map((c) => c.chapterNo))
  const maxVol = Math.max(0, ...volumes.value.map((v) => v.volNo))
  autoPlanForm.value = { volNo: maxVol + 1, from: maxNo + 1, to: null, seedOutline: '' }
  autoPlanOpen.value = true
}

async function runAutoPlan() {
  autoPlanOpen.value = false
  autoPlanBusy.value = true
  ElMessage.info('AI 规划中，约 2-10 分钟，可去工作台看事件流水…')
  try {
    const r = await api.post(`/api/novels/${novelId.value}/planning/volume/auto-plan`, autoPlanForm.value)
    autoPlanResult.value = r
    if (r.adopted) {
      const fs = (r.adoptedForeshadows || []).join('；')
      ElMessage.success(`卷纲已落库：${r.arc}，${r.rows.length} 章${fs ? '；伏笔：' + fs : ''}`)
      await loadAll()
    } else {
      draft.value = {
        arc: r.arc,
        brief: r.brief,
        rows: r.rows.map((row) => ({ ...row, foreshadowsText: (row.foreshadows || []).join(',') }))
      }
      draftOpen.value = true
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    autoPlanBusy.value = false
  }
}

async function adoptPlan() {
  adoptBusy.value = true
  try {
    const r = await api.post(`/api/novels/${novelId.value}/planning/volume/adopt`, {
      volNo: autoPlanForm.value.volNo,
      arc: draft.value.arc,
      brief: draft.value.brief,
      rows: draft.value.rows.map((row) => ({
        no: row.no,
        title: row.title,
        goal: row.goal,
        hook: row.hook,
        timeNote: row.timeNote,
        foreshadows: (row.foreshadowsText || '').split(/[,，]/).map((s) => s.trim()).filter(Boolean),
        budgetMin: row.budgetMin,
        budgetMax: row.budgetMax
      }))
    })
    const fs = (r.adoptedForeshadows || []).join('；')
    ElMessage.success(`已落库 ${r.adoptedChapters} 章${fs ? '；伏笔：' + fs : ''}`)
    draftOpen.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    adoptBusy.value = false
  }
}

onMounted(async () => {
  novels.value = await api.get('/api/novels')
  novelId.value = getSelectedNovelId() ?? novels.value[0]?.id
  if (!novels.value.some((n) => n.id === novelId.value)) novelId.value = novels.value[0]?.id
  await loadAll()
})
</script>
