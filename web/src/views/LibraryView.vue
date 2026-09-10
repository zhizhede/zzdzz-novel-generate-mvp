<template>
  <div>
    <h3>素材库</h3>
    <el-tabs>
      <el-tab-pane label="正典文档">
        <el-table :data="canon" border size="small" style="max-width: 640px; cursor: pointer" @row-click="openCanon">
          <el-table-column prop="kind" label="类型" width="100" />
          <el-table-column prop="name" label="名称" width="160" />
          <el-table-column prop="sortNo" label="排序" width="80" />
          <el-table-column label="操作" width="80">
            <template #default="{ row }"><el-button size="small" @click.stop="openCanon(row)">查看</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`伏笔账本（${foreshadows.length}）`">
        <el-table :data="foreshadows" border size="small">
          <el-table-column prop="code" label="编号" width="80" />
          <el-table-column prop="content" label="内容" min-width="300" />
          <el-table-column prop="plantedIn" label="埋设章" width="80" />
          <el-table-column prop="recoveredIn" label="回收章" width="80" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="{ planned: 'info', planted: 'warning', recovered: 'success' }[row.status]">
                {{ { planned: '计划', planted: '已埋', recovered: '已收' }[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="风格包">
        <el-tabs v-model="styleTab">
          <el-tab-pane label="规则正文" name="rules">
            <div style="white-space: pre-wrap; font-size: 13px; max-height: 560px; overflow-y: auto">{{ style?.rulesMd }}</div>
          </el-tab-pane>
          <el-tab-pane label="指纹基线" name="fingerprint">
            <el-table :data="fingerprintRows" border size="small" style="max-width: 720px">
              <el-table-column prop="metric" label="指标" width="220" />
              <el-table-column prop="value" label="基线值" width="100" />
              <el-table-column prop="tolerance" label="容差" width="100" />
              <el-table-column prop="abs_max" label="天花板" width="100" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="editor" :title="editing ? `${editing.kind} / ${editing.name}` : ''" size="50%">
      <el-input v-if="editing" v-model="editing.content" type="textarea" :rows="24" />
      <div style="margin-top: 10px">
        <el-button type="primary" @click="saveCanon">保存</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const canon = ref([])
const foreshadows = ref([])
const style = ref(null)
const styleTab = ref('rules')
const editing = ref(null)
const editor = ref(false)

const fingerprintRows = computed(() => {
  try {
    const baseline = JSON.parse(style.value?.fingerprintJson || '{}').baseline || {}
    return Object.entries(baseline).map(([metric, r]) => ({ metric, ...r }))
  } catch {
    return []
  }
})

async function openCanon(row) {
  editing.value = await api.get(`/api/canon/${row.id}`)
  editor.value = true
}

async function saveCanon() {
  try {
    await api.put(`/api/canon/${editing.value.id}`, { content: editing.value.content })
    ElMessage.success('已保存')
    editor.value = false
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  const novels = await api.get('/api/novels')
  const novelId = novels[0].id
  canon.value = await api.get(`/api/novels/${novelId}/canon`)
  foreshadows.value = await api.get(`/api/novels/${novelId}/foreshadows`)
  style.value = await api.get(`/api/novels/${novelId}/style`)
})
</script>
