<template>
  <el-table
    ref="tableRef"
    :data="data"
    :border="border"
    :size="size"
    :row-key="rowKey"
    :row-class-name="rowClassName"
    :class="{ 'ng-table-clickable': rowActivatable }"
    v-loading="loading"
    @keydown="onKeydown">
    <slot />
    <template #empty>
      <slot name="empty">
        <el-empty :description="emptyText" :image-size="60" />
      </slot>
    </template>
  </el-table>
</template>

<script setup>
/**
 * 表格统一封装：宽度口径、加载态、空态、行高亮、行级键盘可达。
 *
 * 抽它的原因见 docs/design/前端设计系统与交互优化.md 附录 C 与 §三：
 *   1. 宽度——全站 37 张表此前各写一个 max-width（680/860/1500/1600…），
 *      页面右边缘逐页不同。本组件不设任何宽度上限，表格吃满内容区。
 *   2. 空态——此前部分表写了 #empty、部分没写，没写的会渲染 Element Plus
 *      默认文案；这里统一给出，且允许调用方覆盖。
 *   3. 行高亮——原先四页各写一份 scoped `:deep(.xxx-highlight td){background:#ecf5ff}`，
 *      改为统一的 .row-notice（定义在 styles/app.css，用设计令牌着色）。
 *   4. 键盘可达——见下方 rowActivatable。
 *
 * 其余属性（@row-click / max-height / style 等）经属性透传直达 el-table。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useAttrs, watch } from 'vue'

const props = defineProps({
  data: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无数据' },
  /** 额外的行类名：函数 ({row}) => string，或直接给字符串 */
  rowClass: { type: [Function, String], default: '' },
  /** 高亮行：row[noticeField] === noticeValue 时加 .row-notice（如「刚导入的那条」） */
  noticeField: { type: String, default: '' },
  noticeValue: { type: [String, Number], default: null },
  rowKey: { type: [String, Function], default: undefined },
  size: { type: String, default: 'small' },
  border: { type: Boolean, default: true }
})

function rowClassName({ row }) {
  const base = typeof props.rowClass === 'function' ? props.rowClass({ row }) : props.rowClass
  const hit = props.noticeField && props.noticeValue != null && row[props.noticeField] === props.noticeValue
  return [base, hit ? 'row-notice' : ''].filter(Boolean).join(' ')
}

/* ── 行级键盘可达 ────────────────────────────────────────────────────
 * Element Plus 给每个 <tr> 绑了 onClick，但**不给 tabindex**，也不把 row 对象
 * 暴露到 DOM 上（见 table-body/render-helper.mjs）。所以键盘激活只能这样接：
 *   1. 给行补 tabIndex，让它进 Tab 序列；
 *   2. 回车时直接调该行自己的 click()——复用 Element Plus 已有的点击路径，
 *      行为与鼠标点击逐字一致，也就不需要从 DOM 反查行数据。
 * tabIndex 必须由脚本补而不能写在模板里，因为 el-table 的行是它内部渲染的。 */
const attrs = useAttrs()
const tableRef = ref(null)

/**
 * 只有调用方传了 @row-click 才让行可聚焦。全站 37 张表，无差别开启会把每一行
 * 都塞进 Tab 序列——包括那些按了没反应的，键盘用户得空按一路。
 *
 * ⚠️ 判据是 $attrs 而不是 props/emits：一旦给本组件加上 `emits: ['row-click']`，
 *    Vue 就会把它从 $attrs 里摘掉，这里恒为 false，键盘可达会**静默失效**。
 */
const rowActivatable = computed(() => typeof attrs.onRowClick === 'function')

const ROW_SEL = 'tbody tr.el-table__row'
let observer = null

function markRows() {
  const root = tableRef.value?.$el
  if (!root) return
  for (const tr of root.querySelectorAll(ROW_SEL)) {
    if (tr.tabIndex !== 0) tr.tabIndex = 0
  }
}

/** 行会被 el-table 在数据/列宽变化时重建，属性也就跟着丢，故用观察器兜住。 */
function observeRows() {
  observer?.disconnect()
  observer = null
  if (!rowActivatable.value) return
  const tbody = tableRef.value?.$el?.querySelector('tbody')
  if (!tbody) return
  observer = new MutationObserver(markRows)
  observer.observe(tbody, { childList: true })
  markRows()
}

function onKeydown(e) {
  // 只认落在行本身上的回车：格子里的按钮/输入框有自己的键盘行为，别抢
  if (e.key !== 'Enter' || e.target?.tagName !== 'TR') return
  if (!e.target.classList.contains('el-table__row')) return
  e.preventDefault()
  e.target.click()
}

onMounted(async () => {
  await nextTick()
  observeRows()
})
watch(() => props.data, observeRows, { flush: 'post' })
onBeforeUnmount(() => observer?.disconnect())
</script>
