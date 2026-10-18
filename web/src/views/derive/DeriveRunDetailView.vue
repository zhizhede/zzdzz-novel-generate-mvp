<template>
  <div v-loading="loading">
    <PageHeader :title="`Run #${route.params.id}${detail ? ' · ' + (detail.novelTitle || '未挂成品书') : ''}`">
      <el-button size="small" @click="router.push('/derive/runs')">← 返回列表</el-button>
      <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
      <el-button v-if="detail?.novelId" size="small" type="primary" link
                 @click="router.push(`/derive/books/${detail.novelId}`)">打开成品书 →</el-button>
      <template #hint>
        <el-tag v-if="detail" size="small" :type="statusTag(RUN_STATUS, detail.status).type">
          {{ statusTag(RUN_STATUS, detail.status).label }}
        </el-tag>
      </template>
    </PageHeader>

    <template v-if="detail">
      <el-card shadow="never" class="block action-bar">
        <div class="action-bar__row">
          <el-tag size="large" :type="statusTag(RUN_STATUS, detail.status).type">
            {{ statusTag(RUN_STATUS, detail.status).label }}
          </el-tag>
          <span class="guide">{{ guide }}</span>
          <span class="action-bar__spacer" />

          <template v-if="detail.status === 'PLANNING' && !detail.novelId">
            <el-button type="primary" :loading="acting === 'plan'"
                       :disabled="!!acting" @click="actPlan">⑥a 章节计划</el-button>
          </template>
          <template v-else-if="detail.status === 'PLANNING'">
            <el-button type="primary" :loading="acting === 'draft'"
                       :disabled="!!acting || !!serverDraft" @click="actDraft(false)">⑥b 生成具体稿</el-button>
          </template>
          <template v-else-if="detail.status === 'DRAFTING'">
            <el-button v-if="!gatePassed" type="warning" :loading="acting === 'gate'"
                       :disabled="!!acting" @click="actGate">P6 立项闸（大纲评审）</el-button>
            <el-tag v-else size="large" type="success">立项闸 PASS · 第 {{ gateInfo?.round ?? 1 }} 轮</el-tag>
            <el-button :loading="acting === 'write'" :disabled="!!acting || writeBusy || !gatePassed"
                       @click="actWriteOne">⑦ 写下一章</el-button>
            <el-button type="primary" :disabled="!!acting || writeBusy || !gatePassed"
                       @click="actWriteAll">▶ 连续写完（服务端）</el-button>
          </template>
          <template v-else-if="detail.status === 'QC'">
            <el-button type="primary" :loading="acting === 'qc'"
                       :disabled="!!acting" @click="actQc">⑧ 运行质检</el-button>
          </template>
          <template v-else-if="detail.status === 'REWORK'">
            <el-button type="warning" :loading="acting === 'draft'"
                       :disabled="!!acting || !!serverDraft" @click="actDraft(true)">⑥b 回炉重写具体稿</el-button>
          </template>

          <el-button v-if="writeBusy" type="danger" plain @click="stopWriteLoop">
            ■ 停止（当前章写完即停）
          </el-button>
          <el-button :loading="loading" :disabled="writeBusy" @click="loadAll">刷新</el-button>
        </div>
        <p v-if="draftLoopText" class="loop-text">{{ draftLoopText }}</p>
        <p v-else-if="serverWriteText" class="loop-text loop-text--live">{{ serverWriteText }}</p>
        <p v-else-if="serverDraftText" class="loop-text loop-text--live">{{ serverDraftText }}</p>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          运行过程
          <el-tag v-if="serverDraft" size="small" type="primary" style="margin-left: 8px">进行中</el-tag>
        </template>
        <div v-if="serverWriteText || serverDraftText" class="process-live">{{ serverWriteText || serverDraftText }}</div>
        <div v-else class="process-idle">当前没有进行中的 LLM 任务；下表是本 run 最近的调用账本（服务端事实，刷新/换页都在）。</div>
        <DataTable :data="llmTrail" size="small" style="margin-top: 8px">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="环节" width="150">
            <template #default="{ row }">{{ nodeLabel(row.node) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ Math.round((row.latencyMs || 0) / 1000) }}s</template>
          </el-table-column>
          <el-table-column label="tokens（入/出/总）" width="150">
            <template #default="{ row }">{{ row.promptTokens }} / {{ row.completionTokens }} / {{ row.totalTokens }}</template>
          </el-table-column>
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ok' ? 'success' : 'danger'">
                {{ row.status === 'ok' ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="错误" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.error || '—' }}</template>
          </el-table-column>
        </DataTable>
      </el-card>

      <el-row :gutter="12" class="stat-row">
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat-label">章进度（已写 / 总数）</div>
            <div class="stat-value">{{ detail.progress.chapterWritten }} / {{ detail.progress.chapterTotal }}</div>
            <el-progress :percentage="pct(detail.progress.chapterWritten, detail.progress.chapterTotal)"
                         :stroke-width="8" />
            <div class="stat-sub">其中质检通过 {{ detail.progress.chapterQcPass }} 章</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat-label">拍进度（已核销 / 总数）</div>
            <div class="stat-value">{{ detail.progress.beatVerified }} / {{ detail.progress.beatTotal }}</div>
            <el-progress :percentage="pct(detail.progress.beatVerified, detail.progress.beatTotal)"
                         :stroke-width="8" status="success" />
            <div class="stat-sub">已写入 {{ detail.progress.beatWritten }} · 跳过 {{ detail.progress.beatSkipped }}</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat-label">质检</div>
            <div class="stat-value">
              <el-tag size="small" :type="statusTag(VERDICT, detail.qc.latestVerdict).type">
                {{ statusTag(VERDICT, detail.qc.latestVerdict).label }}
              </el-tag>
              <span class="stat-inline">共 {{ detail.qc.reportCount }} 轮</span>
            </div>
            <div class="stat-sub">预测 {{ detail.qc.wordsPred ?? '—' }} / 实测 {{ detail.qc.wordsActual ?? '—' }} 字</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never">
            <div class="stat-label">参数快照</div>
            <div class="stat-sub">seed {{ detail.seed ?? '—' }} · 字数带 {{ detail.wordsMin ?? '—' }}~{{ detail.wordsMax ?? '—' }}</div>
            <div class="stat-sub">{{ detail.form || '—' }} · 结局 {{ detail.outcome || '—' }} · 节奏 {{ detail.pace ?? '—' }}</div>
            <div class="stat-sub">
              防复刻 <el-tag size="small" :type="detail.antiRepro ? 'warning' : 'info'">{{ detail.antiRepro ? '开' : '关' }}</el-tag>
              字数校验 <el-tag size="small" :type="detail.lenCheck ? 'warning' : 'info'">{{ detail.lenCheck ? '开' : '关' }}</el-tag>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-card v-if="detail.intent || detail.style" shadow="never" class="block">
        <template #header>创作思路 / 文风</template>
        <p v-if="detail.intent" class="pre">{{ detail.intent }}</p>
        <p v-if="detail.style" class="pre">文风：{{ detail.style }}</p>
      </el-card>

      <el-card v-if="gateInfo && gateInfo.status !== 'PENDING'" shadow="never" class="block">
        <template #header>
          P6 立项闸
          <el-tag size="small" :type="gateTagType(gateInfo.status)" style="margin-left: 8px">
            {{ gateStatusLabel(gateInfo.status) }}
          </el-tag>
          <span class="stat-inline" style="margin-left: 8px">第 {{ gateInfo.round ?? 1 }} 轮评审</span>
        </template>
        <template v-if="(gateInfo.issues || []).length">
          <div class="stat-label">问题清单（{{ gateInfo.issues.length }}）</div>
          <ul class="gate-issues">
            <li v-for="(it, i) in gateInfo.issues" :key="i">{{ it }}</li>
          </ul>
        </template>
        <p v-if="gateInfo.directive" class="pre"><b>减法指令（⑥b 回炉按此执行）：</b>{{ gateInfo.directive }}</p>
        <p v-if="gateInfo.status === 'REWORK'" class="pre" style="color: var(--el-color-warning)">
          闸已打回：点上方「⑥b 回炉重写具体稿」，完成后需重新过闸。
        </p>
        <p v-if="gateInfo.status === 'FAILED'" class="pre" style="color: var(--el-color-danger)">
          闸已熔断（REJECT 或回炉超轮次）——run 转 FAILED，需人工处理。
        </p>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          章进度
          <el-button size="small" link type="primary" style="margin-left: 10px" @click="toggleText">
            {{ withText ? '隐藏正文列' : '附带正文加载' }}
          </el-button>
        </template>
        <DataTable :data="chapters" size="small">
          <el-table-column prop="chapterNo" label="章号" width="60" />
          <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(CHAPTER_STATUS, row.status).type">
                {{ statusTag(CHAPTER_STATUS, row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="budget" label="预算" width="70" />
          <el-table-column prop="charCount" label="实测" width="70" />
          <el-table-column label="正文" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.hasText ? 'success' : 'info'">{{ row.hasText ? '有' : '无' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="comment" label="章注" min-width="140" show-overflow-tooltip />
          <el-table-column v-if="withText" label="正文预览" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ (row.content || '').slice(0, 80) || '—' }}</template>
          </el-table-column>
        </DataTable>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>拍实例（{{ beats.length }}）</template>
        <DataTable :data="beats" size="small">
          <el-table-column prop="seq" label="拍序" width="60" />
          <el-table-column prop="beatType" label="拍型" width="80" />
          <el-table-column prop="chapterNo" label="章号" width="60" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(BEAT_STATUS, row.status).type">
                {{ statusTag(BEAT_STATUS, row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="pacing" label="节奏" width="60" />
          <el-table-column prop="filledText" label="填实情节点" min-width="260" show-overflow-tooltip />
        </DataTable>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>质检报告（{{ reports.length }} 轮，最新在前）</template>
        <el-empty v-if="!reports.length" description="还没有质检记录" :image-size="60" />
        <el-collapse v-else>
          <el-collapse-item v-for="r in reports" :key="r.id" :name="r.id">
            <template #title>
              <el-tag size="small" :type="statusTag(VERDICT, r.verdict).type" style="margin-right: 8px">
                {{ statusTag(VERDICT, r.verdict).label }}
              </el-tag>
              <span>第 {{ r.roundNo }} 轮 · {{ fmtTime(r.createTime) }}</span>
            </template>
            <el-tabs>
              <el-tab-pane label="问题清单">
                <pre class="json">{{ pretty(r.issues) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="脚本硬判">
                <pre class="json">{{ pretty(r.scriptResult) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="LLM 快检">
                <pre class="json">{{ pretty(r.llmResult) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="修复记录">
                <pre class="json">{{ pretty(r.repairLog) }}</pre>
              </el-tab-pane>
            </el-tabs>
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../../components/PageHeader.vue'
import DataTable from '../../components/DataTable.vue'
import { deriveApi } from '../../deriveApi'
import { BEAT_STATUS, CHAPTER_STATUS, RUN_STATUS, VERDICT, fmtTime, statusTag } from './deriveLabels'

/** run 详情：进度聚合卡 + 章/拍表 + 质检报告折叠面板（只读监控核心页）。 */
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const detail = ref(null)
const chapters = ref([])
const beats = ref([])
const reports = ref([])
const withText = ref(false)
const serverDraft = ref(null)
const llmTrail = ref([])

const NODE_LABELS = {
  derive_intake_review: '思路预审',
  derive_word_band: 'AI 估档',
  derive_tag_norm: '类别归一化',
  derive_weld: '素材焊接',
  derive_plan: '⑥a 章节计划',
  derive_draft: '⑥b 具体稿',
  derive_gate: 'P6 立项闸',
  derive_write: '⑦ 写正文',
  derive_qc: '⑧ 质检',
  derive_extract: '② 抽取',
  derive_deindex: '③ 无指向化',
  derive_source_words: '④a 扫词',
  derive_label: '④b 打标',
  derive_domain: '④c 域格',
  derive_style: '④d 文风'
}
function nodeLabel(node) {
  return NODE_LABELS[node] || node
}

/** 长任务实时活动（⑥b/闸/⑦/⑧ 共用 /activity；刷新/换页也能看到；顺带锁按钮防重复提交）。 */
async function pollServerDraft() {
  try {
    const p = await deriveApi.get(`/api/derive/runs/${route.params.id}/activity`)
    serverDraft.value = p?.active ? p : null
  } catch {
    // 轮询失败静默
  }
}

async function pollTrail() {
  try {
    llmTrail.value = await deriveApi.get(`/api/derive/runs/${route.params.id}/llm-trail`)
  } catch {
    // 轮询失败静默
  }
}

const serverDraftText = computed(() => {
  const p = serverDraft.value
  if (!p) return ''
  const s = Math.round((p.elapsedMs || 0) / 1000)
  const idle = Math.round((p.idleMs || 0) / 1000)
  const stall = idle > 90 ? ` · 已 ${idle}s 无输出（可能卡流）` : ''
  const nonStream = !p.frames && !p.chars ? ' · 该步非流式，完成前无中间输出' : ''
  return `进行中：${p.phase} · 已耗时 ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
    + ` · 输出约 ${(p.chars / 10000).toFixed(1)} 万字${stall}${nonStream}`
})

function pct(done, total) {
  if (!total) return 0
  return Math.min(100, Math.round((done / total) * 100))
}

function pretty(node) {
  if (node == null) return '—'
  return JSON.stringify(node, null, 2)
}

async function loadAll() {
  loading.value = true
  const id = route.params.id
  try {
    const [d, c, b, q] = await Promise.all([
      deriveApi.get(`/api/derive/runs/${id}`),
      deriveApi.get(`/api/derive/runs/${id}/chapters${withText.value ? '?includeText=true' : ''}`),
      deriveApi.get(`/api/derive/runs/${id}/beats`),
      deriveApi.get(`/api/derive/runs/${id}/qc-reports`)
    ])
    detail.value = d
    chapters.value = c
    beats.value = b
    reports.value = q
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

/* ── 状态机操作条（纯前端走完全流程的驾驶位） ────────────────────── */
const acting = ref('')
const draftLoopText = ref('')
const serverWrite = ref(null)
const writeStarting = ref(false)
let writeDoneHandled = false
let draftPollTimer = null
let writePollTimer = null

/** ⑦ 服务端连跑（2026-10-18 根修）：点一次服务器写到完，刷新/换页/关电脑都不影响。 */
const writeBusy = computed(() => writeStarting.value || !!serverWrite.value?.running)

const serverWriteText = computed(() => {
  const w = serverWrite.value
  if (!w) return ''
  if (w.lastError) return `服务端连跑已停：${w.lastError}（修完可再点「连续写完」续跑）`
  if (w.allDone) return '服务端连跑完成：全部正文已写完，run 已转质检 ⑧'
  if (!w.running) return ''
  const total = Math.round((w.elapsedMs || 0) / 1000)
  const ch = w.lastChapterNo
    ? `已写 ${w.chaptersDone} 章（第 ${w.lastChapterNo} 章 ${Math.round((w.lastChapterMs || 0) / 1000)}s）`
    : `起步中（已写 ${w.chaptersDone} 章）`
  const phase = w.phase ? ` · ${w.phase}` : ''
  return `服务端连跑中：${ch}${phase} · 总耗时 ${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')} —— 关页面不断`
})

async function pollWriteProgress() {
  try {
    const w = await deriveApi.get(`/api/derive/runs/${route.params.id}/write-progress`)
    const prev = serverWrite.value
    serverWrite.value = w
    if (w?.allDone && !writeDoneHandled) {
      writeDoneHandled = true
      ElMessage.success('服务端连跑完成：正文全部写完，下一步运行质检 ⑧')
      await loadAll()
    }
    if (w?.lastError && (!prev || prev.lastError !== w.lastError)) {
      ElMessage.error(`连跑停止：${w.lastError}`)
      await loadAll()
    }
  } catch {
    // 轮询失败静默
  }
}

function startWritePoll() {
  stopWritePoll()
  writePollTimer = setInterval(pollWriteProgress, 2500)
}

function stopWritePoll() {
  if (writePollTimer) {
    clearInterval(writePollTimer)
    writePollTimer = null
  }
}

function startDraftPoll() {
  stopDraftPoll()
  draftPollTimer = setInterval(async () => {
    try {
      const p = await deriveApi.get(`/api/derive/runs/${route.params.id}/draft-progress`)
      if (!p?.active) return
      const s = Math.round((p.elapsedMs || 0) / 1000)
      const idle = Math.round((p.idleMs || 0) / 1000)
      const stall = idle > 90 ? ` · 已 ${idle}s 无输出（可能卡流）` : ''
      draftLoopText.value =
        `服务端进行中：${p.phase} · 已耗时 ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}` +
        ` · 输出约 ${(p.chars / 10000).toFixed(1)} 万字${stall}`
    } catch {
      // 进度接口失败不打断主循环
    }
  }, 2000)
}

function stopDraftPoll() {
  if (draftPollTimer) {
    clearInterval(draftPollTimer)
    draftPollTimer = null
  }
}

const guide = computed(() => {
  const d = detail.value
  if (!d) return ''
  switch (d.status) {
    case 'SAMPLING': return '采样进行中——稍后刷新'
    case 'PLANNING':
      return d.novelId ? '已出章节计划，下一步：生成具体稿（⑥b）' : '下一步：出章节计划（⑥a，建成品书+章行+拍实例）'
    case 'DRAFTING':
      return gatePassed.value
        ? '立项闸已过——点「连续写完（服务端）」由服务器自动逐章写到完，关页面不断'
        : '具体稿就绪，先过 P6 立项闸（六判据评审，GO 后才放行 ⑦ 写正文）'
    case 'QC': return '全部正文已写完，下一步：运行质检（⑧，脚本+AI 快检，可能 10 分钟以上）'
    case 'REWORK':
      return gateInfo.value?.status === 'REWORK'
        ? '立项闸打回：按下方减法指令回炉 ⑥b 整本重做具体稿，完成后重新过闸'
        : '质检打回：先回炉重写具体稿（⑥b），再逐章重写正文（⑦），最后重新质检（⑧）'
    case 'PASSED': return '全链完成，质检通过 ✅'
    case 'FAILED': return '已熔断（qc 三轮未过 / 立项闸 REJECT）——后端暂无重开端点，需人工处理'
    default: return ''
  }
})

/* ── P6 立项闸（run.step_status.steps.GATE，L2 层） ────────────────── */
const gateInfo = computed(() => {
  const raw = detail.value?.stepStatus
  if (!raw) return null
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw
    const g = obj?.steps?.GATE
    return g && g.status ? g : null
  } catch {
    return null
  }
})
const gatePassed = computed(() => gateInfo.value?.status === 'PASS')

function gateTagType(status) {
  return { PASS: 'success', REWORK: 'warning', FAILED: 'danger', PENDING: 'info' }[status] || 'info'
}
function gateStatusLabel(status) {
  return { PASS: '放行 GO', REWORK: '打回 REWORK', FAILED: '熔断 REJECT', PENDING: '待评审' }[status] || status
}

async function actGate() {
  try {
    await ElMessageBox.confirm(
      '立项闸 = LLM 对全书章纲/拍清单做六判据评审（规则单轨/体量/动机链/闭合/职能/母题），一次数分钟。GO 放行 ⑦；REWORK 打回 ⑥b 整本重做；REJECT 熔断。继续？',
      'P6 立项闸', { type: 'warning', confirmButtonText: '评审' }
    )
  } catch {
    return
  }
  acting.value = 'gate'
  try {
    const g = await deriveApi.post(`/api/derive/runs/${route.params.id}/gate`)
    await loadAll()
    if (g.verdict === 'GO') ElMessage.success(`立项闸 GO（第 ${g.round} 轮），⑦ 已放行`)
    else if (g.verdict === 'REWORK') ElMessage.warning(`立项闸 REWORK（${g.issues?.length ?? 0} 个问题）——按减法指令回炉 ⑥b`)
    else ElMessage.error('立项闸 REJECT——run 已熔断 FAILED')
  } catch (e) {
    ElMessage.error(e.message)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

async function actPlan() {
  acting.value = 'plan'
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/plan`)
    ElMessage.success('章节计划完成：成品书 + 章行 + 拍实例已建')
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

async function actDraft(isRework) {
  const conf = isRework
    ? '回炉将清空现有正文（先逐章归档 draft_history）并整本重做具体稿（清稿后从全局头起逐窗重跑），完成后需重新过闸再写正文。继续？'
    : '生成具体稿：全局头 + 逐窗章纲/拍填实（每窗约 3~10 分钟，前端自动循环到全部完成）。继续？'
  try {
    await ElMessageBox.confirm(conf, '确认执行', { type: 'warning', confirmButtonText: '执行' })
  } catch {
    return
  }
  acting.value = 'draft'
  draftLoopText.value = '开始…'
  startDraftPoll()
  let guard = 0
  try {
    // ⑥b 幂等单元制：每次 POST 只推进一个单元（RESET/HEAD/BATCH），循环至 allDone
    while (guard < 40) {
      guard += 1
      const d = await deriveApi.post(`/api/derive/runs/${route.params.id}/draft`)
      if (d.allDone) {
        draftLoopText.value = ''
        ElMessage.success(`具体稿全部完成（《${d.title || ''}》共 ${d.chapterCount} 章），已进入写作阶段`)
        await loadAll()
        return
      }
      if (d.unit === 'RESET') {
        draftLoopText.value = '已清稿（回炉），从全局头重做…'
      } else if (d.unit === 'HEAD') {
        draftLoopText.value = `全局头完成：《${d.title}》主角=${d.protagonist} —— 下一步逐窗生成章纲…`
      } else {
        draftLoopText.value = `窗 ${d.batchIndex}/${d.batchTotal} 完成（填实 ${d.filledCount} · 跳过 ${d.skippedCount}）—— 继续下一窗…`
      }
      await loadAll()
    }
    draftLoopText.value = ''
    ElMessage.warning('已达单次 40 单元保护上限——再点一次会从断点续跑（幂等）')
  } catch (e) {
    draftLoopText.value = ''
    ElMessage.error(`⑥b 中断：${e.message}。进度已按单元落库，再点一次从断点续跑，不会重来。`)
    await loadAll()
  } finally {
    stopDraftPoll()
    acting.value = ''
  }
}

function writeFeedback(w) {
  if (!w.gateCleared && w.lastIssue) {
    ElMessage.warning(`第 ${w.chapterNo} 章落库但输出校验未全过（${w.lastIssue}）——交由质检判定`)
  }
}

async function actWriteOne() {
  acting.value = 'write'
  try {
    const w = await deriveApi.post(`/api/derive/runs/${route.params.id}/write`)
    writeFeedback(w)
    if (w.chapterNo < 0 || w.allDone) {
      ElMessage.success('全部章节已写完，run 已转质检')
    } else {
      ElMessage.success(`第 ${w.chapterNo} 章 ${w.charCount} 字（预算 ${w.budget}），剩 ${w.remaining} 章`)
    }
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

async function actWriteAll() {
  try {
    await ElMessageBox.confirm(
      '服务端连续写完：点一次后由服务器自动逐章写到全部完成——关页面、关电脑都不影响。每章约 1~10 分钟。继续？',
      '连续写完（服务端）', { type: 'warning', confirmButtonText: '启动' }
    )
  } catch {
    return
  }
  writeStarting.value = true
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/write-loop`)
    writeDoneHandled = false
    ElMessage.success('服务端连跑已启动——进度看操作条横幅与「运行过程」，页面可关')
    await pollWriteProgress()
    startWritePoll()
  } catch (e) {
    ElMessage.error(e.message)
    await pollWriteProgress()
  } finally {
    writeStarting.value = false
  }
}

async function stopWriteLoop() {
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/write-loop/stop`)
    ElMessage.info('已请求停止：当前章写完后不再继续')
    await pollWriteProgress()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function actQc() {
  try {
    await ElMessageBox.confirm(
      '质检 = 脚本 7 项硬判 + AI 快检 5 项；中长篇可能 10 分钟以上。不通过将进入回炉或熔断。继续？',
      '运行质检', { type: 'warning', confirmButtonText: '运行' }
    )
  } catch {
    return
  }
  acting.value = 'qc'
  try {
    await deriveApi.post(`/api/derive/runs/${route.params.id}/qc`)
    await loadAll()
    const v = detail.value?.qc?.latestVerdict
    if (v === 'PASS') ElMessage.success('质检通过（PASS）🎉')
    else if (v === 'REWORK') ElMessage.warning('质检打回（REWORK）——按提示回炉 ⑥b')
    else if (v === 'FAIL' || v === 'FAILED') ElMessage.error('质检熔断（FAIL）——已达轮次上限')
    else ElMessage.success('质检完成')
  } catch (e) {
    ElMessage.error(e.message)
    await loadAll()
  } finally {
    acting.value = ''
  }
}

let serverDraftTimer = null
let trailTimer = null
onMounted(() => {
  loadAll()
  pollServerDraft()
  pollTrail()
  pollWriteProgress()
  startWritePoll()
  serverDraftTimer = setInterval(pollServerDraft, 3000)
  trailTimer = setInterval(pollTrail, 10000)
})
onUnmounted(() => {
  if (serverDraftTimer) clearInterval(serverDraftTimer)
  if (trailTimer) clearInterval(trailTimer)
  stopWritePoll()
  stopDraftPoll()
})
</script>

<style scoped>
.stat-row {
  margin-bottom: var(--space-3);
}
.stat-label {
  font-size: var(--text-xs);
  color: var(--muted);
  margin-bottom: 4px;
}
.stat-value {
  font-size: var(--text-lg);
  font-weight: 600;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.stat-inline {
  font-size: var(--text-xs);
  font-weight: 400;
  color: var(--muted);
}
.stat-sub {
  margin-top: 4px;
  font-size: var(--text-xs);
  color: var(--muted);
}
.block {
  margin-bottom: var(--space-3);
}
.action-bar__row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.action-bar__spacer {
  flex: 1;
}
.guide {
  font-size: var(--text-xs);
  color: var(--muted);
}
.loop-text {
  margin: 8px 0 0;
  font-size: var(--text-xs);
  color: var(--fg);
}
.loop-text--live {
  color: var(--el-color-primary);
}
.process-live {
  font-size: var(--text-xs);
  color: var(--el-color-primary);
  line-height: var(--leading-body);
}
.process-idle {
  font-size: var(--text-xs);
  color: var(--muted);
}
.pre {
  margin: 0 0 8px;
  white-space: pre-wrap;
  font-size: var(--text-xs);
  line-height: var(--leading-body);
}
.gate-issues {
  margin: 0 0 8px;
  padding-left: 18px;
  font-size: var(--text-xs);
  line-height: var(--leading-body);
}
.gate-issues li {
  margin-bottom: 4px;
}
.json {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
