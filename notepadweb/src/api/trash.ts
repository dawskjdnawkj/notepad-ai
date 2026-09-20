import request from './request'
import type { PageResult } from './note'

export interface TrashItem {
  id: number
  title: string
  contentPreview: string
  notebookId: number
  notebookName: string
  deleteTime: string
}

/** 回收站列表 */
export function getTrashList(params: { page?: number; pageSize?: number }): Promise<PageResult<TrashItem>> {
  return request.get('/trash/notes', { params })
}

/** 恢复笔记 */
export function restoreNote(id: number): Promise<null> {
  return request.put(`/trash/notes/${id}/restore`)
}

/** 彻底删除 */
export function forceDeleteNote(id: number): Promise<null> {
  return request.delete(`/trash/notes/${id}`)
}

/** 清空回收站 */
export function clearTrash(): Promise<{ count: number }> {
  return request.delete('/trash/notes')
}
