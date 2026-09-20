<template>
  <div>
    <div style="display: flex; align-items: center; gap: 14px; margin-bottom: 6px">
      <h3 style="margin: 0">章节</h3>
      <el-select v-model="novelId" style="width: 260px" @change="() => { setSelectedNovelId(novelId); loadChapters() }">
        <el-option v-for="n in novels" :key="n.id" :value="n.id" :label="n.title" />
      </el-select>
    </div>
    <el-table :data="chapters" border size="small" @row-click="open" style="cursor: pointer">
      <el-table-column prop="chapterNo" label="章" width="60" />
      <el-table-column prop="title" label="标题" min-width="160" />
      <el-table-column prop="status" label="状态" width="150">
        <template #default="{ row }"><el-tag size="small" :type="STATUS_COLOR[row.status] || 'info'">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="budgetMin" label="预算" width="100">
        <template #default="{ row }">{{ row.budgetMin }}-{{ row.budgetMax }}</template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawer" :title="detail ? `第${detail.chapterNo}章 ${detail.title}` : ''" size="65%">
      <template v-if="detail">
        <div style="margin-bottom: 8px; display: flex; gap: 12px; align-items: center">
          <el-tag size="small">{{ detail.status }}</el-tag>
          <span v-if="genStd" style="font-size: 12px; color: #999">
            生成时评审：注水软 {{ genStd.reader_fat_ratio_block }} / 硬上限 {{ genStd.reader_fat_ratio_hard }} /
            恢复线 {{ genStd.reader_fix_len_min }} / 扩写护栏 {{ genStd.reader_fix_len_max }} / 审校下限 {{ genStd.ai_review_fix_floor }}
          </span>
          <span v-else style="font-size: 12px; color: #c0c4cc">生成时评审标准未记录（历史章节）</span>
          <span style="font-size: 12px; color: #999">
            LLM：{{ detail.llmTotals?.calls ?? 0 }} 次调用 / {{ detail.llmTotals?.totalTokens ?? 0 }} tokens / 平均 {{ detail.llmTotals?.avgLatencyMs ?? 0 }}ms
          </span>
          <el-button v-if="detail.status === 'PENDING_APPROVAL'" type="success" size="small" :loading="approving" @click="approve">通过审批</el-button>
          <el-button v-if="detail.status === 'PENDING_APPROVAL'" type="danger" size="small" plain @click="rejectChapter">打回…</el-button>
          <el-button v-if="detail.status === 'OUTLINED'" type="success" size="small" @click="outlineDecision('APPROVE')">批准章纲</el-button>
          <el-button v-if="detail.status === 'OUTLINED'" type="danger" size="small" plain @click="outlineDecision('REJECT')">打回章纲…</el-button>
          <el-button v-if="detail.status === 'DIGESTED'" type="danger" size="small" plain @click="vetoChapter">事后否决…</el-button>
          <el-button v-if="detail.status === 'FAILED'" type="warning" size="small" @click="rerunChapter">断点续跑</el-button>
          <el-button v-if="detail.status === 'INTERRUPTED'" type="warning" size="small" @click="rerunChapter">断点续跑</el-button>
          <el-button v-if="detail.fullText" size="small" plain @click="copyText">复制正文</el-button>
          <el-button v-if="detail.fullText" size="small" type="primary" plain @click="reader = true">阅读模式</el-button>
          <el-button v-if="detail.fullText" size="small" type="warning" plain :loading="reviewing" @click="runReview">AI 审校</el-button>
          <el-button v-if="canEditFullText" size="small" type="primary" plain @click="openFullEdit">编辑正文</el-button>
        </div>

        <el-alert v-if="detail.failureBrief" type="error" :title="'失败/中断原因：' + detail.failureBrief"
          :closable="false" style="margin-bottom: 10px" />
        <el-alert v-else-if="detail.rejectReason" type="warning" :title="'上一版打回意见（下次重写生效）：' + detail.rejectReason"
          :closable="false" style="margin-bottom: 10px" />
        <el-alert v-if="detail.digestRunning" type="info" title="事实账（digest）生成中，完成后自动消失" :closable="false" style="margin-bottom: 10px" />

        <el-tabs v-model="activeTab">
          <el-tab-pane label="正文" name="text">
            <div style="white-space: pre-wrap; line-height: 1.9">{{ detail.fullText || '（尚未生成）' }}</div>
          </el-tab-pane>
          <el-tab-pane :label="`场景（${detail.scenes?.length ?? 0}）`" name="scenes">
            <div v-for="s in (detail.scenes || [])" :key="s.id" style="margin-bottom: 14px">
              <div style="font-size: 12px; color: #999; margin-bottom: 4px; display: flex; align-items: center; gap: 8px">
                <span style="flex: 1">场景 {{ s.sceneNo }}｜{{ s.gateStatus }}｜改写 {{ s.revisionRound ?? 0 }} 次｜目标：{{ s.goal }}</span>
                <el-button v-if="canEditScene" size="small" text type="primary" @click="openSceneEdit(s)">编辑</el-button>
              </div>
              <div style="white-space: pre-wrap; border-left: 3px solid #eee; padding-left: 10px">{{ s.draftText }}</div>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`门禁（${detail.gateReport ? (detail.gateReport.passed ? '通过' : '未过') : '无'}）`" name="gates">
            <el-table v-if="detail.gateReport" :data="detail.gateReport.checks || []" border size="small">
              <el-table-column prop="check" label="指标" width="200" />
              <el-table-column prop="value" label="实测" width="100" />
              <el-table-column prop="baseline" label="基线" width="100" />
              <el-table-column prop="abs_max" label="天花板" width="100" />
              <el-table-column label="结果" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.ok ? 'success' : 'danger'">{{ row.ok ? '通过' : '未过' }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`审校（${reviewLabel}）`" name="review">
            <div v-if="readerReviews.length" style="margin-bottom: 18px">
              <div style="font-weight: bold; margin-bottom: 8px">读者评审（全轮次，生成时随章快照口径）</div>
              <div v-for="(r, i) in readerReviews" :key="i"
                style="border: 1px solid #ebeef5; border-radius: 4px; padding: 10px; margin-bottom: 8px">
                <el-tag size="small" :type="r.passed ? 'success' : 'danger'">第 {{ r.round || 1 }} 轮 · {{ r.passed ? '通过' : '未过' }}</el-tag>
                <span style="color: #999; font-size: 12px; margin-left: 6px">{{ fmtTime(r.createTime) }}</span>
                <div style="display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px">
                  <el-tag v-for="q in fiveQuestions(r)" :key="q.label" size="small" effect="plain"
                    :type="q.value === 'pass' ? 'success' : 'danger'">{{ q.label }}：{{ q.value === 'pass' ? '过' : q.value }}</el-tag>
                </div>
                <div v-if="fatRatio(r) != null" style="font-size: 12px; color: #999; margin-top: 6px">注水比 {{ fatRatio(r) }}</div>
                <el-button link size="small" @click="r.open = !r.open">{{ r.open ? '收起' : '展开原文' }}</el-button>
                <pre v-if="r.open" class="call-pre">{{ prettyJson(r.result) }}</pre>
              </div>
            </div>
            <div v-if="!detail.review" style="color: #999; font-size: 13px">
              尚未审校。点击上方「AI 审校」对当前正文跑一次语义审校（连续性/逻辑/错字/格式）。
            </div>
            <template v-else>
              <div style="margin-bottom: 10px; display: flex; gap: 10px; align-items: center">
                <el-tag size="small" :type="VERDICT_COLOR[detail.review.verdict] || 'info'">
                  {{ VERDICT_TEXT[detail.review.verdict] || detail.review.verdict }}
                </el-tag>
                <span style="font-size: 12px; color: #999">{{ detail.review.createTime }}</span>
              </div>
              <div style="font-size: 13px; margin-bottom: 12px">{{ detail.review.summary }}</div>
              <el-table v-if="detail.review.issues?.length" :data="detail.review.issues" border size="small">
                <el-table-column prop="type" label="类型" width="110" />
                <el-table-column label="严重度" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.severity === 'blocker' ? 'danger' : 'warning'">
                      {{ row.severity === 'blocker' ? '严重' : '轻微' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="quote" label="原句" min-width="180" />
                <el-table-column prop="explanation" label="问题" min-width="160" />
                <el-table-column prop="suggestion" label="建议" min-width="160" />
              </el-table>
              <div v-else style="color: #999; font-size: 13px">无问题条目。</div>
            </template>
          </el-tab-pane>
          <el-tab-pane :label="`步骤（${(detail.steps || []).length}）`" name="steps">
            <el-table :data="detail.steps || []" border size="small">
              <el-table-column prop="step" label="步骤" width="120" />
              <el-table-column prop="subKey" label="子项" width="70" />
              <el-table-column prop="attempt" label="尝试" width="60" />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'DONE' ? 'success' : row.status === 'RUNNING' ? 'warning' : 'danger'">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="detail" label="明细" min-width="240" show-overflow-tooltip />
              <el-table-column prop="updateTime" label="时间" width="180" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="流水" name="events">
            <el-table v-if="events.length" :data="events" border size="small">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <pre class="call-pre">{{ prettyJson(parsedJson(row.payloadJson)) }}</pre>
                </template>
              </el-table-column>
              <el-table-column prop="stage" label="阶段" width="120" />
              <el-table-column prop="phase" label="相位" width="100" />
              <el-table-column prop="payloadJson" label="内容" min-width="320" show-overflow-tooltip />
              <el-table-column prop="createTime" label="时间" width="180" />
            </el-table>
            <el-empty v-else description="暂无事件" :image-size="50" />
          </el-tab-pane>
          <el-tab-pane :label="`档案（${(trace?.calls || []).length} 次调用）`" name="trace">
            <div v-if="trace" style="max-height: 620px; overflow-y: auto">
              <div v-if="trace.nodeStats?.length" style="margin-bottom: 12px; display: flex; gap: 6px; flex-wrap: wrap">
                <el-tag v-for="s in trace.nodeStats" :key="s.node" size="small" effect="plain">
                  {{ NODE_LABEL[s.node] || s.node }}：{{ s.calls }} 次 / {{ (s.totalTokens || 0).toLocaleString() }} tok<span v-if="s.cost != null"> / ¥{{ s.cost.toFixed(4) }}</span>
                </el-tag>
              </div>
              <el-timeline v-if="traceTimeline.length">
                <el-timeline-item v-for="(it, i) in traceTimeline" :key="i" :timestamp="fmtTime(it.time)" placement="top"
                  :type="it.kind === 'call' ? 'primary' : it.kind === 'check' ? (it.passed ? 'success' : 'danger') : (it.status === 'DONE' ? 'success' : it.status === 'RUNNING' ? 'warning' : 'danger')">
                  <div v-if="it.kind === 'step'">
                    <b>{{ STEP_LABEL[it.step] || it.step }}</b><span v-if="it.subKey"> · 场景 {{ it.subKey }}</span>
                    <el-tag size="small" style="margin-left: 6px" :type="it.status === 'DONE' ? 'success' : it.status === 'RUNNING' ? 'warning' : 'danger'">{{ it.status }}</el-tag>
                    <span v-if="it.attempt > 1" style="color: #999; font-size: 12px; margin-left: 4px">第 {{ it.attempt }} 次尝试</span>
                    <div v-if="it.detail" style="font-size: 12px; color: #999; margin-top: 2px">{{ it.detail }}</div>
                  </div>
                  <div v-else-if="it.kind === 'call'">
                    <b>{{ NODE_LABEL[it.node] || it.node }}</b>
                    <span style="color: #999; font-size: 12px">
                      {{ (it.totalTokens || 0).toLocaleString() }} tok · {{ (it.latencyMs / 1000).toFixed(1) }}s<span v-if="it.cost != null"> · ¥{{ it.cost.toFixed(4) }}</span><span v-if="it.status === 'error'"> · 失败</span>
                    </span>
                    <el-button link type="primary" size="small" @click="openCall(it.id)">查看 prompt / 输出</el-button>
                  </div>
                  <div v-else>
                    <b>{{ GATE_LABEL[it.gateType] || it.gateType }}</b><span v-if="it.sceneId"> · 场景级</span> · 第 {{ it.round || 1 }} 轮
                    <el-tag size="small" style="margin-left: 6px" :type="it.passed ? 'success' : 'danger'">{{ it.passed ? '通过' : '未过' }}</el-tag>
                    <el-button link size="small" @click="it.open = !it.open">{{ it.open ? '收起' : '展开明细' }}</el-button>
                    <pre v-if="it.open" style="white-space: pre-wrap; font-size: 12px; color: #666; background: #fafafa; padding: 8px; margin-top: 6px; max-height: 300px; overflow-y: auto">{{ prettyJson(it.result) }}</pre>
                  </div>
                </el-timeline-item>
              </el-timeline>
              <el-empty v-else description="本章无档案数据" :image-size="50" />
            </div>
            <el-empty v-else description="加载中…" :image-size="50" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>

    <!-- 阅读模式：全屏沉浸 -->
    <el-dialog v-model="reader" :title="detail ? `第${detail.chapterNo}章 ${detail.title}` : ''" fullscreen
      style="background: #faf6ef">
      <div style="max-width: 720px; margin: 0 auto; padding: 24px 0 60px">
        <div v-if="detail" style="white-space: pre-wrap; font-size: 17px; line-height: 2.1;
          font-family: 'Source Han Serif SC', 'Noto Serif SC', serif; color: #2c2c2c">{{ detail.fullText }}</div>
        <div style="text-align: center; color: #bbb; margin-top: 32px">— 完 —</div>
      </div>
    </el-dialog>

    <!-- Q5a：场景草稿人工编辑（生成前状态），保存即重过该场景机械门禁 -->
    <el-dialog v-model="sceneDialog" :title="editingScene ? `编辑场景 ${editingScene.sceneNo} 草稿` : ''" width="720px" top="6vh">
      <div style="font-size: 12px; color: #999; margin-bottom: 8px">
        保存后立即重过该场景机械门禁（通过/未过会回写场景门禁状态）；续跑时已通过场景复用此稿
      </div>
      <el-input v-model="sceneDraft" type="textarea" :rows="16" maxlength="20000" show-word-limit />
      <template #footer>
        <el-button @click="sceneDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" :disabled="!sceneDraft.trim()" @click="saveSceneEdit">保存并重过门禁</el-button>
      </template>
    </el-dialog>

    <!-- Q5b：正文人工编辑（仅待审批/已 digest）；DIGESTED 保存后回待审批、digest 重算 -->
    <el-dialog v-model="fullDialog" :title="detail ? `编辑第${detail.chapterNo}章正文` : ''" width="860px" top="4vh">
      <div v-if="detail?.status === 'DIGESTED'" style="font-size: 12px; color: #e6a23c; margin-bottom: 8px">
        本章已有事实账：保存后旧 digest 作废、章节回到待审批，重新审批时重算事实账
      </div>
      <div v-else style="font-size: 12px; color: #999; margin-bottom: 8px">人工修正正文，保存后停留在待审批</div>
      <el-input v-model="fullDraft" type="textarea" :rows="24" maxlength="60000" show-word-limit />
      <template #footer>
        <el-button @click="fullDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" :disabled="!fullDraft.trim()" @click="saveFullEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 档案 tab：单次 LLM 调用详情（完整 prompt 分段 + 思考 + 输出，即「AI 当时看到/说了什么」） -->
    <el-dialog v-model="callDialog" top="4vh" width="860px"
      :title="callDetail ? `${NODE_LABEL[callDetail.node] || callDetail.node} · 调用 #${callDetail.id}` : ''">
      <div v-if="callDetail" style="max-height: 72vh; overflow-y: auto">
        <div style="color: #999; font-size: 12px; margin-bottom: 8px">
          {{ callDetail.model }} · {{ (callDetail.totalTokens || 0).toLocaleString() }} tok（缓存命中 {{ callDetail.cachedTokens || 0 }}）·
          {{ (callDetail.latencyMs / 1000).toFixed(1) }}s<span v-if="callDetail.cost != null"> · ¥{{ callDetail.cost.toFixed(4) }}</span> · {{ fmtTime(callDetail.createTime) }}
        </div>
        <el-collapse>
          <el-collapse-item v-for="(m, i) in callDetail.promptMessages || []" :key="'p' + i"
            :title="`Prompt · ${m.role}（${(m.content || '').length} 字）`">
            <pre class="call-pre">{{ m.content }}</pre>
          </el-collapse-item>
          <el-collapse-item v-if="callDetail.reasoningText" :title="`思考过程（${callDetail.reasoningText.length} 字）`">
            <pre class="call-pre" style="color: #8a8f99">{{ callDetail.reasoningText }}</pre>
          </el-collapse-item>
          <el-collapse-item v-if="callDetail.content" :title="`输出正文（${callDetail.content.length} 字）`">
            <pre class="call-pre">{{ callDetail.content }}</pre>
          </el-collapse-item>
          <el-collapse-item v-if="callDetail.errorMsg" title="错误信息">
            <pre class="call-pre" style="color: #c45656">{{ callDetail.errorMsg }}</pre>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'
import { NODE_LABEL, GATE_LABEL, STEP_LABEL } from '../labels'

const STATUS_COLOR = { DIGESTED: 'success', APPROVED: 'success', FAILED: 'danger', PENDING_APPROVAL: 'warning', NEW: 'info', OUTLINED: '', OUTLINE_APPROVED: 'success', GATE_MECHANICAL: '', GATE_AI_REVIEW: 'warning', INTERRUPTED: 'info' }
const VERDICT_TEXT = { pass: '通过', minor: '轻微', blocker: '严重', skipped: '跳过' }
const VERDICT_COLOR = { pass: 'success', minor: 'warning', blocker: 'danger', skipped: 'info' }

const chapters = ref([])
const novels = ref([])
const novelId = ref(null)
const detail = ref(null)
const drawer = ref(false)
const reader = ref(false)
const activeTab = ref('text')
const reviewing = ref(false)
const approving = ref(false)
const events = ref([])

// ===== 档案 tab：章生成档案（trace）=====
const trace = ref(null)
const callDialog = ref(false)
const callDetail = ref(null)
/** 三路数据源按时间合并成一条时间线（ISO 字符串可直接字典序排序）。 */
const traceTimeline = computed(() => {
  if (!trace.value) return []
  const items = []
  for (const s of trace.value.steps || []) {
    items.push({ kind: 'step', time: s.updateTime || s.createTime, ...s })
  }
  for (const c of trace.value.calls || []) {
    items.push({ kind: 'call', time: c.createTime, ...c })
  }
  for (const k of trace.value.checks || []) {
    items.push({ kind: 'check', time: k.createTime, open: false, ...k })
  }
  items.sort((a, b) => String(a.time || '').localeCompare(String(b.time || '')))
  return items
})

async function loadTrace() {
  if (!detail.value) return
  try {
    trace.value = await api.get(`/api/chapters/${detail.value.id}/trace`)
  } catch {
    trace.value = null
  }
}

async function openCall(id) {
  try {
    callDetail.value = await api.get(`/api/llm-logs/${id}`)
    callDialog.value = true
  } catch (e) {
    ElMessage.error('调用详情加载失败：' + e.message)
  }
}

function prettyJson(v) {
  if (v == null) return ''
  return typeof v === 'string' ? v : JSON.stringify(v, null, 2)
}

function parsedJson(s) {
  try { return JSON.parse(s) } catch { return s }
}

/** 读者评审五问（hook/stakes/continuity/consequence）摘要标签。 */
function fiveQuestions(r) {
  const res = r.result || {}
  return [
    { label: '开场钩子', value: String(res.hook ?? '-') },
    { label: '利害赌注', value: String(res.stakes ?? '-') },
    { label: '连贯性', value: String(res.continuity ?? '-') },
    { label: '后果承接', value: String(res.consequence ?? '-') },
  ]
}

function fatRatio(r) {
  const v = r.result?.fat_ratio
  return v == null ? null : (typeof v === 'number' ? v.toFixed(2) : v)
}

const readerReviews = computed(() => (trace.value?.checks || []).filter((c) => c.gateType === 'reader_review'))

function fmtTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  return isNaN(d) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

const reviewLabel = computed(() => {
  const r = detail.value?.review
  return r ? (VERDICT_TEXT[r.verdict] || r.verdict) : '未审'
})

/** Q5a 场景编辑口径：与后端一致——生成中/已收尾状态不可编辑。 */
const canEditScene = computed(() =>
  !['GATE_MECHANICAL', 'GATE_AI_REVIEW', 'REVISING', 'PENDING_APPROVAL', 'DIGESTED'].includes(detail.value?.status))
/** Q5b 正文编辑口径：仅待审批/已 digest。 */
const canEditFullText = computed(() => ['PENDING_APPROVAL', 'DIGESTED'].includes(detail.value?.status))

const sceneDialog = ref(false)
const editingScene = ref(null)
const sceneDraft = ref('')
const fullDialog = ref(false)
const fullDraft = ref('')
const savingEdit = ref(false)

function openSceneEdit(s) {
  editingScene.value = s
  sceneDraft.value = s.draftText || ''
  sceneDialog.value = true
}

async function saveSceneEdit() {
  savingEdit.value = true
  try {
    const passed = await api.put(`/api/scenes/${editingScene.value.id}/edit`, { reason: sceneDraft.value })
    ElMessage.success(passed ? '场景已保存，机械门禁通过' : '场景已保存，机械门禁未过（门禁状态已回写，可继续修改）')
    sceneDialog.value = false
    detail.value = await api.get(`/api/chapters/${detail.value.id}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    savingEdit.value = false
  }
}

function openFullEdit() {
  fullDraft.value = detail.value.fullText || ''
  fullDialog.value = true
}

async function saveFullEdit() {
  savingEdit.value = true
  try {
    const wasDigested = detail.value.status === 'DIGESTED'
    await api.put(`/api/chapters/${detail.value.id}/fulltext`, { reason: fullDraft.value })
    ElMessage.success(wasDigested ? '正文已保存；旧事实账作废，章节回到待审批' : '正文已保存')
    fullDialog.value = false
    detail.value = await api.get(`/api/chapters/${detail.value.id}`)
    await loadChapters()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    savingEdit.value = false
  }
}

/** 生成时的评审标准快照（历史章节无记录时为 null）。 */
const genStd = computed(() => {
  try {
    return JSON.parse(detail.value?.reviewConfig || 'null')
  } catch {
    return null
  }
})

async function loadChapters() {
  try {
    chapters.value = await api.get(`/api/novels/${novelId.value}/chapters`)
  } catch (e) {
    ElMessage.error('章节列表加载失败：' + e.message)
  }
}

async function rerunChapter() {
  const novel = novels.value.find((n) => n.id === novelId.value)
  try {
    await api.post('/api/pipeline/run', { novel: novel.title, from: detail.value.chapterNo, to: detail.value.chapterNo })
    ElMessage.success('已断点续跑，进度见工作台')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 流 A：打回（PENDING_APPROVAL）——意见注入下次章纲重写。 */
async function rejectChapter() {
  try {
    const { value } = await ElMessageBox.prompt('打回意见将注入下次章纲重写，清空现有场景与正文', '打回本章', {
      inputPlaceholder: '例如：结尾钩子不对，前两场景可以保留',
      inputPattern: /\S/,
      inputErrorMessage: '打回意见不能为空',
    })
    await api.post(`/api/chapters/${detail.value.id}/reject`, { reason: value })
    ElMessage.success('已打回并重新入队，意见将在重写时生效')
    detail.value = await api.get(`/api/chapters/${detail.value.id}`)
    await loadChapters()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

/** 流 A：章纲卡点（manual）——批准放行 / 打回重出。 */
async function outlineDecision(action) {
  try {
    let reason = null
    if (action === 'REJECT') {
      const r = await ElMessageBox.prompt('打回意见将注入下次章纲重写', '打回章纲', {
        inputPattern: /\S/,
        inputErrorMessage: '打回意见不能为空',
      })
      reason = r.value
    }
    await api.post(`/api/chapters/${detail.value.id}/outline-decision`, { action, reason })
    ElMessage.success(action === 'APPROVE' ? '章纲已批准，生成继续' : '章纲已打回，重出中')
    detail.value = await api.get(`/api/chapters/${detail.value.id}`)
    await loadChapters()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

/** 流 A 扩展·事后否决：DIGESTED 章打回，清除本章事实账，重生成后 digest 重算。 */
async function vetoChapter() {
  try {
    await ElMessageBox.confirm(
      '否决将清除本章事实账/正文/场景并重新生成；重算后 digest 覆盖原行。继续？',
      '事后否决', { type: 'warning', confirmButtonText: '否决重写' })
    const { value } = await ElMessageBox.prompt('否决意见将注入下次章纲重写', '否决意见', {
      inputPattern: /\S/, inputErrorMessage: '否决意见不能为空' })
    await api.post(`/api/chapters/${detail.value.id}/veto`, { reason: value })
    ElMessage.success('已否决并重新入队，digest 将在重写完成后重算')
    detail.value = await api.get(`/api/chapters/${detail.value.id}`)
    await loadChapters()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

/** 流水 tab：本章事件（懒加载）。 */
async function loadEvents() {
  try {
    events.value = await api.get(`/api/llm-logs/events?novelId=${novelId.value}&chapterNo=${detail.value.chapterNo}&limit=100`)
  } catch {
    events.value = []
  }
}

watch(activeTab, (tab) => {
  if (tab === 'events' && detail.value && !events.value.length) loadEvents()
  if ((tab === 'trace' || tab === 'review') && detail.value && !trace.value) loadTrace()
})

async function runReview() {
  reviewing.value = true
  try {
    await api.post(`/api/chapters/${detail.value.id}/review`)
    detail.value = await api.get(`/api/chapters/${detail.value.id}`)
    activeTab.value = 'review'
    ElMessage.success('审校完成')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    reviewing.value = false
  }
}

async function copyText() {
  await navigator.clipboard.writeText(detail.value.fullText || '')
  ElMessage.success('正文已复制')
}

async function open(row) {
  try {
    detail.value = await api.get(`/api/chapters/${row.id}`)
  } catch (e) {
    ElMessage.error('章节详情加载失败：' + e.message)
    return
  }
  trace.value = null // 换章后档案重新懒加载
  activeTab.value = detail.value.fullText ? 'text' : 'scenes'
  drawer.value = true
}

async function approve() {
  approving.value = true
  try {
    // 审批接口立即返回（占位），digest（事实账/世界状态）后台异步生成，失败会自动回退待审批
    await api.post(`/api/chapters/${detail.value.id}/approve`)
    ElMessage.success('已过审；事实账后台生成中（约 1 分钟）')
    detail.value = await api.get(`/api/chapters/${detail.value.id}`)
    await loadChapters()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    approving.value = false
  }
}

onMounted(async () => {
  novels.value = await api.get('/api/novels')
  novelId.value = getSelectedNovelId() ?? novels.value[0]?.id
  if (!novels.value.some((n) => n.id === novelId.value)) novelId.value = novels.value[0]?.id
  await loadChapters()
})
</script>

<style scoped>
.call-pre {
  white-space: pre-wrap;
  font-size: 12px;
  line-height: 1.8;
  font-family: inherit;
  background: #fafafa;
  padding: 8px;
  margin: 4px 0;
  max-height: 360px;
  overflow-y: auto;
}
</style>
