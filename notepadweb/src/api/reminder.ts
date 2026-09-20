import request from './request'

export interface ReminderResult {
  id: number
  noteId: number
  remindAt: string
  status: number
}

/** 设置提醒 */
export function setReminder(noteId: number, unit: 'HOURS' | 'DAYS' | 'MINUTES', value: number): Promise<ReminderResult> {
  return request.post(`/notes/${noteId}/reminder`, { unit, value })
}

/** 取消提醒 */
export function cancelReminder(noteId: number): Promise<null> {
  return request.delete(`/notes/${noteId}/reminder`)
}
