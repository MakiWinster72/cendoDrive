import { computed, reactive, watch } from 'vue';
import { useAuth } from './auth';
export type TransferStatus = 'waiting' | 'preparing' | 'uploading' | 'downloading' | 'success' | 'failed' | 'cancelled';
export interface TransferTask { id: string; direction: 'upload' | 'download'; name: string; size: number; status: TransferStatus; progress: number; createdAt: number; error?: string; }
const tasks = reactive<TransferTask[]>([]);
const settings = reactive({ downloadLimit: 2 });
const pending: { task: TransferTask; operation: (progress: (value: number) => void) => Promise<void>; resolve: () => void; reject: (error: unknown) => void }[] = [];
let active = 0;
const clearedUploads = new Set<string>();
let owner: string | null = null;
const storageKey = (id: string) => `cendo-drive-transfers:${id}`;
const statuses: TransferStatus[] = ['waiting', 'preparing', 'uploading', 'downloading', 'success', 'failed', 'cancelled'];
function savedTasks(id: string): TransferTask[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(storageKey(id)) ?? '[]');
    if (!Array.isArray(value)) return [];
    return value.filter((task): task is TransferTask =>
      task !== null && typeof task === 'object' &&
      typeof task.id === 'string' && typeof task.name === 'string' &&
      typeof task.size === 'number' && Number.isFinite(task.size) &&
      typeof task.createdAt === 'number' && Number.isFinite(task.createdAt) &&
      typeof task.progress === 'number' && Number.isFinite(task.progress) &&
      (task.direction === 'upload' || task.direction === 'download') &&
      statuses.includes(task.status) &&
      (task.error === undefined || typeof task.error === 'string')
    ).map(task => isActive(task)
      ? { ...task, status: 'cancelled' as const, error: '页面刷新，传输已中断' }
      : task);
  } catch { return []; }
}
function ensureOwner() {
  const id = useAuth().user.value?.id ?? null;
  if (owner === id) return;
  owner = id;
  tasks.splice(0, tasks.length, ...(id ? savedTasks(id) : []));
  clearedUploads.clear();
}
watch(tasks, () => {
  if (!owner) return;
  try { localStorage.setItem(storageKey(owner), JSON.stringify(tasks)); }
  catch { /* History remains available in memory when storage is unavailable. */ }
}, { deep: true, flush: 'sync' });
export const isActive = (task: TransferTask) => ['waiting', 'preparing', 'uploading', 'downloading'].includes(task.status);
function pump() {
  while (active < settings.downloadLimit && pending.length) {
    const entry = pending.shift()!;
    active++;
    entry.task.status = 'downloading';
    void Promise.resolve().then(() => entry.operation(value => { entry.task.progress = Math.min(100, Math.max(0, value)); }))
      .then(() => { entry.task.status = 'success'; entry.task.progress = 100; entry.resolve(); })
      .catch(error => { entry.task.status = 'failed'; entry.task.error = error instanceof Error ? error.message : '下载失败'; entry.reject(error); })
      .finally(() => { active--; pump(); });
  }
}
export function useTransfers() {
  ensureOwner();
  function syncUpload(task: Omit<TransferTask, 'direction' | 'createdAt'>) {
    ensureOwner();
    // The upload panel still retains completed rows after the queue clears them.
    if (clearedUploads.has(task.id)) {
      if (!['preparing', 'uploading'].includes(task.status)) return;
      clearedUploads.delete(task.id);
    }
    const existing = tasks.find(entry => entry.id === task.id && entry.direction === 'upload');
    if (existing) Object.assign(existing, task);
    else tasks.push({ ...task, direction: 'upload', createdAt: Date.now() });
  }
  function enqueueDownload(name: string, size: number, operation: (progress: (value: number) => void) => Promise<void>) {
    ensureOwner();
    const task = reactive<TransferTask>({ id: crypto.randomUUID(), name, size, direction: 'download', status: 'waiting', progress: 0, createdAt: Date.now() });
    tasks.push(task);
    return new Promise<void>((resolve, reject) => { pending.push({ task, operation, resolve, reject }); pump(); });
  }
  function clearFinished(direction: TransferTask['direction']) {
    ensureOwner();
    for (let i = tasks.length - 1; i >= 0; i--) {
      const task = tasks[i]!;
      if (task.direction !== direction || isActive(task)) continue;
      if (direction === 'upload') clearedUploads.add(task.id);
      tasks.splice(i, 1);
    }
  }
  function removeUpload(id: string) {
    ensureOwner();
    const index = tasks.findIndex(task => task.id === id && task.direction === 'upload' && !['preparing', 'uploading'].includes(task.status));
    if (index >= 0) tasks.splice(index, 1);
  }
  function setDownloadLimit(value: number) { if ([1, 2, 3, 5].includes(value)) { settings.downloadLimit = value; pump(); } }
  function reset() {
    for (const entry of pending.splice(0)) entry.reject(new Error('会话已重置，取消等待下载'));
    owner = null;
    tasks.splice(0);
    clearedUploads.clear();
  }
  return { tasks, settings, syncUpload, enqueueDownload, clearFinished, removeUpload, setDownloadLimit, reset, activeCount: computed(() => tasks.filter(isActive).length) };
}
