<template>
  <div>
    <h3>章节</h3>
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
            LLM：{{ detail.llmTotals.calls }} 次调用 / {{ detail.llmTotals.totalTokens }} tokens / 平均 {{ detail.llmTotals.avgLatencyMs }}ms
          </span>
          <el-button v-if="detail.status === 'PENDING_APPROVAL'" type="success" size="small" @click="approve">通过审批</el-button>
        </div>

        <el-tabs>
          <el-tab-pane label="正文">
            <div style="white-space: pre-wrap; line-height: 1.9">{{ detail.fullText || '（尚未生成）' }}</div>
          </el-tab-pane>
          <el-tab-pane :label="`场景（${detail.scenes.length}）`">
            <div v-for="s in detail.scenes" :key="s.id" style="margin-bottom: 14px">
              <div style="font-size: 12px; color: #999; margin-bottom: 4px">
                场景 {{ s.sceneNo }}｜{{ s.gateStatus }}｜改写 {{ s.revisionRound }} 次｜目标：{{ s.goal }}
              </div>
              <div style="white-space: pre-wrap; border-left: 3px solid #eee; padding-left: 10px">{{ s.draftText }}</div>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`门禁（${detail.gateReport ? (detail.gateReport.passed ? '通过' : '未过') : '无'}）`">
            <el-table v-if="detail.gateReport" :data="detail.gateReport.checks" border size="small">
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
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const STATUS_COLOR = { DIGESTED: 'success', APPROVED: 'success', FAILED: 'danger', PENDING_APPROVAL: 'warning', NEW: 'info', OUTLINED: '', GATE_MECHANICAL: '' }

const chapters = ref([])
const detail = ref(null)
const drawer = ref(false)

async function open(row) {
  detail.value = await api.get(`/api/chapters/${row.id}`)
  drawer.value = true
}

async function approve() {
  try {
    await api.post(`/api/chapters/${detail.value.id}/approve`)
    ElMessage.success('已过审并生成事实账')
    detail.value = await api.get(`/api/chapters/${detail.value.id}`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  const novels = await api.get('/api/novels')
  chapters.value = await api.get(`/api/novels/${novels[0].id}/chapters`)
})
</script>
