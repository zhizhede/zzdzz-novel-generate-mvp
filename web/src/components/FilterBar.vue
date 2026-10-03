<template>
  <div class="filter-bar">
    <el-card shadow="never">
      <el-form :inline="true" size="small" @submit.prevent>
        <slot />
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="$emit('search')">查询</el-button>
          <el-button @click="$emit('reset')">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <p v-if="hit || $slots.hit" class="filter-bar__hit">
      <slot name="hit">{{ hit }}</slot>
    </p>
  </div>
</template>

<script setup>
/**
 * 筛选条：卡片 + 内联表单 + 固定的「查询/重置」。
 *
 * 抽它的原因：BooksView / FingerprintView / PlanAssetView 三页各写一遍同样的
 * `<el-card shadow="never"><el-form :inline="true" size="small" @submit.prevent>` 外壳
 * 与末尾那对查询/重置按钮。
 *
 * 条件项写在默认插槽里（用 el-form-item）；命中读数写在 #hit 插槽，
 * 不传则由 hit 属性渲染一行纯文本。
 */
defineProps({
  /** 查询按钮的 loading 态（通常传页面的 loading） */
  loading: { type: Boolean, default: false },
  /** 命中读数纯文本；内容较复杂时用 #hit 插槽 */
  hit: { type: String, default: '' }
})
defineEmits(['search', 'reset'])
</script>

<style scoped>
.filter-bar {
  margin-bottom: var(--space-3);
}
.filter-bar__hit {
  margin: 0 0 var(--space-2);
  font-size: var(--text-xs);
  line-height: var(--leading-body);
  color: var(--muted);
}
</style>
