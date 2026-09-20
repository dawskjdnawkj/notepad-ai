import request from './request'

export interface TagBrief {
  id: number
  name: string
}

export interface ReminderInfo {
  id: number
  remindAt: string
  status: number  // 0 待提醒 / 1 已提醒 / 2 已取消
}

export interface NoteListItem {
  id: number
  title: string
  contentPreview: string
  pinned: number
  notebookId: number
  notebookName: string
  tags: TagBrief[]
  createTime: string
  updateTime: string
}

export interface NoteDetail {
  id: number
  title: string
  content: string
  pinned: number
  notebookId: number
  notebookName: string
  tags: TagBrief[]
  reminder: ReminderInfo | null
  createTime: string
  updateTime: string
}

export interface NoteCreateRequest {
  notebookId?: number
  title?: string
  content?: string
  tagIds?: number[]
}

export interface NoteUpdateRequest {
  title: string
  content: string
  tagIds: number[]
}

export interface NoteMoveRequest {
  notebookId: number
}

export interface NoteListParams {
  page?: number
  pageSize?: number
  notebookId?: number
  tagId?: number
  keyword?: string
  createdDate?: string
}

export interface PageResult<T> {
  total: number
  page: number
  pageSize: number
  records: T[]
}

/** 笔记列表 / 搜索 / 筛选 */
export function getNoteList(params: NoteListParams): Promise<PageResult<NoteListItem>> {
  return request.get('/notes', { params })
}

/** 笔记详情 */
export function getNoteDetail(id: number): Promise<NoteDetail> {
  return request.get(`/notes/${id}`)
}

/** 新建笔记 */
export function createNote(data: NoteCreateRequest): Promise<NoteDetail> {
  return request.post('/notes', data)
}

/** 保存笔记（自动保存） */
export function saveNote(id: number, data: NoteUpdateRequest): Promise<NoteDetail> {
  return request.put(`/notes/${id}`, data)
}

/** 移动笔记 */
export function moveNote(id: number, notebookId: number): Promise<null> {
  return request.put(`/notes/${id}/notebook`, { notebookId })
}

/** 置顶 / 取消置顶 */
export function pinNote(id: number, pinned: boolean): Promise<null> {
  return request.put(`/notes/${id}/pin`, { pinned })
}

/** 重命名笔记标题 */
export function renameNote(id: number, title: string): Promise<null> {
  return request.put(`/notes/${id}/title`, { title })
}

/** 删除笔记（移入回收站） */
export function deleteNote(id: number): Promise<null> {
  return request.delete(`/notes/${id}`)
}
