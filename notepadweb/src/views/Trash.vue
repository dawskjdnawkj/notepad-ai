<template>
  <div class="trash-page">
    <div class="page-header">
      <h1>回收站</h1>
      <span class="tip">超过 30 天的笔记将自动清空</span>
      <div class="header-right">
        <el-button type="danger" text @click="handleClearAll" :disabled="total === 0">清空回收站</el-button>
      </div>
    </div>

    <div v-if="loading" class="loading-wrap">
      <el-skeleton :rows="3" animated v-for="i in 3" :key="i" />
    </div>
    <el-empty v-else-if="items.length === 0" description="回收站为空" />
    <div v-else class="trash-list">
      <div v-for="item in items" :key="item.id" class="trash-card">
        <div class="card-body">
          <h3>{{ item.title || '无标题' }}</h3>
          <p>{{ item.contentPreview || '暂无内容' }}</p>
          <div class="meta">
            <el-tag size="small">{{ item.notebookName }}</el-tag>
            <span class="delete-time">删除于 {{ item.deleteTime }}</span>
          </div>
        </div>
        <div class="card-actions">
          <el-button size="small" type="primary" @click="handleRestore(item.id)">恢复</el-button>
          <el-button size="small" type="danger" text @click="handleForceDelete(item.id)">彻底删除</el-button>
        </div>
      </div>
    </div>

    <div class="pagination-wrap" v-if="total > 0">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20]"
        layout="total, sizes, prev, pager, next"
        @change="loadList"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getTrashList, restoreNote, forceDeleteNote, clearTrash, type TrashItem } from '../api/trash'

const loading = ref(false)
const items = ref<TrashItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

async function loadList() {
  loading.value = true
  try {
    const result = await getTrashList({ page: page.value, pageSize: pageSize.value })
    items.value = result.records
    total.value = result.total
  } catch { /* ignore */ }
  finally { loading.value = false }
}

async function handleRestore(id: number) {
  await restoreNote(id)
  ElMessage.success('笔记已恢复')
  loadList()
}

async function handleForceDelete(id: number) {
  try {
    await ElMessageBox.confirm('彻底删除后不可恢复，确定吗？', '警告', { type: 'warning' })
  } catch { return }
  await forceDeleteNote(id)
  ElMessage.success('已彻底删除')
  loadList()
}

async function handleClearAll() {
  try {
    await ElMessageBox.confirm('将彻底清空回收站中所有笔记，不可恢复，确定吗？', '警告', { type: 'warning' })
  } catch { return }
  const result = await clearTrash()
  ElMessage.success(`已清空 ${result.count} 条笔记`)
  loadList()
}

onMounted(loadList)
</script>

<style scoped>
.trash-page { padding: 0; }
.page-header {
  display: flex;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 24px;
}
.page-header h1 { margin: 0; font-size: 22px; }
.tip { color: #999; font-size: 13px; }
.header-right { margin-left: auto; }
.loading-wrap { padding: 20px 0; }
.trash-list { display: flex; flex-direction: column; gap: 12px; }
.trash-card {
  background: #fff;
  padding: 16px 20px;
  border-radius: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-body { flex: 1; }
.card-body h3 { margin: 0 0 6px; font-size: 15px; }
.card-body p {
  margin: 0 0 8px;
  color: #909399;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 500px;
}
.meta { display: flex; align-items: center; gap: 12px; }
.delete-time { font-size: 12px; color: #c0c4cc; }
.card-actions { display: flex; gap: 8px; flex-shrink: 0; }
.pagination-wrap { margin-top: 20px; display: flex; justify-content: center; }
</style>
