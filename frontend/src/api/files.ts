import axios from 'axios'
import http from './http'
import type { DriveItem } from '../stores/drive'

export interface UploadFileOptions {
  file: File
  parentId: string | null
  signal: AbortSignal
  onProgress: (progress: number) => void
}

export async function uploadFile({ file, parentId, signal, onProgress }: UploadFileOptions): Promise<DriveItem> {
  const form = new FormData()
  form.append('file', file)
  if (parentId !== null) form.append('parentId', parentId)

  const { data } = await http.post<DriveItem>('/files/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    signal,
    timeout: 0,
    onUploadProgress: (event) => {
      if (event.total) onProgress(Math.min(99, Math.round((event.loaded / event.total) * 100)))
    },
  })
  return data
}

interface UploadApiError {
  code?: string
  message?: string
  fields?: Record<string, string>
}

export function uploadErrorMessage(error: unknown): string {
  if (axios.isCancel(error)) return '已取消上传'
  if (!axios.isAxiosError<UploadApiError>(error)) return '上传失败，请重试'
  if (!error.response) return '网络连接失败，请检查后端服务'

  const { code, fields } = error.response.data ?? {}
  if (error.response.status === 400 && code === 'INVALID_INPUT') {
    if (fields && Object.keys(fields).some((field) => field.toLowerCase().includes('parent'))) {
      return '目标文件夹信息无效，请重新选择上传位置'
    }
    if (fields && Object.keys(fields).some((field) => field.toLowerCase().includes('file'))) {
      return '文件信息无效，请重新选择文件'
    }
    return '文件或目标文件夹信息无效'
  }

  if (code === 'UPLOAD_NOT_FOUND') return '上传会话已失效，请重新上传'
  if (code === 'INVALID_CHUNK') return '分片数据无效，请重试'
  if (code === 'CHUNK_INCOMPLETE') return '分片尚未上传完整，请重试'
  if (code === 'CHECKSUM_MISMATCH') return '文件校验失败，请重新上传'
  if (code === 'UPLOAD_IN_PROGRESS') return '文件正在合并中，请稍后重试'
  if (error.response.status === 413 || code === 'MAX_UPLOAD_SIZE_EXCEEDED') return '文件超过 100MB 大小限制'
  if (code === 'STORAGE_QUOTA_EXCEEDED') return '存储空间不足，请清理文件后重试'
  if (code === 'NAME_CONFLICT') return '目标文件夹已存在同名文件'
  if (code === 'INVALID_NAME') return '文件名称不合法'
  if (code === 'NOT_A_FOLDER') return '目标位置不是文件夹，请重新选择上传位置'
  if (code === 'FILE_NOT_FOUND') return '目标文件夹不存在，请重新选择上传位置'
  if (error.response.status === 401 && code === 'UNAUTHORIZED') return '登录状态已失效，请重新登录'
  if (error.response.status === 404 && code === 'NOT_FOUND') return '上传接口或请求资源不存在'
  if (error.response.status === 405 && code === 'METHOD_NOT_ALLOWED') return '上传接口不支持此请求方式'
  if (error.response.status === 415 && code === 'UNSUPPORTED_MEDIA_TYPE') return '不支持此文件类型'
  if (code === 'STORAGE_UNAVAILABLE') return '存储服务暂不可用，请稍后重试'
  if (error.response.status >= 500 || code === 'INTERNAL_ERROR') return '服务器处理失败，请稍后重试'
  return '上传失败，请重试'
}

export function isUploadCancelled(error: unknown, signal: AbortSignal): boolean {
  return signal.aborted || axios.isCancel(error)
}
