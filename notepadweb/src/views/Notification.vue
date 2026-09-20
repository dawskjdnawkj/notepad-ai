<template>
  <div class="notification-page">
    <div class="page-header">
      <h1>通知中心</h1>
      <div class="header-right">
        <el-button-group v-if="localUnread > 0">
          <el-button text type="primary" @click="handleMarkAll">全部已读</el-button>
        </el-button-group>
        <span class="unread-tip" v-if="localUnread > 0">共 {{ localUnread }} 条未读</span>
      </div>
    </div>

    <div v-if="loading">
      <el-skeleton :rows="3" animated v-for="i in 3" :key="i" />
    </div>
    <el-empty v-else-if="notifications.length === 0" description="暂无通知" />
    <div v-else class="notification-list">
      <div
        v-for="n in notifications"
        :key="n.id"
        class="notification-item"
        :class="{ unread: n.isRead === 0 }"
        @click="handleClick(n)"
      >
        <div class="notif-dot" v-if="n.isRead === 0"></div>
        <div class="notif-body">
          <div class="notif-header">
            <span class="notif-title">{{ n.title }}</span>
            <span class="notif-time">{{ n.createTime }}</span>
          </div>
          <p class="notif-content">{{ n.content }}</p>
        </div>
        <span v-if="n.isRead === 0" class="unread-label">未读</span>
        <span v-else class="read-label">已读</span>
      </div>
    </div>

    <div class="pagination-wrap" v-if="total > pageSize">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @change="loadList"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getNotificationList, markRead, markAllRead, type NotificationVO } from '../api/notification'
import { useNotificationStore } from '../stores/notification'

const router = useRouter()
const notificationStore = useNotificationStore()

const loading = ref(false)
const notifications = ref<NotificationVO[]>([])
const total = ref(0)
const localUnread = ref(0)
const page = ref(1)
const pageSize = ref(20)

async function loadList() {
  loading.value = true
  try {
    const result = await getNotificationList({ page: page.value, pageSize: pageSize.value })
    notifications.value = result.records
    total.value = result.total
    localUnread.value = result.records.filter(n => n.isRead === 0).length
  } catch { /* ignore */ }
  finally { loading.value = false }
}

async function handleClick(n: NotificationVO) {
  // 标记已读
  if (n.isRead === 0) {
    try {
      await markRead(n.id)
      n.isRead = 1
      localUnread.value = Math.max(0, localUnread.value - 1)
      notificationStore.decreaseUnread(1)
    } catch { /* ignore */ }
  }
  // 跳转到对应笔记
  if (n.noteId) {
    router.push(`/notes/${n.noteId}`)
  } else {
    ElMessage.warning('关联笔记已删除')
  }
}

async function handleMarkAll() {
  try {
    const result = await markAllRead()
    notifications.value.forEach(n => n.isRead = 1)
    localUnread.value = 0
    notificationStore.clearUnread()
    ElMessage.success(`已标记 ${result.count} 条为已读`)
  } catch { /* ignore */ }
}

onMounted(loadList)
</script>

<style scoped>
.notification-page { padding: 0; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.page-header h1 { margin: 0; font-size: 22px; }
.header-right { display: flex; align-items: center; gap: 12px; }
.unread-tip { font-size: 13px; color: #909399; }
.notification-list { display: flex; flex-direction: column; gap: 8px; }
.notification-item {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  padding: 14px 20px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}
.notification-item:hover { background: #f5f7fa; }
.notification-item.unread { background: #ecf5ff; }
.notif-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #409eff;
  flex-shrink: 0;
}
.notif-body { flex: 1; min-width: 0; }
.notif-header { display: flex; justify-content: space-between; margin-bottom: 4px; }
.notif-title { font-size: 14px; font-weight: 500; }
.notif-time { font-size: 12px; color: #c0c4cc; flex-shrink: 0; }
.notif-content { margin: 0; font-size: 13px; color: #909399; }
.unread-label { font-size: 11px; color: #409eff; flex-shrink: 0; }
.read-label { font-size: 11px; color: #c0c4cc; flex-shrink: 0; }
.pagination-wrap { margin-top: 20px; display: flex; justify-content: center; }
</style>
