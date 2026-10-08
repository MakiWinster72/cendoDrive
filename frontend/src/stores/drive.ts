import { computed, reactive } from "vue";
import * as api from "../api/drive";
import { useTransfers } from "./transfers";
import * as shareApi from "../api/shares";

export type FileKind = api.FileKind;
export interface DriveItem extends api.DriveItemResponse {}
export type ShareRecord = shareApi.ShareRecord;
const state = reactive({
  files: [] as DriveItem[], shares: [] as ShareRecord[],
  favorites: [] as DriveItem[], hidden: [] as DriveItem[],
  usage: null as api.StorageUsage | null, usageError: "",
  loading: false, error: "",
});
let pending = 0;
function upsert(item: DriveItem) {
  const index = state.files.findIndex(entry => entry.id === item.id);
  if (index < 0) state.files.push(item);
  else state.files[index] = item;
}
function ancestorFlag(item: DriveItem, flag: "hidden" | "deletedAt"): boolean {
  const seen = new Set<string>();
  let current: DriveItem | undefined = item;
  while (current && !seen.has(current.id)) {
    if (current[flag]) return true;
    seen.add(current.id);
    current = state.files.find(entry => entry.id === current!.parentId);
  }
  return false;
}
function isHidden(item: DriveItem) { return ancestorFlag(item, "hidden"); }
function isDeleted(item: DriveItem) { return ancestorFlag(item, "deletedAt"); }
async function loadUsage() {
  state.usageError = "";
  try { state.usage = await api.getUsage(); }
  catch (error) { state.usageError = api.driveErrorMessage(error, "容量获取失败，请刷新重试"); }
}
async function run<T>(operation: () => Promise<T>, refreshUsage = false): Promise<T> {
  pending++; state.loading = true; state.error = "";
  try {
    const result = await operation();
    if (refreshUsage) await loadUsage();
    return result;
  } catch (error) {
    state.error = api.driveErrorMessage(error, "文件操作失败");
    throw error;
  } finally { pending--; state.loading = pending > 0; }
}
function removeCachedTree(ids: string[]) {
  const removed = new Set(ids);
  let changed = true;
  while (changed) {
    changed = false;
    for (const item of state.files) {
      if (item.parentId && removed.has(item.parentId) && !removed.has(item.id)) {
        removed.add(item.id); changed = true;
      }
    }
  }
  state.files = state.files.filter(item => !removed.has(item.id));
  state.favorites = state.favorites.filter(item => !removed.has(item.id));
  state.hidden = state.hidden.filter(item => !removed.has(item.id));
}
export function useDrive() {
  const list = (parentId: string | null) => computed(() => state.files.filter(item => !isDeleted(item) && item.parentId === parentId));
  const get = (itemId: string) => state.files.find(item => item.id === itemId);
  const load = (parentId: string | null) => run(async () => {
    const items = await api.listFiles(parentId);
    state.files = state.files.filter(item => item.deletedAt || item.parentId !== parentId);
    items.forEach(upsert); return items;
  });
  const loadTrash = () => run(async () => {
    const items = await api.listTrash();
    state.files = state.files.filter(item => !item.deletedAt);
    items.forEach(upsert); return items;
  });
  const loadFavorites = () => run(async () => {
    state.favorites = await api.listFavorites(); state.favorites.forEach(upsert); return state.favorites;
  });
  const loadHidden = () => run(async () => {
    state.hidden = await api.listHidden(); state.hidden.forEach(upsert); return state.hidden;
  });
  const loadShares = () => run(async () => { state.shares = await shareApi.listShares(); return state.shares; });
  const createFolder = (name: string, parentId: string | null) => run(async () => {
    const item = await api.createFolder(name, parentId); upsert(item); return item;
  });
  const rename = (id: string, name: string) => run(async () => { const item = await api.renameFile(id, name); upsert(item); return item; });
  const move = (id: string, parentId: string | null) => run(async () => { const item = await api.moveFile(id, parentId); upsert(item); return item; });
  const moveMany = (ids: string[], parentId: string | null) => run(async () => { const items = await api.moveFiles(ids, parentId); items.forEach(upsert); return items; });
  const copy = (ids: string[], parentId: string | null) => run(async () => { const items = await api.copyFiles(ids, parentId); items.forEach(upsert); return items; }, true);
  const favorite = (ids: string[], value: boolean) => run(async () => { const items = await api.setFavorite(ids, value); items.forEach(upsert); return items; });
  const hide = (ids: string[], value: boolean) => run(async () => { const items = await api.setHidden(ids, value); items.forEach(upsert); return items; });
  const organize = (ids: string[]) => run(async () => { const items = await api.organizeFiles(ids); items.forEach(upsert); return items; });
  const download = (id: string) => {
    const item = get(id);
    if (!item) return Promise.reject(new Error("文件不存在"));
    return run(() => useTransfers().enqueueDownload(item.name, item.size,
      progress => api.downloadFile(item.id, item.name, progress)));
  };
  const addUploaded = (item: DriveItem) => { upsert(item); void loadUsage(); };
  const trash = (ids: string[]) => run(async () => { const items = await api.trashFiles(ids); items.forEach(upsert); return items; }, true);
  const restore = (ids: string[]) => run(async () => { const items = await api.restoreFiles(ids); items.forEach(upsert); return items; }, true);
  const removeForever = (ids: string[]) => run(async () => { await api.deleteFilesForever(ids); removeCachedTree(ids); }, true);
  const emptyTrash = () => run(async () => {
    await api.emptyTrash(); removeCachedTree(state.files.filter(item => item.deletedAt).map(item => item.id));
  }, true);
  const share = (id: string, expiresInSeconds: number, extractionCode?: string) => run(async () => {
    const response = await shareApi.createShare(id, expiresInSeconds, extractionCode);
    const record = extractionCode ? { ...response, extractionCode } : response;
    state.shares.unshift(record); return record;
  });
  const cancelShare = (id: string) => run(async () => {
    await shareApi.cancelShare(id); const record = state.shares.find(item => item.id === id); if (record) record.status = "CANCELLED";
  });
  const reset = () => {
    useTransfers().reset();
    state.files = []; state.shares = []; state.favorites = []; state.hidden = [];
    state.usage = null; state.usageError = ""; state.error = "";
  };
  return { state, list, get, load, loadTrash, loadFavorites, loadHidden, loadShares, loadUsage,
    createFolder, addUploaded, rename, move, moveMany, copy, favorite, hide, organize,
    download, trash, restore, removeForever, emptyTrash, share, cancelShare, isHidden, isDeleted, reset };
}
export function formatSize(size: number) {
  if (!size) return "—";
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}
export function formatBytes(size: number) {
  if (!size) return "0 B";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  const power = Math.min(Math.floor(Math.log(size) / Math.log(1024)), units.length - 1);
  return `${(size / 1024 ** power).toFixed(power ? 1 : 0)} ${units[power]}`;
}
