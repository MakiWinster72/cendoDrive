import { computed, reactive } from 'vue'

export type FileKind = 'folder' | 'image' | 'video' | 'audio' | 'pdf' | 'doc' | 'other'
export interface DriveItem {
  id: string
  name: string
  kind: FileKind
  size: number
  parentId: string | null
  updatedAt: string
  deletedAt?: string
  originalParentId?: string | null
}
export interface ShareRecord {
  id: string
  itemId: string
  code: string
  expiresAt: string
  cancelled: boolean
}

const FILE_KEY = 'cendo-drive-files'
const SHARE_KEY = 'cendo-drive-shares'
const now = () => new Date().toISOString()
const id = () => `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

const initialFiles: DriveItem[] = [
  { id: 'work', name: '工作资料', kind: 'folder', size: 0, parentId: null, updatedAt: '2026-09-27T18:42:00' },
  { id: 'travel', name: '旅行照片', kind: 'folder', size: 0, parentId: null, updatedAt: '2026-09-25T20:16:00' },
  { id: 'plan', name: '项目方案.pdf', kind: 'pdf', size: 9017754, parentId: null, updatedAt: '2026-09-21T10:08:00' },
  { id: 'summary', name: '季度总结.docx', kind: 'doc', size: 2516582, parentId: null, updatedAt: '2026-09-18T16:30:00' },
  { id: 'sunset', name: '海边日落.jpg', kind: 'image', size: 5347738, parentId: null, updatedAt: '2026-09-12T12:05:00' },
  { id: 'meeting', name: '会议纪要.docx', kind: 'doc', size: 328104, parentId: 'work', updatedAt: '2026-09-27T15:22:00' },
]

function read<T>(key: string, fallback: T): T {
  try { return JSON.parse(localStorage.getItem(key) || '') as T } catch { return fallback }
}
const state = reactive({ files: read<DriveItem[]>(FILE_KEY, initialFiles), shares: read<ShareRecord[]>(SHARE_KEY, []) })
const save = () => {
  localStorage.setItem(FILE_KEY, JSON.stringify(state.files))
  localStorage.setItem(SHARE_KEY, JSON.stringify(state.shares))
}

export function useDrive() {
  const list = (parentId: string | null, deleted = false) => computed(() => state.files.filter((item) => deleted ? Boolean(item.deletedAt) : !item.deletedAt && item.parentId === parentId))
  const get = (itemId: string) => state.files.find((item) => item.id === itemId)
  const createFolder = (name: string, parentId: string | null) => {
    if (!name.trim()) throw new Error('文件夹名称不能为空')
    if (state.files.some((item) => !item.deletedAt && item.parentId === parentId && item.name === name.trim())) throw new Error('同名文件夹已存在')
    state.files.push({ id: id(), name: name.trim(), kind: 'folder', size: 0, parentId, updatedAt: now() }); save()
  }
  const addUploaded = (item: DriveItem) => {
    const existing = state.files.findIndex((file) => file.id === item.id)
    if (existing >= 0) state.files.splice(existing, 1, item)
    else state.files.push(item)
    save()
  }
  const rename = (itemId: string, name: string) => {
    const item = get(itemId); if (!item || !name.trim()) throw new Error('名称不能为空')
    item.name = name.trim(); item.updatedAt = now(); save()
  }
  const move = (itemId: string, parentId: string | null) => {
    const item = get(itemId); if (!item) return
    if (item.id === parentId) throw new Error('不能移动到自身')
    item.parentId = parentId; item.updatedAt = now(); save()
  }
  const trash = (itemIds: string[]) => {
    const mark = (target: DriveItem) => {
      target.originalParentId = target.parentId; target.deletedAt = now()
      if (target.kind === 'folder') state.files.filter((item) => item.parentId === target.id && !item.deletedAt).forEach(mark)
    }
    itemIds.forEach((itemId) => { const item = get(itemId); if (item) mark(item) }); save()
  }
  const restore = (itemIds: string[]) => {
    itemIds.forEach((itemId) => { const item = get(itemId); if (item) { item.deletedAt = undefined; item.parentId = item.originalParentId && get(item.originalParentId) ? item.originalParentId : null; item.updatedAt = now() } }); save()
  }
  const removeForever = (itemIds: string[]) => { state.files = state.files.filter((item) => !itemIds.includes(item.id)); save() }
  const emptyTrash = () => { state.files = state.files.filter((item) => !item.deletedAt); save() }
  const share = (itemId: string, days: number) => {
    const record: ShareRecord = { id: Math.random().toString(36).slice(2, 10), itemId, code: Math.random().toString().slice(2, 6), expiresAt: new Date(Date.now() + days * 86400000).toISOString(), cancelled: false }
    state.shares.push(record); save(); return record
  }
  const cancelShare = (shareId: string) => { const item = state.shares.find((share) => share.id === shareId); if (item) item.cancelled = true; save() }

  return { state, list, get, createFolder, addUploaded, rename, move, trash, restore, removeForever, emptyTrash, share, cancelShare }
}

export function formatSize(size: number) {
  if (!size) return '—'
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
