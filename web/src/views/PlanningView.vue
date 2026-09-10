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
        <div style="margin-bottom: 10px">
          <el-button size="small" type="primary" @click="openAdd">新增章规划</el-button>
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

const planChapters = computed(() => volumes.value.flatMap((v) => v.chapters))

async function loadAll() {
  if (!novelId.value) return
  const s = await api.get(`/api/novels/${novelId.value}/planning/story`)
  story.value = s.content
  volumes.value = await api.get(`/api/novels/${novelId.value}/planning/volumes`)
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
    title: '', goal: '', hook: '', budgetMin: 1800, budgetMax: 2800
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
      await api.put(`/api/planning/chapters/${editing.value.id}/plan`, editing.value)
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
    await api.delete(`/api/planning/chapters/${row.id}/plan`)
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

onMounted(async () => {
  novels.value = await api.get('/api/novels')
  novelId.value = getSelectedNovelId() ?? novels.value[0]?.id
  if (!novels.value.some((n) => n.id === novelId.value)) novelId.value = novels.value[0]?.id
  await loadAll()
})
</script>
