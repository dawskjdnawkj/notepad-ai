import request from './request'

export interface TagVO {
  id: number
  name: string
  noteCount: number
  createTime: string
}

export interface TagCreateRequest {
  name: string
}

/** 标签列表 */
export function getTagList(): Promise<TagVO[]> {
  return request.get('/tags')
}

/** 新建标签 */
export function createTag(params: TagCreateRequest): Promise<TagVO> {
  return request.post('/tags', params)
}

/** 删除标签 */
export function deleteTag(id: number): Promise<null> {
  return request.delete(`/tags/${id}`)
}
