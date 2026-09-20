import request from './request'

export interface NotificationVO {
  id: number
  type: number
  title: string
  content: string
  noteId: number | null
  isRead: number
  createTime: string
}

export interface UnreadCountResult {
  count: number
}

/** 通知列表 */
export function getNotificationList(params: { page?: number; pageSize?: number }): Promise<{ total: number; page: number; pageSize: number; records: NotificationVO[] }> {
  return request.get('/notifications', { params })
}

/** 未读数量 */
export function getUnreadCount(): Promise<UnreadCountResult> {
  return request.get('/notifications/unread-count')
}

/** 标记已读 */
export function markRead(id: number): Promise<null> {
  return request.put(`/notifications/${id}/read`)
}

/** 全部已读 */
export function markAllRead(): Promise<{ count: number }> {
  return request.put('/notifications/read-all')
}
