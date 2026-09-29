<template>
  <!-- 共用拖拽导入区：拖进来或点击选择，读成 { name, text, fileBase64 } 交给调用方 -->
  <div class="text-drop" :class="{ 'is-over': over }"
       @dragenter.prevent="dragIn" @dragover.prevent="dragIn"
       @dragleave.prevent="dragOut" @drop.prevent="onDrop">
    <div class="text-drop-title">
      把 txt / docx / mobi / azw 拖到这里，或
      <button type="button" class="text-drop-pick" @click="pick">点击选择文件</button>
    </div>
    <div class="text-drop-sub">{{ subHint }}</div>
    <input ref="inputRef" type="file" :accept="accept" style="display: none" @change="onPick" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

defineProps({
  /** 系统选择框的扩展名过滤（拖拽不受它约束——拖进来的文件一律按扩展名判定类型） */
  accept: { type: String, default: '.txt,.md,.docx,.mobi,.azw3,.azw' },
  /** 区域内的第二行说明（各导入场景告知各自的格式边界与替代入口） */
  subHint: { type: String, default: '支持 txt / docx / 无 DRM 的 mobi、azw；也可以直接粘贴正文' }
})
const emit = defineEmits(['loaded'])

const over = ref(false)
// 进入/离开用计数而不是布尔：鼠标划过区域内子元素也会触发 dragleave，布尔写法会让高亮闪
let dragDepth = 0
const inputRef = ref(null)

function dragIn() {
  dragDepth += 1
  over.value = true
}

function dragOut() {
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) over.value = false
}

function pick() {
  inputRef.value.value = ''
  inputRef.value.click()
}

function onPick(ev) {
  take(ev.target.files && ev.target.files[0])
}

/**
 * 拖拽入口：浏览器不按 accept 过滤（accept 只管系统选择框），所以必须自己判类型——
 * 否则不认识的二进制（如 .docx）会被当 utf-8 文本读进来，把正文框灌成乱码（实弹踩过）。
 * 未知类型直接报错，绝不静默塞进正文框。
 */
function onDrop(ev) {
  dragDepth = 0
  over.value = false
  const dt = ev.dataTransfer
  if (!dt) return
  const file = dt.files && dt.files[0]
  if (!file) {
    ElMessage.warning('这里要拖文件（txt / docx / mobi / azw），拖文字请直接粘贴到下方文本框')
    return
  }
  take(file)
}

/** 按扩展名分派：文本直读；docx 与电子书都走 base64（后端按文件头再分派）；其余给明确拒绝理由。 */
function take(file) {
  if (!file) return
  const lower = file.name.toLowerCase()
  if (lower.endsWith('.doc')) {
    ElMessage.error('旧版 .doc（二进制格式）不支持——请用 Word 打开后「另存为」.docx 或 .txt 再导入')
    return
  }
  const isEbook = /\.(mobi|azw3|azw)$/.test(lower)
  const isOoxml = /\.(docx|docm)$/.test(lower)
  const isPlain = /\.(txt|md|text)$/.test(lower)
  if (!isEbook && !isOoxml && !isPlain) {
    ElMessage.error('不支持的文件类型：' + lower.split('.').pop()
      + '——请用 txt / docx（PDF 请先转出 txt），电子书支持无 DRM 的 mobi / azw')
    return
  }
  const baseName = file.name.replace(/\.[^.]+$/, '')
  const reader = new FileReader()
  if (isPlain) {
    reader.onload = () => emit('loaded', { name: baseName, text: String(reader.result || ''), fileBase64: '' })
    reader.readAsText(file, 'utf-8')
  } else {
    reader.onload = () => emit('loaded', { name: baseName, text: '', fileBase64: String(reader.result || '') })
    reader.readAsDataURL(file)
  }
}
</script>

<style scoped>
.text-drop {
  border: 1px dashed #d9dce0;
  border-radius: 6px;
  background: #fafbfc;
  padding: 14px 16px;
  text-align: center;
  transition: border-color .2s, background .2s;
}
.text-drop.is-over {
  border-color: #409eff;
  background: #ecf5ff;
}
.text-drop-title {
  font-size: 13px;
  color: #606266;
}
.text-drop-pick {
  border: none;
  background: none;
  padding: 0;
  font-size: 13px;
  color: #409eff;
  cursor: pointer;
}
.text-drop-pick:hover {
  text-decoration: underline;
}
.text-drop-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
}
</style>
