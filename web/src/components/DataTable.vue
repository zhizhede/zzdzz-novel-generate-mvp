<template>
  <el-table
    :data="data"
    :border="border"
    :size="size"
    :row-key="rowKey"
    :row-class-name="rowClassName"
    v-loading="loading">
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
 * 表格统一封装：宽度口径、加载态、空态、行高亮。
 *
 * 抽它的原因见 docs/design/前端设计系统与交互优化.md 附录 C 与 §三：
 *   1. 宽度——全站 37 张表此前各写一个 max-width（680/860/1500/1600…），
 *      页面右边缘逐页不同。本组件不设任何宽度上限，表格吃满内容区。
 *   2. 空态——此前部分表写了 #empty、部分没写，没写的会渲染 Element Plus
 *      默认文案；这里统一给出，且允许调用方覆盖。
 *   3. 行高亮——原先四页各写一份 scoped `:deep(.xxx-highlight td){background:#ecf5ff}`，
 *      改为统一的 .row-notice（定义在 styles/app.css，用设计令牌着色）。
 *
 * 其余属性（@row-click / max-height / style 等）经属性透传直达 el-table。
 */
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
</script>
