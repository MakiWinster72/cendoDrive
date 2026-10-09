import { computed, reactive, watch } from 'vue';
import { useAuth } from './auth';
import { clearTransferRecords, deleteTransferRecord, listTransferRecords, saveTransferRecord } from '../api/transfers';
export type TransferStatus = 'waiting' | 'preparing' | 'uploading' | 'downloading' | 'success' | 'failed' | 'cancelled';
export interface TransferTask { id: string; direction: 'upload' | 'download' | 'transfer'; name: string; size: number; status: TransferStatus; progress: number; createdAt: number; error?: string; }
const tasks = reactive<TransferTask[]>([]);
const settings = reactive({ downloadLimit: 2 });
const pending: { task: TransferTask; operation: (progress: (value: number) => void) => Promise<void>; resolve: () => void; reject: (error: unknown) => void }[] = [];
let active = 0;
const clearedUploads = new Set<string>();
let owner: string | null = null;
const storageKey = (id: string) => `cendo-drive-transfers:${id}`;
const pendingChanges = new Map<string, TransferTask | null>();
const pendingClears = new Set<TransferTask['direction']>();
let flushPromise: Promise<void> | null = null;
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
      (task.direction === 'upload' || task.direction === 'download' || task.direction === 'transfer') &&
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
  pendingChanges.clear(); pendingClears.clear();
  if (!id) return;
  try {
    const changes: unknown = JSON.parse(localStorage.getItem(`${storageKey(id)}:changes`) ?? '{}');
    if (changes && typeof changes === 'object' && !Array.isArray(changes))
      for (const [key, task] of Object.entries(changes)) pendingChanges.set(key, task as TransferTask | null);
    const clears: unknown = JSON.parse(localStorage.getItem(`${storageKey(id)}:clears`) ?? '[]');
    if (Array.isArray(clears)) for (const direction of clears)
      if (direction === 'upload' || direction === 'download' || direction === 'transfer') pendingClears.add(direction);
    if (localStorage.getItem(`${storageKey(id)}:migrated`) !== '1') {
      for (const task of tasks) if (!isActive(task)) pendingChanges.set(task.id, { ...task });
      localStorage.setItem(`${storageKey(id)}:migrated`, '1');
      persistPending();
    }
  } catch { /* Keep local history when storage is unavailable. */ }
}
function persistPending() {
  if (!owner) return;
  try {
    localStorage.setItem(`${storageKey(owner)}:changes`, JSON.stringify(Object.fromEntries(pendingChanges)));
    localStorage.setItem(`${storageKey(owner)}:clears`, JSON.stringify([...pendingClears]));
  } catch { /* Retry from memory while the page is open. */ }
}
function flushChanges(): Promise<void> {
  if (flushPromise) return flushPromise.then(() => flushChanges());
  const id = owner;
  if (!id || (!pendingChanges.size && !pendingClears.size)) return Promise.resolve();
  flushPromise = (async () => {
    try {
      for (const direction of [...pendingClears]) {
        if (owner !== id) return;
        await clearTransferRecords(direction);
        if (owner !== id) return;
        pendingClears.delete(direction); persistPending();
      }
      for (const [key, task] of [...pendingChanges]) {
        if (owner !== id) return;
        if (task) await saveTransferRecord(task);
        else await deleteTransferRecord(key);
        if (owner !== id) return;
        if (pendingChanges.get(key) === task) pendingChanges.delete(key);
        persistPending();
      }
    } catch { /* Keep pending changes for the next refresh or transfer. */ }
  })().finally(() => { flushPromise = null; });
  return flushPromise;
}
function queueChange(task: TransferTask | null, id: string) {
  if (!owner) return;
  if (task && !tasks.some(entry => entry.id === id && entry.direction === task.direction)) return;
  pendingChanges.set(id, task ? { ...task } : null);
  persistPending();
  void flushChanges();
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
      .then(() => { entry.task.status = 'success'; entry.task.progress = 100; queueChange(entry.task, entry.task.id); entry.resolve(); })
      .catch(error => { entry.task.status = 'failed'; entry.task.error = error instanceof Error ? error.message : '下载失败'; queueChange(entry.task, entry.task.id); entry.reject(error); })
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
    const changed = !existing || existing.status !== task.status || existing.progress !== task.progress || existing.error !== task.error;
    if (existing) Object.assign(existing, task);
    else tasks.push({ ...task, direction: 'upload', createdAt: Date.now() });
    if (changed && !isActive(task as TransferTask)) queueChange(existing ?? tasks.at(-1)!, task.id);
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
    if (owner) {
      pendingClears.add(direction);
      for (const [id, task] of pendingChanges) if (task?.direction === direction) pendingChanges.delete(id);
      persistPending(); void flushChanges();
    }
  }
  function removeUpload(id: string) {
    ensureOwner();
    const index = tasks.findIndex(task => task.id === id && task.direction === 'upload' && !['preparing', 'uploading'].includes(task.status));
    if (index >= 0) { tasks.splice(index, 1); queueChange(null, id); }
  }
  function recordResult(direction: TransferTask['direction'], name: string, size: number, error?: string) {
    ensureOwner();
    const task: TransferTask = { id: crypto.randomUUID(), direction, name, size,
      status: error ? 'failed' : 'success', progress: error ? 0 : 100, createdAt: Date.now(), ...(error ? { error } : {}) };
    tasks.push(task); queueChange(task, task.id);
  }
  function recordTransfer(name: string, size: number) { recordResult('transfer', name, size); }
  function recordDownload(name: string, size: number) { recordResult('download', name, size); }
  function recordUpload(name: string, size: number) { recordResult('upload', name, size); }
  function recordFailure(direction: TransferTask['direction'], name: string, size: number, error: string) {
    recordResult(direction, name, size, error);
  }
  async function refresh() {
    ensureOwner();
    const id = owner;
    if (!id) return;
    await flushChanges();
    try {
      const remote = await listTransferRecords();
      if (owner !== id) return;
      const local = tasks.filter(isActive);
      const pending = tasks.filter(task => pendingChanges.has(task.id) && pendingChanges.get(task.id) !== null);
      const removed = new Set([...pendingChanges].filter(([, task]) => task === null).map(([key]) => key));
      const merged = [...remote.filter(task => !pendingClears.has(task.direction) && !removed.has(task.id)), ...pending, ...local];
      tasks.splice(0, tasks.length, ...[...new Map(merged.map(task => [task.id, task])).values()]);
    } catch { /* Preserve local history while offline. */ }
  }
  function setDownloadLimit(value: number) { if ([1, 2, 3, 5].includes(value)) { settings.downloadLimit = value; pump(); } }
  function reset() {
    for (const entry of pending.splice(0)) entry.reject(new Error('会话已重置，取消等待下载'));
    owner = null;
    tasks.splice(0);
    clearedUploads.clear();
    pendingChanges.clear(); pendingClears.clear();
  }
  return { tasks, settings, syncUpload, enqueueDownload, recordTransfer, recordDownload, recordUpload, recordFailure, refresh, clearFinished, removeUpload, setDownloadLimit, reset, activeCount: computed(() => tasks.filter(isActive).length) };
}
