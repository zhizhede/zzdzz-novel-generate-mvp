<template>
  <!-- 按本书正文提指纹：草稿 → 用户确认 → 采纳（覆盖本书风格包指纹）。导入书籍与书籍管理行内共用。 -->
  <el-dialog :model-value="modelValue" title="按本书正文提指纹" width="880px"
             @update:model-value="(open) => emit('update:modelValue', open)"
             @closed="emit('closed')">
    <div v-loading="loading">
      <template v-if="draft">
        <div style="font-size: var(--text-sm); color: var(--fg-2); line-height: 1.9; margin-bottom: 8px">
          <div>书：<b>{{ draft.title }}</b></div>
          <div>统计样本：{{ draft.chapterCount }} 章 · {{ (draft.totalChars / 10000).toFixed(1) }} 万字 ·
            {{ draft.metricCount }} 项指标</div>
          <div>章长带：{{ draft.budgetMin }}–{{ draft.budgetMax }} 字（容差 ±{{ draft.chapterLengthTolerance }}）</div>
        </div>

        <el-alert v-if="draft.lowConfidence" type="warning" :closable="false" style="margin-bottom: 8px"
                  title="样本偏少·低置信" :description="draft.notes.join('；')" />
        <el-alert v-else type="success" :closable="false" style="margin-bottom: 8px"
                  title="指纹已按本书正文算出——确认无误再采纳" :description="draft.notes.join('；')" />

        <el-alert type="info" :closable="false" style="margin-bottom: 8px"
                  title="采纳的后果：本书后续生成的门禁宽严立刻改按这份指纹判定（场景级与章级同一套数学）；要退回原预设口径，去素材库「应用到本书」覆盖即可。" />

        <FingerprintMetricTable :metrics="draft.metrics" :max-height="320" style="margin-bottom: 10px" />

        <el-checkbox v-model="syncBand" style="margin-bottom: 6px">
          同时把章长带写进本书门禁配置（{{ draft.budgetMin }}–{{ draft.budgetMax }} 字，续写按原书章节长度）
        </el-checkbox>

        <el-collapse>
          <el-collapse-item title="原始指纹 JSON（采纳时原样提交）">
            <pre style="max-height: 200px; overflow: auto; background: var(--surface-warm); padding: 10px; border-radius: 6px; font-size: var(--text-xs); line-height: 1.6">{{ prettyJson }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
      <el-empty v-else-if="!loading" :description="error || '拿不到指纹草稿'" />
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!draft" :loading="applying" @click="apply">
        采纳并覆盖本书指纹
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import FingerprintMetricTable from './FingerprintMetricTable.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  novelId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue', 'applied', 'closed'])

const draft = ref(null)
const loading = ref(false)
const applying = ref(false)
const error = ref('')
const syncBand = ref(true)

const prettyJson = computed(() => {
  if (!draft.value) return ''
  try {
    return JSON.stringify(JSON.parse(draft.value.fingerprintJson), null, 2)
  } catch {
    return draft.value.fingerprintJson
  }
})

watch(() => [props.modelValue, props.novelId], async ([open]) => {
  if (!open || !props.novelId) return
  draft.value = null
  error.value = ''
  loading.value = true
  try {
    draft.value = await api.post(`/api/novels/${props.novelId}/style/extract-fingerprint`, {})
    syncBand.value = true
  } catch (e) {
    error.value = e.message
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
})

async function apply() {
  applying.value = true
  try {
    await api.post(`/api/novels/${props.novelId}/style/apply-fingerprint`, {
      fingerprintJson: draft.value.fingerprintJson,
      budgetMin: draft.value.budgetMin,
      budgetMax: draft.value.budgetMax,
      chapterLengthTolerance: draft.value.chapterLengthTolerance,
      syncBudgetBand: syncBand.value
    })
    ElMessage.success(`已采纳：本书指纹改为按正文统计的 ${draft.value.metricCount} 项指标`)
    emit('applied', draft.value)
    emit('update:modelValue', false)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    applying.value = false
  }
}
</script>
