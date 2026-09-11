<template>
  <div>
    <div style="display: flex; align-items: center; gap: 14px; margin-bottom: 6px">
      <h3 style="margin: 0">素材库</h3>
      <el-select v-model="novelId" style="width: 260px" @change="() => { setSelectedNovelId(novelId); loadAll() }">
        <el-option v-for="n in novels" :key="n.id" :value="n.id" :label="n.title" />
      </el-select>
      <span style="color: #999; font-size: 12px">所有素材按作品隔离</span>
    </div>

    <el-tabs>
      <!-- 正典 -->
      <el-tab-pane :label="`正典文档（${canon.length}）`">
        <div style="display: flex; gap: 8px; margin-bottom: 10px">
          <el-select v-model="newCanon.kind" style="width: 130px" size="small">
            <el-option value="world" label="world 世界观" />
            <el-option value="character" label="character 人物" />
            <el-option value="misc" label="misc 其他" />
          </el-select>
          <el-input v-model="newCanon.name" placeholder="文档名称" style="width: 200px" size="small" />
          <el-button type="primary" size="small" @click="createCanon">新增文档</el-button>
        </div>
        <el-table :data="canon" border size="small" style="max-width: 680px">
          <el-table-column prop="kind" label="类型" width="110" />
          <el-table-column prop="name" label="名称" width="180" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="small" @click="openCanon(row)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="removeCanon(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 伏笔 -->
      <el-tab-pane :label="`伏笔账本（${foreshadows.length}）`">
        <el-table :data="foreshadows" border size="small" style="max-width: 860px">
          <el-table-column prop="code" label="编号" width="70" />
          <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
          <el-table-column prop="plantedIn" label="埋设章" width="80" />
          <el-table-column prop="recoveredIn" label="回收章" width="80" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="{ planned: 'info', planted: 'warning', recovered: 'success', dropped: 'danger' }[row.status]">
                {{ { planned: '计划', planted: '已埋', recovered: '已收', dropped: '弃用' }[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button size="small" @click="openForeshadow(row)">修正</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 事实账 -->
      <el-tab-pane :label="`事实账（${digests.length}）`">
        <div style="color: #999; font-size: 12px; margin-bottom: 8px">续写前情链的唯一来源——发现摘要与正文不符时在此人工修正</div>
        <el-table :data="digests" border size="small" style="max-width: 860px">
          <el-table-column prop="chapterNo" label="章" width="70" />
          <el-table-column prop="contentMd" label="摘要" min-width="480" show-overflow-tooltip />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button size="small" @click="openDigest(row)">修正</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 世界状态账 -->
      <el-tab-pane :label="`世界状态（${worldStates.length}）`">
        <div style="color: #999; font-size: 12px; margin-bottom: 8px">
          每章一份结构化快照（时间/位置/随身物/新承诺/未解），随事实账自动产出并注入后续生成上下文——写错时在此人工纠偏
        </div>
        <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
          <el-select v-model="wsChapter" placeholder="选择章号" size="small" style="width: 160px" @change="loadWsForEdit">
            <el-option v-for="w in worldStates" :key="w.chapterNo" :value="w.chapterNo" :label="`第${w.chapterNo}章`" />
          </el-select>
          <el-button size="small" @click="backfillWs" :loading="wsBackfilling">对本章重新抽取</el-button>
          <span style="color: #999; font-size: 12px">回填 = 轻量 LLM 调用重做快照，不动事实账</span>
        </div>
        <div style="max-width: 860px">
          <el-input v-model="wsText" type="textarea" :rows="18" placeholder="选择章号后加载快照 JSON" />
          <div style="margin-top: 8px">
            <el-button type="primary" @click="saveWs">保存纠偏</el-button>
            <span style="color: #999; font-size: 12px; margin-left: 10px">必须是合法 JSON 对象；下一次场景生成即注入</span>
          </div>
        </div>
      </el-tab-pane>

      <!-- 风格包 -->
      <el-tab-pane label="风格包">
        <el-tabs v-model="styleTab">
          <el-tab-pane label="规则正文（可编辑）" name="rules">
            <el-input v-model="styleRules" type="textarea" :rows="20" />
            <div style="margin-top: 8px">
              <el-button type="primary" @click="saveStyle">保存规则正文</el-button>
              <span style="color: #999; font-size: 12px; margin-left: 10px">直改生成时的文风指令；指纹阈值不在此改</span>
            </div>
          </el-tab-pane>
          <el-tab-pane label="指纹基线（只读）" name="fingerprint">
            <el-table :data="fingerprintRows" border size="small" style="max-width: 720px">
              <el-table-column prop="metric" label="指标" width="220" />
              <el-table-column prop="value" label="基线值" width="100" />
              <el-table-column prop="tolerance" label="容差" width="100" />
              <el-table-column prop="abs_max" label="天花板" width="100" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="门禁配置（可编辑）" name="gate">
            <div style="max-width: 720px">
              <div style="font-size: 13px; margin-bottom: 6px">AI 腔黑名单（每行一个，正文中出现即判未过）</div>
              <el-input v-model="bannedText" type="textarea" :rows="8" placeholder="心中暗想" />
              <div style="display: flex; gap: 12px; align-items: center; margin: 10px 0">
                <span style="font-size: 13px">章长容差（±）</span>
                <el-input-number v-model="lenTol" :min="0" :max="0.5" :step="0.05" size="small" />
                <span style="color: #999; font-size: 12px">预算 2400-3200、容差 0.15 → 实际允许 2040-3680 字</span>
              </div>
              <el-button type="primary" @click="saveGateConfig">保存门禁配置</el-button>
              <span style="color: #999; font-size: 12px; margin-left: 10px">落库于 style_packs.gate_config，下一次门禁检测即生效</span>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>
    </el-tabs>

    <!-- 正典编辑 -->
    <el-drawer v-model="canonEditor" :title="editing ? `${editing.kind} / ${editing.name}` : ''" size="50%">
      <el-input v-if="editing" v-model="editing.content" type="textarea" :rows="24" />
      <div style="margin-top: 10px">
        <el-button type="primary" @click="saveCanon">保存</el-button>
      </div>
    </el-drawer>

    <!-- 伏笔修正 -->
    <el-dialog v-model="foreshadowEditor" title="修正伏笔" width="560px">
      <template v-if="editing">
        <el-input v-model="editing.content" type="textarea" :rows="3" placeholder="伏笔内容" />
        <div style="display: flex; gap: 10px; margin-top: 10px; align-items: center">
          <span>埋设章 <el-input-number v-model="editing.plantedIn" :min="1" size="small" /></span>
          <span>回收章 <el-input-number v-model="editing.recoveredIn" :min="1" size="small" /></span>
          <el-select v-model="editing.status" size="small" style="width: 110px">
            <el-option value="planned" label="计划" />
            <el-option value="planted" label="已埋" />
            <el-option value="recovered" label="已收" />
            <el-option value="dropped" label="弃用" />
          </el-select>
        </div>
      </template>
      <template #footer>
        <el-button @click="foreshadowEditor = false">取消</el-button>
        <el-button type="primary" @click="saveForeshadow">保存</el-button>
      </template>
    </el-dialog>

    <!-- 事实账修正 -->
    <el-drawer v-model="digestEditor" :title="editing ? `第 ${editing.chapterNo} 章事实账` : ''" size="55%">
      <template v-if="editing">
        <div style="font-size: 12px; color: #999; margin-bottom: 6px">摘要（进入后续章节前情窗口的内容）</div>
        <el-input v-model="editing.contentMd" type="textarea" :rows="8" />
        <div style="font-size: 12px; color: #999; margin: 10px 0 6px">硬事实（JSON 数组，一条一句）</div>
        <el-input v-model="editing.facts" type="textarea" :rows="8" />
        <div style="margin-top: 10px">
          <el-button type="primary" @click="saveDigest">保存</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'

const novels = ref([])
const novelId = ref(null)
const canon = ref([])
const foreshadows = ref([])
const digests = ref([])
const worldStates = ref([])
const wsChapter = ref(null)
const wsText = ref('')
const wsBackfilling = ref(false)
const styleRules = ref('')
const styleFingerprint = ref('')
const bannedText = ref('')
const lenTol = ref(0.15)
const styleTab = ref('rules')
const editing = ref(null)
const canonEditor = ref(false)
const foreshadowEditor = ref(false)
const digestEditor = ref(false)
const newCanon = ref({ kind: 'character', name: '', content: '（待填写）' })

function loadWsForEdit() {
  const row = worldStates.value.find((w) => w.chapterNo === wsChapter.value)
  if (!row) { wsText.value = ''; return }
  try { wsText.value = JSON.stringify(JSON.parse(row.stateJson), null, 2) } catch { wsText.value = row.stateJson }
}

async function saveWs() {
  try {
    JSON.parse(wsText.value)
  } catch {
    ElMessage.error('不是合法 JSON')
    return
  }
  try {
    await api.put(`/api/novels/${novelId.value}/world-states/${wsChapter.value}`, { state: wsText.value })
    ElMessage.success('已保存，下次场景生成即注入')
    worldStates.value = await api.get(`/api/novels/${novelId.value}/world-states`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function backfillWs() {
  if (wsChapter.value == null) return
  wsBackfilling.value = true
  try {
    await api.post(`/api/novels/${novelId.value}/world-states/backfill/${wsChapter.value}`)
    ElMessage.success('已重新抽取')
    worldStates.value = await api.get(`/api/novels/${novelId.value}/world-states`)
    loadWsForEdit()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    wsBackfilling.value = false
  }
}

const fingerprintRows = computed(() => {  try {
    const baseline = JSON.parse(styleFingerprint.value || '{}').baseline || {}
    return Object.entries(baseline).map(([metric, r]) => ({ metric, ...r }))
  } catch {
    return []
  }
})

async function loadAll() {
  if (!novelId.value) return
  canon.value = await api.get(`/api/novels/${novelId.value}/canon`)
  foreshadows.value = await api.get(`/api/novels/${novelId.value}/foreshadows`)
  digests.value = await api.get(`/api/novels/${novelId.value}/digests`)
  worldStates.value = await api.get(`/api/novels/${novelId.value}/world-states`)
  if (wsChapter.value == null && worldStates.value.length) {
    wsChapter.value = worldStates.value[0].chapterNo
    loadWsForEdit()
  }
  const s = await api.get(`/api/novels/${novelId.value}/style`)
  styleRules.value = s.rulesMd
  styleFingerprint.value = s.fingerprintJson
  try {
    const cfg = JSON.parse(s.gateConfigJson || '{}')
    bannedText.value = (cfg.banned_phrases || []).join('\n')
    lenTol.value = typeof cfg.chapter_length_tolerance === 'number' ? cfg.chapter_length_tolerance : 0.15
  } catch {
    bannedText.value = ''
  }
}

async function openCanon(row) {
  editing.value = await api.get(`/api/canon/${row.id}`)
  canonEditor.value = true
}

async function saveCanon() {
  try {
    await api.put(`/api/canon/${editing.value.id}`, { content: editing.value.content })
    ElMessage.success('已保存')
    canonEditor.value = false
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function createCanon() {
  try {
    await api.post(`/api/novels/${novelId.value}/canon`, { ...newCanon.value })
    ElMessage.success('已新增')
    newCanon.value = { kind: newCanon.value.kind, name: '', content: '（待填写）' }
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeCanon(row) {
  try {
    await ElMessageBox.confirm(`删除「${row.name}」？（软删除，可恢复）`, '确认', { type: 'warning' })
    await api.delete(`/api/canon/${row.id}`)
    await loadAll()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

function openForeshadow(row) {
  editing.value = { ...row }
  foreshadowEditor.value = true
}

async function saveForeshadow() {
  try {
    await api.put(`/api/foreshadows/${editing.value.id}`, {
      content: editing.value.content,
      plantedIn: editing.value.plantedIn,
      recoveredIn: editing.value.recoveredIn,
      status: editing.value.status
    })
    ElMessage.success('已修正')
    foreshadowEditor.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openDigest(row) {
  editing.value = { ...row }
  digestEditor.value = true
}

async function saveDigest() {
  try {
    await api.put(`/api/digests/${editing.value.id}`, {
      contentMd: editing.value.contentMd,
      facts: editing.value.facts
    })
    ElMessage.success('已修正（影响后续章节前情窗口）')
    digestEditor.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function saveGateConfig() {
  try {
    const phrases = bannedText.value.split('\n').map((s) => s.trim()).filter(Boolean)
    const cfg = { banned_phrases: phrases, chapter_length_tolerance: lenTol.value, no_straight_quote: true }
    await api.put(`/api/novels/${novelId.value}/gate-config`, { gateConfig: JSON.stringify(cfg) })
    ElMessage.success('门禁配置已落库（下一次门禁检测即生效）')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function saveStyle() {
  try {
    await api.put(`/api/novels/${novelId.value}/style`, { rulesMd: styleRules.value })
    ElMessage.success('风格规则已更新（立即影响后续生成）')
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

watch(novelId, loadAll)
</script>
