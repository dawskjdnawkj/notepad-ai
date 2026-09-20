import request from './request'
import type { PageResult } from './note'

export interface NotebookVO {
  id: number
  name: string
  isDefault: boolean
  noteCount: number
  createTime: string
}

export interface NotebookNoteVO {
  id: number
  title: string
}

export interface NotebookCreateRequest {
  name: string
}

export interface NotebookRenameRequest {
  name: string
}

/** 笔记本列表 */
export function getNotebookList(): Promise<NotebookVO[]> {
  return request.get('/notebooks')
}

/** 分页获取笔记本下的笔记（id + 标题） */
export function getNotebookNotes(id: number, page = 1, pageSize = 30): Promise<PageResult<NotebookNoteVO>> {
  return request.get(`/notebooks/${id}/notes`, { params: { page, pageSize } })
}

/** 新建笔记本 */
export function createNotebook(params: NotebookCreateRequest): Promise<NotebookVO> {
  return request.post('/notebooks', params)
}

/** 重命名笔记本 */
export function renameNotebook(id: number, params: NotebookRenameRequest): Promise<NotebookVO> {
  return request.put(`/notebooks/${id}`, params)
}

/** 删除笔记本 */
export function deleteNotebook(id: number): Promise<null> {
  return request.delete(`/notebooks/${id}`)
}
