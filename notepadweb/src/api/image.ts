import request from './request'

export interface ImageUploadResult {
  url: string
}

/** 上传图片（返回访问 URL） */
export function uploadImage(file: File): Promise<ImageUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/images/upload', formData)
}
