import { computed, reactive } from 'vue'
import * as api from '../api/drive'

export type FileKind = api.FileKind
export interface DriveItem extends api.DriveItemResponse {
}
export interface ShareRecord {
  id: string
  itemId: string
  code: string
  expiresAt: string
  cancelled: boolean
}

const state = reactive({ files: [] as DriveItem[], shares: [] as ShareRecord[], loading: false, error: '' })

function upsert(item: DriveItem) {
  const index = state.files.findIndex((entry) => entry.id === item.id)
  if (index < 0) state.files.push(item)
  else state.files[index] = item
}

async function run<T>(operation: () => Promise<T>): Promise<T> {
  state.loading = true
  state.error = ''
  try { return await operation() }
  catch (error) { state.error = api.driveErrorMessage(error, '文件操作失败'); throw error }
  finally { state.loading = false }
}

export function useDrive() {
  const list = (parentId: string | null) => computed(() => state.files.filter((item) => !item.deletedAt && item.parentId === parentId))
  const get = (itemId: string) => state.files.find((item) => item.id === itemId)
  const load = (parentId: string | null) => run(async () => {
    const items = await api.listFiles(parentId)
    state.files = state.files.filter((item) => item.parentId !== parentId)
    items.forEach(upsert)
    return items
  })
  const loadTrash = () => run(async () => {
    const items = await api.listTrash()
    state.files = state.files.filter((item) => !item.deletedAt)
    items.forEach(upsert)
    return items
  })
  const createFolder = (name: string, parentId: string | null) => run(async () => {
    const item = await api.createFolder(name, parentId); upsert(item); return item
  })
  const rename = (itemId: string, name: string) => run(async () => {
    const item = await api.renameFile(itemId, name); upsert(item); return item
  })
  const move = (itemId: string, parentId: string | null) => run(async () => {
    const item = await api.moveFile(itemId, parentId); upsert(item); return item
  })
  const download = (itemId: string) => {
    const item = get(itemId)
    if (!item) return Promise.reject(new Error('文件不存在'))
    return run(() => api.downloadFile(item.id, item.name))
  }
  const addUploaded = (item: DriveItem) => upsert(item)
  const trash = (itemIds: string[]) => run(async () => {
    const items = await api.trashFiles(itemIds); items.forEach(upsert); return items
  })
  const restore = (itemIds: string[]) => run(async () => {
    const items = await api.restoreFiles(itemIds); items.forEach(upsert); return items
  })
  const removeForever = (itemIds: string[]) => run(async () => {
    await api.deleteFilesForever(itemIds)
    state.files = state.files.filter((item) => !itemIds.includes(item.id))
  })
  const emptyTrash = () => run(async () => {
    await api.emptyTrash()
    state.files = state.files.filter((item) => !item.deletedAt)
  })
  const share = (_itemId: string, _days: number): ShareRecord => { throw new Error('文件分享接口暂未开放') }
  const cancelShare = (_shareId: string) => undefined
  return { state, list, get, load, loadTrash, createFolder, addUploaded, rename, move, download, trash, restore, removeForever, emptyTrash, share, cancelShare }
}

export function formatSize(size: number) {
  if (!size) return '—'
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
