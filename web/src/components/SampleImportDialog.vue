<template>
  <!-- 导入新小说（共用弹窗）：素材库「导入小说」页与文风指纹页共用，落库口径只有这一条路 -->
  <el-dialog :model-value="modelValue" title="导入新小说" width="640px"
             @update:model-value="(open) => emit('update:modelValue', open)">
    <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px">
      <el-input v-model="form.name" placeholder="小说名（用于命名品类，可选）" size="small" style="width: 220px" />
      <span v-if="form.text" style="font-size: 12px; color: #999">
        已载入 {{ (form.text.length / 10000).toFixed(1) }} 万字
      </span>
      <span v-else-if="form.fileBase64" style="font-size: 12px; color: #999">已载入文档/电子书</span>
    </div>
    <TextFileDropZone style="margin-bottom: 8px"
                      sub-hint="支持 txt / docx / 无 DRM 的 mobi、azw；整本或长片段都行，也可直接粘贴到下方文本框"
                      @loaded="onFileLoaded" />
    <el-input v-model="form.text" type="textarea" :rows="8"
              placeholder="或直接粘贴小说正文（整本或长片段，最多 800 万字）。系统自动切块存入语料库并出文风分析，之后可在列表里深度解析。" />
    <div style="font-size: 12px; color: #999; margin-top: 6px">
      分析为纯机械指标（秒级、零 LLM 成本）；深度解析（LLM）在列表行单独触发。样本偏少（<10 块）会给低置信提示，仍会入库。
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="importing" :disabled="!form.text && !form.fileBase64" @click="submit">
        分析并入库
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import TextFileDropZone from './TextFileDropZone.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'imported'])

const form = ref({ name: '', text: '', fileBase64: '' })
const importing = ref(false)

// 每次打开重置，避免上一轮的正文残留被误提交
watch(() => props.modelValue, (open) => {
  if (open) form.value = { name: '', text: '', fileBase64: '' }
})

/** 拖拽/选择载入：txt 给文本，docx/电子书给 base64 由后端按文件头提取正文；书名空着时用文件名兜底。 */
function onFileLoaded({ name, text, fileBase64 }) {
  form.value.text = text
  form.value.fileBase64 = fileBase64
  if (!form.value.name) form.value.name = name
}

/** 切块落语料 + 机械分析 + 台账落库（POST /api/preset/analyze）；成功后由使用方决定提示与刷新。 */
async function submit() {
  importing.value = true
  try {
    const result = await api.post('/api/preset/analyze', {
      sampleName: form.value.name,
      text: form.value.text,
      fileBase64: form.value.fileBase64 || undefined
    })
    emit('update:modelValue', false)
    emit('imported', result)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    importing.value = false
  }
}
</script>
