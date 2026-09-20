import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUnreadCount } from '../api/notification'

/**
 * 通知未读数（侧边栏铃铛角标）。
 * 由 Layout 轮询刷新，通知页标记已读后立即更新，避免等下一次轮询。
 */
export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref(0)

  async function refreshUnreadCount() {
    try {
      const data = await getUnreadCount()
      unreadCount.value = data.count
    } catch { /* ignore */ }
  }

  function decreaseUnread(count = 1) {
    unreadCount.value = Math.max(0, unreadCount.value - count)
  }

  function clearUnread() {
    unreadCount.value = 0
  }

  return { unreadCount, refreshUnreadCount, decreaseUnread, clearUnread }
})
