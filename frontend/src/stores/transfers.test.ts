// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
const authState = vi.hoisted(() => ({ id: 'account-a' }));
vi.mock('./auth', () => ({ useAuth: () => ({ user: { value: { id: authState.id } } }) }));
import { useTransfers, isActive } from './transfers';
const values = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => { values.set(key, value); },
  clear: () => values.clear(),
});
const store = useTransfers();
beforeEach(() => { store.reset(); localStorage.clear(); authState.id = 'account-a'; store.setDownloadLimit(2); });
describe('transfer queue', () => {
  it('synchronizes upload progress and retry without duplicate tasks', () => {
    const task = { id: 'u', name: 'test.zip', size: 1024, status: 'waiting' as const, progress: 0 };
    store.syncUpload(task);
    store.syncUpload({ ...task, status: 'uploading', progress: 45 });
    expect(store.tasks).toHaveLength(1);
    expect(store.activeCount.value).toBe(1);
    store.clearFinished('upload');
    expect(store.tasks).toHaveLength(1);
    store.syncUpload({ ...task, status: 'failed', error: 'network' });
    store.syncUpload({ ...task, status: 'success', progress: 100 });
    expect(isActive(store.tasks[0]!)).toBe(false);
    store.clearFinished('upload');
    expect(store.tasks).toHaveLength(0);
  });
  it('does not resurrect cleared upload records but shows deliberate retries', () => {
    const task = { id: 'cleared', name: 'retry.zip', size: 1, status: 'failed' as const, progress: 0 };
    store.syncUpload(task);
    store.clearFinished('upload');
    store.syncUpload(task);
    expect(store.tasks).toHaveLength(0);
    store.syncUpload({ ...task, status: 'preparing' });
    expect(store.tasks).toHaveLength(1);
    store.removeUpload(task.id);
    expect(store.tasks).toHaveLength(1);
  });
  it('limits downloads, reports progress, and starts waiting work after completion', async () => {
    store.setDownloadLimit(1);
    let finish!: () => void;
    const first = store.enqueueDownload('one.zip', 5, async progress => { progress(35); await new Promise<void>(resolve => { finish = resolve; }); });
    const secondRun = vi.fn(async () => {});
    const second = store.enqueueDownload('two.zip', 10, secondRun);
    await Promise.resolve();
    expect(store.tasks.map(task => task.status)).toEqual(['downloading', 'waiting']);
    expect(store.tasks[0]!.progress).toBe(35);
    store.clearFinished('download');
    expect(store.tasks).toHaveLength(2);
    expect(secondRun).not.toHaveBeenCalled();
    finish();
    await Promise.all([first, second]);
    expect(secondRun).toHaveBeenCalledOnce();
    expect(store.tasks.every(task => task.status === 'success' && task.progress === 100)).toBe(true);
  });
  it('clears in-memory tasks and rejects queued work without restarting it after reset', async () => {
    store.setDownloadLimit(1);
    let finish!: () => void;
    const active = store.enqueueDownload('private.txt', 1, async () => { await new Promise<void>(resolve => { finish = resolve; }); });
    const waitingRun = vi.fn(async () => {});
    const waiting = store.enqueueDownload('queued.txt', 1, waitingRun);
    const cancelled = expect(waiting).rejects.toThrow('会话已重置，取消等待下载');
    await Promise.resolve();
    store.reset();
    expect(store.tasks).toHaveLength(0);
    finish();
    await Promise.all([active, cancelled]);
    expect(waitingRun).not.toHaveBeenCalled();
    expect(store.tasks).toHaveLength(0);
  });
  it('records failure and keeps the queue moving', async () => {
    store.setDownloadLimit(1);
    const failed = store.enqueueDownload('bad', 1, async () => { throw new Error('offline'); });
    const outcome = expect(failed).rejects.toThrow('offline');
    const success = store.enqueueDownload('good', 1, async () => {});
    await Promise.all([outcome, success]);
    expect(store.tasks[0]!.error).toBe('offline');
    expect(store.tasks[1]!.status).toBe('success');
    store.setDownloadLimit(100);
    expect(store.settings.downloadLimit).toBe(1);
  });
  it('restores history for the same account and marks interrupted work', async () => {
    store.syncUpload({ id: 'finished', name: 'done.txt', size: 3, status: 'success', progress: 100 });
    store.syncUpload({ id: 'running', name: 'busy.txt', size: 4, status: 'uploading', progress: 42 });
    store.reset();
    vi.resetModules();
    const restored = (await import('./transfers')).useTransfers();
    expect(restored.tasks.map(task => task.name)).toEqual(['done.txt', 'busy.txt']);
    expect(restored.tasks[0]!.status).toBe('success');
    expect(restored.tasks[1]).toMatchObject({ status: 'cancelled', error: '页面刷新，传输已中断' });
    expect(restored.activeCount.value).toBe(0);
  });
  it('keeps records separate between accounts and persists clearing', () => {
    store.syncUpload({ id: 'a', name: 'private.txt', size: 1, status: 'success', progress: 100 });
    authState.id = 'account-b';
    const other = useTransfers();
    expect(other.tasks).toHaveLength(0);
    other.syncUpload({ id: 'b', name: 'other.txt', size: 1, status: 'success', progress: 100 });
    authState.id = 'account-a';
    expect(useTransfers().tasks.map(task => task.name)).toEqual(['private.txt']);
    store.clearFinished('upload');
    store.reset();
    expect(useTransfers().tasks).toHaveLength(0);
    authState.id = 'account-b';
    expect(useTransfers().tasks.map(task => task.name)).toEqual(['other.txt']);
  });
});
