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
          <span style="font-size: 12px; color: #999">
            LLM：{{ detail.llmTotals?.calls ?? 0 }} 次调用 / {{ detail.llmTotals?.totalTokens ?? 0 }} tokens / 平均 {{ detail.llmTotals?.avgLatencyMs ?? 0 }}ms
          </span>
          <el-button v-if="detail.status === 'PENDING_APPROVAL'" type="success" size="small" :loading="approving" @click="approve">通过审批</el-button>
          <el-button v-if="detail.status === 'FAILED'" type="warning" size="small" @click="rerunChapter">重新生成本章</el-button>
          <el-button v-if="detail.fullText" size="small" plain @click="copyText">复制正文</el-button>
          <el-button v-if="detail.fullText" size="small" type="primary" plain @click="reader = true">阅读模式</el-button>
          <el-button v-if="detail.fullText" size="small" type="warning" plain :loading="reviewing" @click="runReview">AI 审校</el-button>
        </div>

        <el-tabs v-model="activeTab">
          <el-tab-pane label="正文" name="text">
            <div style="white-space: pre-wrap; line-height: 1.9">{{ detail.fullText || '（尚未生成）' }}</div>
          </el-tab-pane>
          <el-tab-pane :label="`场景（${detail.scenes?.length ?? 0}）`" name="scenes">
            <div v-for="s in (detail.scenes || [])" :key="s.id" style="margin-bottom: 14px">
              <div style="font-size: 12px; color: #999; margin-bottom: 4px">
                场景 {{ s.sceneNo }}｜{{ s.gateStatus }}｜改写 {{ s.revisionRound ?? 0 }} 次｜目标：{{ s.goal }}
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
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'

const STATUS_COLOR = { DIGESTED: 'success', APPROVED: 'success', FAILED: 'danger', PENDING_APPROVAL: 'warning', NEW: 'info', OUTLINED: '', GATE_MECHANICAL: '', GATE_AI_REVIEW: 'warning' }
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

const reviewLabel = computed(() => {
  const r = detail.value?.review
  return r ? (VERDICT_TEXT[r.verdict] || r.verdict) : '未审'
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
    ElMessage.success('已加入生成，进度见工作台')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

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
  activeTab.value = detail.value.fullText ? 'text' : 'scenes'
  drawer.value = true
}

async function approve() {
  approving.value = true
  try {
    // 审批会同步跑 digest（一次 LLM 调用，可能 1-2 分钟），按钮转圈防重复提交
    await api.post(`/api/chapters/${detail.value.id}/approve`)
    ElMessage.success('已过审并生成事实账')
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
