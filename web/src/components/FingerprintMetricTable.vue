<template>
  <!-- 指纹指标表（口径唯一）：文风指纹页的指标明细与「按本书正文提指纹」草稿弹窗共用 -->
  <DataTable :data="metrics" border size="small" :max-height="maxHeight">
    <el-table-column prop="label" label="指标" min-width="170" />
    <el-table-column prop="key" label="键名" min-width="190" />
    <el-table-column label="基线值" width="90">
      <template #default="{ row }">{{ row.value == null ? '-' : row.value }}</template>
    </el-table-column>
    <el-table-column label="容差" width="80">
      <template #default="{ row }">{{ row.tolerance == null ? '-' : row.tolerance }}</template>
    </el-table-column>
    <el-table-column label="显式下限" width="90">
      <template #default="{ row }">{{ row.absMin == null ? '-' : row.absMin }}</template>
    </el-table-column>
    <el-table-column label="上限" width="90">
      <template #default="{ row }">{{ row.absMax == null ? '-' : row.absMax }}</template>
    </el-table-column>
    <template #empty>
      <span style="color: #bbb">没有指标</span>
    </template>
  </DataTable>
</template>

<script setup>
import DataTable from './DataTable.vue'
defineProps({
  /** 后端统一形状：[{ key, label, value, tolerance, absMin, absMax }] */
  metrics: { type: Array, default: () => [] },
  maxHeight: { type: Number, default: 360 }
})
</script>
