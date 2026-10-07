import { computed, reactive } from 'vue';
export type TransferStatus = 'waiting' | 'preparing' | 'uploading' | 'downloading' | 'success' | 'failed' | 'cancelled';
export interface TransferTask { id: string; direction: 'upload' | 'download'; name: string; size: number; status: TransferStatus; progress: number; createdAt: number; error?: string; }
const tasks = reactive<TransferTask[]>([]);
const settings = reactive({ downloadLimit: 2 });
const pending: { task: TransferTask; operation: (progress: (value: number) => void) => Promise<void>; resolve: () => void; reject: (error: unknown) => void }[] = [];
let active = 0;
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
  function syncUpload(task: Omit<TransferTask, 'direction' | 'createdAt'>) {
    const existing = tasks.find(entry => entry.id === task.id && entry.direction === 'upload');
    if (existing) Object.assign(existing, task);
    else tasks.push({ ...task, direction: 'upload', createdAt: Date.now() });
  }
  function enqueueDownload(name: string, size: number, operation: (progress: (value: number) => void) => Promise<void>) {
    const task = reactive<TransferTask>({ id: crypto.randomUUID(), name, size, direction: 'download', status: 'waiting', progress: 0, createdAt: Date.now() });
    tasks.push(task);
    return new Promise<void>((resolve, reject) => { pending.push({ task, operation, resolve, reject }); pump(); });
  }
  function clearFinished(direction: TransferTask['direction']) {
    for (let i = tasks.length - 1; i >= 0; i--) if (tasks[i]!.direction === direction && !isActive(tasks[i]!)) tasks.splice(i, 1);
  }
  function removeUpload(id: string) {
    const index = tasks.findIndex(task => task.id === id && task.direction === 'upload' && !['preparing', 'uploading'].includes(task.status));
    if (index >= 0) tasks.splice(index, 1);
  }
  function setDownloadLimit(value: number) { if ([1, 2, 3, 5].includes(value)) { settings.downloadLimit = value; pump(); } }
  return { tasks, settings, syncUpload, enqueueDownload, clearFinished, removeUpload, setDownloadLimit, activeCount: computed(() => tasks.filter(isActive).length) };
}
