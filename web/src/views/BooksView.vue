<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 12px">
      <div style="display: flex; align-items: center; gap: 10px">
        <b style="font-size: 15px">书籍管理</b>
        <span style="font-size: 12px; color: #999">全部作品在此查询/编辑/删除；「打开」设为工作台当前书。删除为软删（数据库可恢复）。</span>
        <div style="flex: 1" />
        <el-button type="primary" size="small" @click="router.push('/wizard')">＋ 开新书</el-button>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="books" border size="small" v-loading="loading" @row-dblclick="(row) => openBook(row)">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="书名" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="{ fontWeight: row.id === currentId ? 600 : 400 }">{{ row.title }}</span>
            <el-tag v-if="row.id === currentId" size="small" style="margin-left: 6px">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="简介" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="章数" width="70">
          <template #default="{ row }">{{ row.chapterCount }}</template>
        </el-table-column>
        <el-table-column label="审批模式" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.approvalMode === 'auto' ? 'success' : 'warning'">
              {{ row.approvalMode === 'auto' ? '自动' : '人工' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'draft'" size="small" type="info">草稿</el-tag>
            <el-tag v-else size="small">正式</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="无人续跑" width="130">
          <template #default="{ row }">
            <template v-if="row.autoContinue">
              <el-tag size="small" type="success">开</el-tag>
              <span style="font-size: 12px; color: #999; margin-left: 4px">目标 {{ row.targetChapters ?? '∞' }} 章</span>
            </template>
            <span v-else style="color: #999; font-size: 12px">关</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ (row.createTime || '').toString().replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openBook(row)">打开</el-button>
            <el-button v-if="row.status === 'draft'" size="small" type="warning" link
                       @click="router.push('/wizard')">继续向导</el-button>
            <el-button size="small" link @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" link @click="delBook(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editOpen" title="编辑书籍信息" width="560px">
      <el-form label-width="70px">
        <el-form-item label="书名" required>
          <el-input v-model="editForm.title" maxlength="256" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="editForm.description" type="textarea" :rows="3" placeholder="一句话简介（可选）" />
        </el-form-item>
      </el-form>
      <div style="font-size: 12px; color: #999">
        改文风/门禁/衍生参数/无人续跑不在本页——分别在工作台「衍生参数」「评审标准」与素材库对应页签。
      </div>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { getSelectedNovelId, setSelectedNovelId } from '../novelSelection'

const router = useRouter()
const books = ref([])
const loading = ref(false)
const currentId = ref(getSelectedNovelId())
const editOpen = ref(false)
const editForm = ref({})
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    books.value = await api.get('/api/novels')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 打开＝设为工作台当前书并跳转。 */
function openBook(row) {
  setSelectedNovelId(row.id)
  router.push('/chapters')
}

function openEdit(row) {
  editForm.value = { id: row.id, title: row.title, description: row.description || '' }
  editOpen.value = true
}

async function saveEdit() {
  if (!editForm.value.title.trim()) {
    ElMessage.warning('书名必填')
    return
  }
  saving.value = true
  try {
    await api.put(`/api/novels/${editForm.value.id}`, {
      title: editForm.value.title.trim(),
      description: editForm.value.description
    })
    ElMessage.success('已保存')
    editOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function delBook(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `将删除《${row.title}》（${row.chapterCount} 章）。此操作软删该书，生成数据保留但界面不再可见。请输入完整书名确认：`,
      '删除书籍', { confirmButtonText: '删除', cancelButtonText: '取消', inputPattern: new RegExp(`^${row.title}$`), inputErrorMessage: '书名不匹配' })
    if (value !== row.title) return
  } catch (e) {
    return
  }
  try {
    await api.delete(`/api/novels/${row.id}`)
    ElMessage.success('已删除')
    if (currentId.value === row.id) {
      const rest = books.value.filter((b) => b.id !== row.id)
      setSelectedNovelId(rest.length ? rest[0].id : null)
      currentId.value = rest.length ? rest[0].id : null
    }
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>
