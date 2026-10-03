<template>
  <el-dialog v-model="visible" :title="`解析《${title || ''}》`" width="var(--dlg-w-lg)" :close-on-click-modal="false"
             @close="stopPoll">
    <div v-if="status" style="margin-bottom: 8px; font-size: var(--text-sm)">
      <el-tag size="small" :type="STATUS_TYPE[status.status] || 'info'">{{ STATUS_TEXT[status.status] || status.status }}</el-tag>
      <span style="margin-left: 8px; color: var(--fg-2)">{{ status.message || runningHint }}</span>
    </div>

    <!-- 进度/结果：本次提交的步骤逐行渲染（后端每步落库，刷新页面也不丢） -->
    <div v-if="status && status.plannedSteps && status.plannedSteps.length" style="margin-bottom: 10px">
      <div v-for="key in status.plannedSteps" :key="key"
           style="display: flex; align-items: baseline; gap: 8px; padding: 3px 0; font-size: var(--text-sm); line-height: 1.7">
        <span style="width: 16px">{{ icon(key) }}</span>
        <span style="min-width: 190px">{{ label(key) }}</span>
        <el-tag v-if="isToggleable(key)" size="small" effect="plain"
                :type="skippedInRun(key) ? 'info' : 'warning'">
          {{ skippedInRun(key) ? '跳过已有' : '覆盖重做' }}
        </el-tag>
        <span style="flex: 1; min-width: 0"
              :style="{ color: resultOf(key) ? colorOf(resultOf(key).status) : 'var(--muted)' }">
          {{ resultOf(key) ? (resultOf(key).message || resultOf(key).status) : (isRunning(key) ? '进行中…' : '待执行') }}
        </span>
        <span v-if="resultOf(key)" style="color: var(--meta)">{{ Math.round(resultOf(key).elapsedMs / 1000) }}s</span>
      </div>
    </div>

    <!-- 空闲态：可勾选重跑（用于给已有书补资产；导入时已由导入弹窗提交过） -->
    <section v-if="!busy" class="ia">
      <div class="ia-head">
        <b>再跑一次解析</b>
        <span class="hint">默认「覆盖重做」＝已有内容重算并原地更新；只想补缺的，把该步切到「跳过已有」</span>
      </div>

      <el-checkbox-group v-model="picked" class="ia-list">
        <div v-for="s in ANALYZE_STEPS" :key="s.key" class="ia-step">
          <div class="ia-step-row">
            <el-checkbox :label="s.key">
              <span class="ia-step-name">{{ s.label }}</span>
            </el-checkbox>
            <el-radio-group v-if="isToggleable(s.key)" :model-value="skipMode(s.key)" size="small"
                            @update:model-value="(v) => setSkipMode(s.key, v)">
              <el-radio-button value="overwrite">覆盖重做</el-radio-button>
              <el-radio-button value="skip">跳过已有</el-radio-button>
            </el-radio-group>
            <el-tag v-else size="small" type="info" effect="plain">{{ existingPolicy(s.key) }}</el-tag>
          </div>
          <p class="ia-step-hint">{{ s.hint }}</p>
        </div>
      </el-checkbox-group>

      <div class="ia-actions">
        <el-button size="small" @click="picked = allStepKeys()">全选</el-button>
        <el-button size="small" @click="picked = []">全不选</el-button>
        <span class="hint">已选 {{ picked.length }} / {{ ANALYZE_STEPS.length }} 步</span>
        <span class="ia-gap"></span>
        <el-button type="primary" size="small" :disabled="!picked.length" @click="start">开始解析</el-button>
      </div>
    </section>
    <div class="hint" v-else>
      解析在后台跑（关掉这个窗口也会继续），随时回到这里或刷新页面都能看到进度；完成情况也会写进本书的解析任务行。
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { ANALYZE_STEPS, allStepKeys, existingPolicy, isToggleable, stepLabel } from '../importAnalyze'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  novelId: { type: [Number, String], default: null },
  title: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'finished'])

const STATUS_TEXT = { QUEUED: '排队中', RUNNING: '解析中', DONE: '已完成', FAILED: '有步骤失败', INTERRUPTED: '已中断' }
const STATUS_TYPE = { QUEUED: 'info', RUNNING: 'primary', DONE: 'success', FAILED: 'danger', INTERRUPTED: 'warning' }

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const status = ref(null)
const picked = ref(allStepKeys())
/** 选择「跳过已有」的步（默认空 = 全部覆盖重做）。 */
const skipExisting = ref([])
let timer = null

const busy = computed(() => status.value && (status.value.status === 'RUNNING' || status.value.status === 'QUEUED'))
const runningHint = computed(() => (busy.value ? '解析在后台进行，本窗口会自动刷新…' : ''))

function skipMode (key) {
  return skipExisting.value.includes(key) ? 'skip' : 'overwrite'
}
function setSkipMode (key, mode) {
  const rest = skipExisting.value.filter((k) => k !== key)
  skipExisting.value = mode === 'skip' ? [...rest, key] : rest
}
/** 本次运行（后端任务行）里该步是不是「跳过已有」——进度行据实标注，而不是显示当前开关状态。 */
function skippedInRun (key) {
  return !!status.value && (status.value.skipExistingSteps || []).includes(key)
}

function resultOf (key) {
  return (status.value && status.value.results || []).find((r) => r.step === key)
}
function isRunning (key) {
  return status.value && status.value.currentStep === key
}
function label (key) {
  const r = resultOf(key)
  return (r && r.label) || stepLabel(key)
}
function icon (key) {
  const r = resultOf(key)
  if (!r) return isRunning(key) ? '⏳' : '·'
  if (r.status === 'SUCCESS') return '✅'
  if (r.status === 'SKIPPED') return '⏭'
  return '❌'
}
function colorOf (s) {
  return s === 'SUCCESS' ? 'var(--success)' : s === 'SKIPPED' ? 'var(--muted)' : 'var(--danger)'
}

async function loadStatus (notifyFinished = false) {
  if (!props.novelId) return
  try {
    const s = await api.get(`/api/novels/${props.novelId}/import-analyze`)
    const wasBusy = !!busy.value
    status.value = s
    if (!busy.value) {
      stopPoll()
      if (notifyFinished || wasBusy) {
        if (s && s.status === 'DONE') ElMessage.success(s.message || '解析完成')
        else if (s && s.status === 'FAILED') ElMessage.warning(s.message || '解析有步骤失败（详见逐项说明）')
        emit('finished', s)
      }
    } else {
      startPoll()
    }
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function startPoll () {
  if (timer) return
  timer = setInterval(() => loadStatus(true), 4000)
}
function stopPoll () {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function start () {
  try {
    // 只提交勾选中的步；「跳过已有」也只对勾选中的步生效（后端同样会过滤一次）
    const skipped = skipExisting.value.filter((k) => picked.value.includes(k))
    await api.post(`/api/novels/${props.novelId}/import-analyze`, {
      steps: picked.value,
      skipExistingSteps: skipped
    })
    ElMessage.success('解析已入队——逐步进度见下')
    status.value = { status: 'QUEUED', plannedSteps: picked.value, skipExistingSteps: skipped, results: [] }
    startPoll()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

watch(() => props.modelValue, (open) => {
  if (open) {
    stopPoll()
    loadStatus(false)
  } else {
    stopPoll()
  }
})

onBeforeUnmount(stopPoll)
</script>

<style scoped>
/* 步骤列表：每一步 = 「名称 + 策略控件」一行，说明另起一行并对齐到名称。
 * 改造前全是行内流：hint 首行从两个胶囊控件后面开始、续行却回到最左侧，
 * 读不出这行说明属于哪一步——用户把它当成了「文字重叠」。
 * （实测截图逐行做暗像素剖面，行间都有干净空隙，确无重叠；真问题是这个
 *  断裂的阅读顺序。） */
.ia-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-bottom: var(--space-3);
}

.ia-list { display: block; }

.ia-step {
  padding: var(--space-2) 0;
  border-top: 1px solid var(--border-soft);
}
.ia-step:first-child { border-top: 0; padding-top: 0; }

.ia-step-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
/* Element Plus 给 .el-checkbox 默认 margin-right:30px，在 flex 行里会与 gap 叠加 */
.ia-step-row :deep(.el-checkbox) { margin-right: 0; }

.ia-step-name { font-size: var(--text-sm); }

.ia-step-hint {
  /* 22px = 复选框宽 14px + 它与文字的间距，用于对齐到「步骤名」而不是复选框 */
  margin: 2px 0 0 22px;
  font-size: var(--text-xs);
  color: var(--muted);
  line-height: 1.7;
}

.ia-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
}
.ia-gap { flex: 1; }
</style>
