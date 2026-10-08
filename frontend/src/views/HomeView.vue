<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import {
  Archive,
  Bell,
  CalendarDays,
  CheckSquare,
  Clock3,
  ClipboardCheck,
  CloudUpload,
  Coins,
  Crown,
  Gift,
  MonitorSmartphone,
  LogOut,
  PackageOpen,
  ScanLine,
  TicketPercent,
  Wallet,
  ChevronLeft,
  ArrowDownUp,
  ShieldCheck,
  ChevronDown,
  ChevronRight,
  CircleUserRound,
  Cloud,
  Copy,
  Download,
  Eye,
  EyeOff,
  File,
  FileText,
  Folder,
  HardDrive,
  House,
  Image,
  LayoutGrid,
  List,
  Menu,
  MessageCircle,
  MoreHorizontal,
  Music2,
  Plus,
  Printer,
  RotateCcw,
  Search,
  Settings,
  Share2,
  SlidersHorizontal,
  Sparkles,
  Star,
  Trash2,
  Upload,
  UserRound,
  Video,
  WandSparkles,
  X,
  Info,
  LockKeyhole,
  Pencil,
  FolderInput,
} from "lucide-vue-next";
import BrandLogo from "../components/BrandLogo.vue";
import { fileCategory, iconForFile } from "../components/fileIcon";
import HomeToolIcon from "../components/HomeToolIcon.vue";
import UploadPanel from "../components/UploadPanel.vue";
import TransferPage from "../components/TransferPage.vue";
import AccountPage from "../components/AccountPage.vue";
import { getProfile, getAvatar } from "../api/users";
import { invalidateSession } from "../stores/auth";
import FilePreview from "../components/FilePreview.vue";
import FileSearchPanel from "../components/FileSearchPanel.vue";
import type { FileSearchHit, SearchType } from "../api/drive";
import FileTools from "../components/FileTools.vue";
import InlineNameEditor from "../components/InlineNameEditor.vue";
import { useNameEdit } from "../components/useNameEdit";
import type { ToolAction } from "../components/fileTools";
import ShareList from "../components/ShareList.vue";
import ShareLinkDialog from "../components/ShareLinkDialog.vue";
import ShareSettingsDialog from "../components/ShareSettingsDialog.vue";
import MobileMyShares from "../components/MobileMyShares.vue";
import MobileShareHub from "../components/MobileShareHub.vue";
import UnavailableFeatureDialog from "../components/UnavailableFeatureDialog.vue";
import { Send } from "lucide-vue-next";
import "../styles/profile.css";
import "../styles/home.css";
import "../styles/selection.css";
import "../styles/file-list.css";
import "../styles/folder.css";
import FolderGlyph from "../components/FolderGlyph.vue";

import "../styles/share.css";
import { useAuth } from "../stores/auth";
import { formatBytes, formatSize, useDrive, type DriveItem } from "../stores/drive";
import { shareClipboardText, shareErrorMessage, type ShareRecord } from "../api/shares";

type Mode =
  | "all"
  | "recent"
  | "image"
  | "video"
  | "doc"
  | "audio"
  | "other"
  | "shares"
  | "trash"
  | "favorites"
  | "hidden";
const router = useRouter(),
  auth = useAuth(),
  drive = useDrive();
const view = ref<"list" | "grid">("list"),
  mode = ref<Mode>("all"),
  currentFolder = ref<string | null>(null);
const navigationFolders = ref<DriveItem[]>([]);
function navigationFolder(id: string) { return drive.get(id) ?? navigationFolders.value.find(item => item.id === id); }
const keyword = ref(""),
  checked = ref<string[]>([]),
  menuOpen = ref(false),
  mobileNavOpen = ref(false),
  uploadPanelOpen = ref(false);
const createdShare = ref<ShareRecord | null>(null);
const shareTarget = ref<DriveItem | null>(null);
const previewTarget = ref<DriveItem | null>(null);
const toolsTarget = ref<{ action: ToolAction; items: DriveItem[] } | null>(null);
const mobileViewport = ref(window.innerWidth < 768);
function updateViewport() { mobileViewport.value = window.innerWidth < 768; }
const { edit: nameEdit, saving: nameSaving, error: nameError, beginCreate, beginRename, submit: saveName, cancel: cancelName } = useNameEdit((_item, kind) => { checked.value = []; flash(kind === "create" ? "文件夹创建成功" : "重命名成功"); });
function openTools(action: ToolAction, items = checked.value.map(id => drive.get(id)).filter((item): item is DriveItem => !!item)) {
  if (!items.length) return;
  checked.value = items.map(item => item.id);
  toolsTarget.value = { action, items };
}
async function toolsChanged(message: string) {
  checked.value = [];
  flash(message);
  if (mode.value === "all") await loadFolder(currentFolder.value);
  else await changeMode(mode.value);
}

const serverSearchEnabled = computed(() => ["all", "image", "video", "doc", "audio", "other"].includes(mode.value));
const searchActive = computed(() => serverSearchEnabled.value && !!keyword.value.trim());
const searchType = computed<SearchType>(() => mode.value === "all" ? "all" : ["image", "video", "doc", "audio", "other"].includes(mode.value) ? mode.value as SearchType : "all");
const sortBy = ref<"name" | "time" | "size">("time"),
  notice = ref(""),
  unavailableMessage = ref(""),
  loggingOut = ref(false),
  downloading = ref(false);
const mobileTab = ref<"home" | "files" | "share" | "profile">("home");
watch(keyword, () => { checked.value = []; });
const showTransfers = ref(false);
const showAccount=ref(false),avatarUrl=ref("");
let avatarGeneration=0,homeAlive=true;
async function refreshAvatar() {
  const generation=++avatarGeneration,id=auth.user.value?.id;
  if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value);
  avatarUrl.value="";
  if (!id) return;
  try {
    const profile=await getProfile();
    if (!profile.hasAvatar || !homeAlive || generation!==avatarGeneration || auth.user.value?.id!==id) return;
    const blob=await getAvatar();
    if (homeAlive && generation===avatarGeneration && auth.user.value?.id===id) avatarUrl.value=URL.createObjectURL(blob);
  } catch { /* Account management exposes a retry; the header retains an initials fallback. */ }
}
async function accountSignedOut(reason: "password"|"deletion",purgeAfter?: string) {
  invalidateSession(); drive.reset();
  await router.replace({name:"login",query:reason==="password" ? {passwordChanged:"1"} : {accountDeleted:"1",purgeAfter}});
}
function openTransfers() {
  folderMenuOpen.value = false;
  showTransfers.value = true;
}
watch([currentFolder, mode, keyword, mobileTab], cancelName, { flush: "sync" });
const showMyShares = ref(false);
const mySharesError = ref("");
const recentVisible = ref(true);
const recentItems = computed(() =>
  [...drive.state.files]
    .filter((item) => !drive.isDeleted(item) && !drive.isHidden(item) && item.kind !== "folder")
    .sort((a, b) => +new Date(b.updatedAt) - +new Date(a.updatedAt))
    .slice(0, 3),
);
const savedItems = computed(() =>
  drive.state.files
    .filter(
      (item) =>
        !drive.isDeleted(item) && !drive.isHidden(item) &&
        drive.state.shares.some(
          (share) =>
            share.fileId === item.id &&
            share.status === "ACTIVE" &&
            new Date(share.expiresAt) > new Date(),
        ),
    )
    .slice(0, 3),
);
function openHomeCategory(next: Mode) {
  mobileTab.value = "files";
  void changeMode(next);
}
const profileShortcuts = [
  { label: "我的收藏", icon: Sparkles },
  { label: "隐藏空间", icon: LockKeyhole },
  { label: "我的分享", icon: Share2 },
  { label: "回收站", icon: Trash2 },
  { label: "设备管理", icon: MonitorSmartphone },
  { label: "我的打印", icon: Printer },
  { label: "转存与下载", icon: CloudUpload },
];
const profileServices = [
  { label: "借钱", icon: Wallet, tone: "rose" },
  { label: "免费领会员", icon: Gift, tone: "mint" },
  { label: "照片冲印", icon: Printer, tone: "orange" },
  { label: "奇妙赏", icon: Coins, tone: "rose" },
  { label: "一刻相册", icon: Image, tone: "gold" },
  { label: "活动中心", icon: TicketPercent, tone: "orange" },
];
const displayName = computed(
  () =>
    auth.user.value?.nickname || auth.user.value?.username || "CendoDrive 用户",
);
const searchPrompts = computed(() => [
  `${displayName.value}的云端空间`,
  "AI学习笔记",
  "简单听记",
  "文件清理",
  "照片动起来",
]);
const searchPromptIndex = ref(0);
let searchPromptTimer: number | undefined;
const modeNames: Record<Mode, string> = {
  all: "全部文件",
  recent: "最近",
  image: "图片",
  video: "视频",
  doc: "文档",
  audio: "音频",
  other: "其他",
  shares: "我的分享",
  trash: "回收站",
  favorites: "我的收藏",
  hidden: "隐藏空间",
};
const title = computed(() =>
  currentFolder.value
    ? navigationFolder(currentFolder.value)?.name || "文件夹"
    : modeNames[mode.value],
);
const folderMenuOpen = ref(false);
watch([currentFolder, mobileTab], () => { folderMenuOpen.value = false; });
const folderCrumbs = computed(() => {
  const crumbs: DriveItem[] = [], seen = new Set<string>();
  let id = currentFolder.value;
  while (id && !seen.has(id)) {
    seen.add(id);
    const folder = navigationFolder(id);
    if (!folder) break;
    crumbs.unshift(folder);
    id = folder.parentId;
  }
  return crumbs;
});
const folderDateText = (date: string) => new Date(date).toLocaleString("sv-SE", {
  year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
});
const folders = computed(() =>
  drive.state.files.filter((item) => item.kind === "folder" && !item.deletedAt),
);
const uploadFolders = computed(() => [
  { id: null, name: displayName.value },
  ...folders.value.map((folder) => ({ id: folder.id, name: folder.name })),
]);
const trashCount = computed(
  () => drive.state.files.filter((item) => Boolean(item.deletedAt)).length,
);
const filteredFiles = computed(() => {
  let items: DriveItem[];
  if (mode.value === "trash")
    items = drive.state.files.filter((item) => Boolean(item.deletedAt));
  else if (mode.value === "favorites") items = drive.state.favorites;
  else if (mode.value === "hidden") items = drive.state.hidden;
  else if (mode.value === "shares") {
    const ids = new Set(
      drive.state.shares
        .filter(
          (share) =>
            share.status === "ACTIVE" && new Date(share.expiresAt) > new Date(),
        )
        .map((share) => share.fileId),
    );
    items = drive.state.files.filter(
      (item) => ids.has(item.id) && !item.deletedAt,
    );
  } else if (mode.value === "all")
    items = drive.state.files.filter(
      (item) => !item.deletedAt && item.parentId === currentFolder.value,
    );
  else {
    items = drive.state.files.filter(
      (item) =>
        !item.deletedAt &&
        item.kind !== "folder" &&
        (mode.value === "recent" || fileCategory(item) === mode.value),
    );
  }
  items = items.filter((item) =>
    item.name.toLowerCase().includes(keyword.value.trim().toLowerCase()) &&
    (mode.value === "trash" || !drive.isDeleted(item)) &&
    (mode.value === "trash" || mode.value === "hidden" ||
      (mode.value === "all" && currentFolder.value && drive.get(currentFolder.value) && drive.isHidden(drive.get(currentFolder.value)!)) || !drive.isHidden(item)),
  );
  return [...items].sort((a, b) =>
    sortBy.value === "name"
      ? a.name.localeCompare(b.name, "zh-CN")
      : sortBy.value === "size"
        ? b.size - a.size
        : +new Date(mode.value === "trash" ? b.deletedAt! : b.updatedAt) -
          +new Date(mode.value === "trash" ? a.deletedAt! : a.updatedAt),
  );
});
const allVisibleSelected = computed({
  get: () =>
    filteredFiles.value.length > 0 &&
    filteredFiles.value.every((item) => checked.value.includes(item.id)),
  set: (selected: boolean) => {
    checked.value = selected ? filteredFiles.value.map((item) => item.id) : [];
  },
});
const dateText = (date: string) =>
  new Date(date)
    .toLocaleString("zh-CN", { hour12: false })
    .replaceAll("/", "-");
function flash(text: string) {
  notice.value = text;
  window.setTimeout(() => (notice.value = ""), 2200);
}
function showUnavailable(message: string) {
  unavailableMessage.value = message;
}
async function changeMode(next: Mode) {
  mode.value = next;
  currentFolder.value = null;
  checked.value = [];
  mobileNavOpen.value = false;
  try {
    if (next === "all") await drive.load(null);
    else if (next === "trash") await drive.loadTrash();
    else if (next === "favorites") await drive.loadFavorites();
    else if (next === "hidden") await drive.loadHidden();
    else if (next === "shares") await drive.loadShares();
  } catch {
    alert(drive.state.error);
  }
}
async function openMyShares() {
  showMyShares.value = true;
  mySharesError.value = "";
  try {
    await drive.loadShares();
  } catch (error) {
    mySharesError.value = shareErrorMessage(error, "分享列表加载失败");
  }
}
async function loadFolder(parentId: string | null) {
  try {
    await drive.load(parentId);
    currentFolder.value = parentId;
  } catch {
    alert(drive.state.error);
  }
}
async function openSearchHit(hit: FileSearchHit, locate = false) {
  if (drive.state.loading || nameSaving.value) return;
  if (!locate && hit.file.kind === "file") { previewTarget.value = hit.file; return; }
  const target = locate ? hit.file.parentId : hit.file.id;
  const query = keyword.value;
  try {
    await drive.load(target);
    if (keyword.value !== query) return;
    navigationFolders.value = [...hit.ancestors, ...(hit.file.kind === "folder" ? [hit.file] : [])];
    cancelName(); checked.value = []; folderMenuOpen.value = false;
    mode.value = "all"; currentFolder.value = target; keyword.value = ""; mobileTab.value = "files";
  } catch { alert(drive.state.error); }
}
async function openItem(item: DriveItem) {
  if (mode.value === "trash" || drive.state.loading || nameSaving.value) return;
  if (item.kind === "folder") {
    await navigateFolder(item.id);
  } else {
    previewTarget.value = item;
  }
}
async function navigateFolder(parentId: string | null) {
  if (drive.state.loading || nameSaving.value) return;
  cancelName();
  checked.value = [];
  keyword.value = "";
  folderMenuOpen.value = false;
  mode.value = "all";
  await loadFolder(parentId);
}
async function goParent() {
  await navigateFolder(currentFolder.value ? navigationFolder(currentFolder.value)?.parentId ?? null : null);
}
async function goRoot() {
  await navigateFolder(null);
}
async function createFolder() {
  if (nameSaving.value) return;
  mobileTab.value = "files"; keyword.value = ""; checked.value = [];
  if (mode.value !== "all") { mode.value = "all"; await loadFolder(currentFolder.value); }
  beginCreate(currentFolder.value, drive.state.files.filter(item => item.parentId === currentFolder.value && !item.deletedAt).map(item => item.name));
}
function chooseFiles() {
  uploadPanelOpen.value = true;
}
function handleUploaded(item: DriveItem) {
  drive.addUploaded(item);
  flash(`已上传：${item.name}`);
}
function renameItem(item: DriveItem) {
  if (mode.value === "trash" || nameSaving.value) return;
  checked.value = []; mobileTab.value = "files"; beginRename(item);
}
function startMobileRename() {
  if (checked.value.length !== 1) return;
  const item = drive.get(checked.value[0]!); if (item) renameItem(item);
}
function itemMenu(item: DriveItem) { openTools("menu", [item]); }
async function removeSelected(ids = checked.value) {
  if (!ids.length || !confirm("确定移入回收站吗？")) return;
  try {
    await drive.trash(ids);
    checked.value = [];
    flash("已移入回收站");
  } catch {
    alert(drive.state.error);
  }
}
async function restoreSelected(ids = checked.value) {
  if (!ids.length) return;
  try {
    await drive.restore(ids);
    checked.value = checked.value.filter((id) => !ids.includes(id));
    flash(ids.length > 1 ? `已恢复 ${ids.length} 个项目` : "文件已恢复");
  } catch {
    alert(drive.state.error);
  }
}
async function permanentDelete(ids = checked.value) {
  if (
    !ids.length ||
    !confirm(`永久删除 ${ids.length} 个项目后无法恢复，是否继续？`)
  )
    return;
  try {
    await drive.removeForever(ids);
    checked.value = checked.value.filter((id) => !ids.includes(id));
    flash(ids.length > 1 ? `已永久删除 ${ids.length} 个项目` : "已永久删除");
  } catch {
    alert(drive.state.error);
  }
}
async function clearTrash() {
  if (
    !trashCount.value ||
    !confirm(`将永久删除回收站中的 ${trashCount.value} 个项目，是否继续？`)
  )
    return;
  try {
    await drive.emptyTrash();
    checked.value = [];
    flash("回收站已清空");
  } catch {
    alert(drive.state.error);
  }
}
async function shareSelected() {
  if (checked.value.length !== 1) return alert("请选择一个文件进行分享");
  const item = drive.get(checked.value[0]!);
  if (!item || item.kind === "folder") return alert("目前只支持分享单个文件");
  shareTarget.value = item;
}
async function copyShareLink(share: ShareRecord) {
  const link = shareClipboardText(share, location.origin);
  try {
    await navigator.clipboard.writeText(link);
    flash("分享链接已复制");
  } catch {
    window.prompt("复制分享链接", link);
  }
}
async function copyShareLinks(shares: ShareRecord[]) {
  const links = shares
    .map(
      (share) => shareClipboardText(share, location.origin),
    )
    .join("\n");
  try {
    await navigator.clipboard.writeText(links);
    flash(
      shares.length === 1
        ? "分享链接已复制"
        : `已复制 ${shares.length} 条分享链接`,
    );
  } catch {
    window.prompt("复制分享链接", links);
  }
}
async function cancelShareRecord(share: ShareRecord) {
  if (!confirm(`确定取消“${share.fileName}”的分享吗？取消后链接立即失效。`))
    return;
  try {
    await drive.cancelShare(share.id);
    flash("分享已取消");
  } catch (error) {
    alert(shareErrorMessage(error, "取消分享失败"));
  }
}
async function cancelShareRecords(shares: ShareRecord[]) {
  if (
    !confirm(`确定取消选中的 ${shares.length} 个分享吗？取消后链接立即失效。`)
  )
    return;
  let completed = 0;
  for (const share of shares) {
    try {
      await drive.cancelShare(share.id);
      completed += 1;
    } catch (error) {
      alert(
        `${completed ? `已取消 ${completed} 个分享；` : ""}${shareErrorMessage(error, "取消分享失败，请重试")}`,
      );
      return;
    }
  }
  flash(`已取消 ${completed} 个分享`);
}
async function openProfileShortcut(label: string) {
  if (label === "回收站") {
    mobileTab.value = "files";
    await changeMode("trash");
  } else if (label === "我的收藏" || label === "隐藏空间") openHomeCategory(label === "我的收藏" ? "favorites" : "hidden");
  else if (label === "我的分享") await openMyShares();
  else if (label === "转存与下载") showTransfers.value = true;
}
function openProfileService(label: string) {
  if (label === "免费领会员") {
    void router.push({ name: "membership" });
    return;
  }
  showUnavailable(`${label}服务暂未开放。`);
}
async function downloadSelected() {
  if (downloading.value) return;
  const files = checked.value.filter((id) => drive.get(id)?.kind !== "folder");
  if (!files.length) return flash("文件夹暂不支持下载");
  downloading.value = true;
  try {
    const results = await Promise.allSettled(
      files.map((id) => drive.download(id)),
    );
    if (results.some((result) => result.status === "rejected"))
      throw new Error("部分下载失败");
    flash(`已下载 ${files.length} 个文件`);
  } catch {
    alert(drive.state.error || "下载失败");
  } finally {
    downloading.value = false;
  }
}
const selectionActions = [
  { label: "下载", icon: Download },
  { label: "分享", icon: Share2 },
  { label: "删除", icon: Trash2 },
  { label: "智能整理", icon: Sparkles },
  { label: "收藏", icon: Star },
  { label: "添加至", icon: Folder },
  { label: "移入隐藏空间", icon: LockKeyhole },
  { label: "重命名", icon: Pencil },
  { label: "移动", icon: FolderInput },
  { label: "复制", icon: Copy },
  { label: "文件详情", icon: Info },
];
function mobileSelectionAction(label: string) {
  if (label === "下载") void downloadSelected();
  else if (label === "分享") void shareSelected();
  else if (label === "重命名") startMobileRename();
  else if (label === "删除") void removeSelected();
  else {
    const actions: Record<string, ToolAction> = { "移动": "move", "复制": "copy", "收藏": "favorite", "移入隐藏空间": "hide", "智能整理": "organize", "文件详情": "details", "添加至": "menu" };
    const action = actions[label]; if (action) openTools(action);
  }
}
async function logout() {
  if (loggingOut.value) return;
  loggingOut.value = true;
  try {
    const revoked = await auth.logout();
    drive.reset();
    await router.replace({
      name: "login",
      query: revoked ? {} : { logoutWarning: "1" },
    });
  } finally {
    loggingOut.value = false;
  }
}
onMounted(async () => {
  window.addEventListener("resize", updateViewport);
  drive.reset();
  void drive.loadUsage();
  void refreshAvatar();
  searchPromptTimer = window.setInterval(() => {
    searchPromptIndex.value =
      (searchPromptIndex.value + 1) % searchPrompts.value.length;
  }, 3000);
  await loadFolder(null);
  try {
    await drive.loadTrash();
  } catch {
    /* 回收站入口会再次加载并显示错误 */
  }
});
onUnmounted(() => {
  window.removeEventListener("resize", updateViewport);
  if (searchPromptTimer) clearInterval(searchPromptTimer);
  homeAlive=false; avatarGeneration++;
  if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value);
});
</script>

<template>
  <UnavailableFeatureDialog
    :open="Boolean(unavailableMessage)"
    :message="unavailableMessage"
    @close="unavailableMessage = ''"
  />
  <TransferPage v-if="showTransfers" @back="showTransfers = false" />
  <AccountPage v-if="showAccount" @back="showAccount=false" @changed="refreshAvatar" @signed-out="accountSignedOut" />
  <FileTools v-if="toolsTarget" :items="toolsTarget.items" :initial-action="toolsTarget.action" @close="toolsTarget = null" @changed="toolsChanged" @rename="startMobileRename" @trash="removeSelected" />
  <FilePreview v-if="previewTarget" :file="previewTarget" @close="previewTarget = null" />
  <UploadPanel
    :open="uploadPanelOpen"
    :folder-options="uploadFolders"
    :initial-folder-id="currentFolder"
    @close="uploadPanelOpen = false"
    @uploaded="handleUploaded"
    @create-folder="
      uploadPanelOpen = false;
      createFolder();
    "
  />
  <ShareSettingsDialog v-if="shareTarget" :file="shareTarget" @close="shareTarget = null" @created="createdShare = $event; shareTarget = null; flash('分享链接已创建')" />
  <ShareLinkDialog :share="createdShare" @close="createdShare = null" />


  <div class="mobile-app">
    <MobileMyShares
      v-if="showMyShares"
      :shares="drive.state.shares"
      :loading="drive.state.loading"
      :error="mySharesError"
      @back="showMyShares = false"
      @refresh="openMyShares"
      @open="createdShare = $event"
      @copy="copyShareLinks"
      @cancel="cancelShareRecords"
    />
    <template v-else-if="mobileTab === 'home'">
      <div class="m-home-top">
        <header class="m-home-head">
          <div class="m-vip">
            <span class="m-vip-envelope">领</span
            ><span>会员免费领<small>新用户福利 ❯</small></span>
          </div>
          <div class="m-head-actions">
            <button aria-label="传输列表" @click="showTransfers = true">
              <Download />
            </button>
            <button aria-label="签到" @click="showUnavailable('签到功能暂未开放。')">
              <CalendarDays /></button
            ><button aria-label="存储空间" @click="mobileTab = 'files'">
              <HardDrive /></button
            ><button aria-label="上传文件" @click="chooseFiles">
              <Plus />
            </button>
          </div>
        </header>
        <button class="m-profile-search" @click="mobileTab = 'profile'">
          <span class="m-search-prompt-window"
            ><Transition name="m-prompt-slide"
              ><span :key="searchPromptIndex" class="m-search-prompt">{{
                searchPrompts[searchPromptIndex]
              }}</span></Transition
            ></span
          ><WandSparkles :size="23" />
        </button>
        <section class="m-tools" aria-label="文件分类">
          <button @click="openHomeCategory('image')">
            <span class="m-tool-icon"><HomeToolIcon name="photo" /></span
            ><span>相册</span></button
          ><button @click="router.push({ name: 'videos' })">
            <span class="m-tool-icon"><HomeToolIcon name="video" /></span
            ><span>视频</span></button
          ><button @click="showUnavailable('求职服务暂未开放。')">
            <span class="m-tool-icon"><HomeToolIcon name="career" /></span
            ><span>求职</span></button
          ><button @click="router.push({ name: 'novels' })">
            <span class="m-tool-icon"><HomeToolIcon name="novel" /></span
            ><span>小说</span></button
          ><button @click="openHomeCategory('doc')">
            <span class="m-tool-icon"><HomeToolIcon name="document" /></span
            ><span>文档</span></button
          ><button @click="chooseFiles">
            <span class="m-tool-icon"><HomeToolIcon name="scan" /></span
            ><span>扫描</span></button
          ><button @click="openHomeCategory('audio')">
            <span class="m-tool-icon"><HomeToolIcon name="audio" /></span
            ><span>听记</span>
          </button>
        </section>
        <div class="m-tools-indicator"><i></i><i></i></div>
      </div>
      <section class="m-panel">
        <div class="m-panel-title">
          <h2>最近</h2>
          <div class="m-panel-actions">
            <button
              type="button"
              :aria-label="recentVisible ? '隐藏最近文件' : '显示最近文件'"
              :aria-pressed="!recentVisible"
              @click="recentVisible = !recentVisible"
            >
              <Eye v-if="recentVisible" :size="20" /><EyeOff
                v-else
                :size="20"
              /></button
            ><button
              type="button"
              aria-label="查看最近文件"
              @click="openHomeCategory('recent')"
            >
              <ChevronRight :size="20" />
            </button>
          </div>
        </div>
        <template v-if="recentVisible"
          ><div
            v-for="item in recentItems"
            :key="item.id"
            class="m-recent"
            @click="openItem(item)"
          >
            <span class="m-item-icon"
              ><component :is="iconForFile(item)"
            /></span>
            <div>
              <b>{{ item.name }}</b
              ><small>{{ dateText(item.updatedAt) }} · 我的资源</small>
            </div>
          </div>
          <p v-if="!recentItems.length" class="m-home-empty">
            还没有文件，点击右上角 ＋ 上传第一份文件
          </p></template
        >
      </section>
      <section class="m-banner">
        <div class="m-panel-title">
          <h2>转存 <span>订阅</span></h2>
          <div class="m-panel-actions">
            <button
              type="button"
              aria-label="查看转存与订阅"
              @click="mobileTab = 'share'"
            >
              <Eye :size="20" />
            </button>
            <button
              type="button"
              aria-label="查看分享"
              @click="mobileTab = 'share'"
            >
              <ChevronRight :size="20" />
            </button>
          </div>
        </div>
        <div v-if="savedItems.length" class="m-saved-scroll">
          <button
            v-for="item in savedItems"
            :key="item.id"
            @click="openMyShares"
          >
            <span class="m-item-icon"
              ><component :is="iconForFile(item)" /></span
            ><span
              ><b>{{ item.name }}</b
              ><small>位置：我的资源 ❯</small></span
            >
          </button>
        </div>
        <p v-else class="m-home-empty">分享的文件会显示在这里</p>
      </section>
      <section class="m-memory">
        <div class="m-panel-title">
          <h2>推荐 <span>创意</span></h2>
          <button aria-label="更多推荐" @click="showUnavailable('更多推荐内容暂未接入。')">
            <MoreHorizontal :size="22" />
          </button>
        </div>
        <div class="m-discover">
          <button @click="openHomeCategory('image')">
            <span class="m-discover-art photo"><Image :size="38" /></span
            ><b>发现云端相册</b><small>随时找回珍贵瞬间</small></button
          ><button @click="router.push({ name: 'videos' })">
            <span class="m-discover-art film"><Video :size="38" /></span
            ><b>收藏精彩视频</b><small>你的回忆都在这里</small>
          </button>
        </div>
      </section>
    </template>
    <template v-else-if="mobileTab === 'files'">
      <header v-if="currentFolder && !checked.length" class="m-folder-head" @keydown.esc="folderMenuOpen = false">
        <button type="button" aria-label="返回上一级" :disabled="drive.state.loading || nameSaving" @click="goParent"><ChevronLeft :size="24" /></button>
        <label class="m-folder-search"><Search :size="21" /><input v-model="keyword" aria-label="搜索当前文件夹" placeholder="按文件名搜索" maxlength="100" /></label>
        <button type="button" class="m-transfer-button" aria-label="传输列表" @click="openTransfers"><ArrowDownUp :size="16" /></button>
        <button type="button" aria-label="文件夹更多操作" :aria-expanded="folderMenuOpen" aria-controls="folder-menu" @click="folderMenuOpen = !folderMenuOpen"><MoreHorizontal :size="25" /></button>
        <div v-if="folderMenuOpen" id="folder-menu" class="m-folder-menu" @keydown.esc="folderMenuOpen = false">
          <button type="button" class="m-new-folder" aria-label="新建文件夹" :disabled="drive.state.loading" @click="folderMenuOpen = false; createFolder()"><Folder :size="18" />新建文件夹</button>
          <button type="button" :disabled="!filteredFiles.length" @click="allVisibleSelected = true; folderMenuOpen = false"><CheckSquare :size="18" />选择文件</button>
        </div>
      </header>
      <header v-else class="m-file-head" @keydown.esc="folderMenuOpen = false">

        <template v-if="checked.length && mode !== 'trash'"
          ><button
            class="m-selection-close"
            aria-label="退出选择"
            @click="checked = []"
          >
            <X />
          </button>
          <h1>已选中 {{ checked.length }} 个文件</h1>
          <button
            class="m-selection-all"
            @click="allVisibleSelected = !allVisibleSelected"
          >
            {{ allVisibleSelected ? "取消全选" : "全选" }}
          </button></template
        ><template v-else
          ><h1>{{ title }}</h1>
          <div class="m-file-head-actions">
            <button type="button" class="m-transfer-button" aria-label="传输列表" @click="openTransfers"><ArrowDownUp :size="16" /></button>
            <button type="button" aria-label="文件更多操作" :aria-expanded="folderMenuOpen" aria-controls="folder-menu" @click="folderMenuOpen = !folderMenuOpen"><MoreHorizontal :size="24" /></button>
            <div v-if="folderMenuOpen" id="folder-menu" class="m-folder-menu">
              <button v-if="mode !== 'trash'" type="button" class="m-new-folder" aria-label="新建文件夹" :disabled="drive.state.loading" @click="folderMenuOpen = false; createFolder()"><Folder :size="18" />新建文件夹</button>
              <button type="button" :disabled="!filteredFiles.length" @click="allVisibleSelected = true; folderMenuOpen = false"><CheckSquare :size="18" />选择文件</button>
            </div>
          </div
        ></template>
      </header>
      <div v-if="!currentFolder" class="m-search">
        <Search :size="19" /><input
          v-model="keyword"
          :placeholder="serverSearchEnabled ? '按文件名搜索全网盘' : '筛选当前列表文件名'" aria-label="搜索文件名" maxlength="100"
        />
      </div>
      <div v-if="mode === 'trash'" class="m-trash-actions">
        <button
          :disabled="!filteredFiles.length || drive.state.loading"
          @click="allVisibleSelected = !allVisibleSelected"
        >
          {{ allVisibleSelected ? "取消全选" : "全选" }}</button
        ><button
          :disabled="!checked.length || drive.state.loading"
          @click="restoreSelected()"
        >
          <RotateCcw :size="16" />恢复</button
        ><button
          :disabled="!checked.length || drive.state.loading"
          @click="permanentDelete()"
        >
          <Trash2 :size="16" />删除</button
        ><button
          :disabled="!trashCount || drive.state.loading"
          @click="clearTrash"
        >
          清空
        </button>
      </div>
      <nav v-if="currentFolder" class="m-folder-breadcrumb" aria-label="文件夹路径">
        <button type="button" :disabled="drive.state.loading || nameSaving" @click="goRoot">我的网盘</button>
        <template v-for="crumb in folderCrumbs" :key="crumb.id"><span class="path-separator" aria-hidden="true">/</span><span v-if="crumb.id === currentFolder" aria-current="page">{{ crumb.name }}</span><button v-else type="button" :disabled="drive.state.loading || nameSaving" @click="navigateFolder(crumb.id)">{{ crumb.name }}</button></template>
      </nav>
      <div v-if="currentFolder" class="m-folder-toolbar">
        <label class="m-folder-sort-label">{{ sortBy === 'time' ? '智能排序' : sortBy === 'name' ? '名称排序' : '大小排序' }}<ChevronDown :size="16" /><select v-model="sortBy" aria-label="文件夹排序"><option value="time">智能排序（修改时间）</option><option value="name">名称排序</option><option value="size">大小排序</option></select></label>
        <button type="button" :aria-label="view === 'list' ? '切换网格视图' : '切换列表视图'" @click="view = view === 'list' ? 'grid' : 'list'"><LayoutGrid v-if="view === 'list'" :size="19" /><List v-else :size="19" /></button>
      </div>
      <div v-if="!currentFolder" class="m-filter">
        <button>智能排序 <SlidersHorizontal :size="15" /></button
        ><button :class="{ active: mode === 'all' }" @click="goRoot">全部</button><button :class="{ active: mode === 'favorites' }" @click="openHomeCategory('favorites')">我的收藏</button><button :class="{ active: mode === 'hidden' }" @click="openHomeCategory('hidden')">隐藏空间</button>
      </div>
      <p v-if="!currentFolder && drive.state.usage" class="m-capacity">已用 {{ formatBytes(drive.state.usage.usedBytes) }} / {{ formatBytes(drive.state.usage.limitBytes) }} · 可用 {{ formatBytes(drive.state.usage.availableBytes) }}</p>
      <p v-if="!currentFolder && drive.state.usageError" class="m-capacity" role="alert">{{ drive.state.usageError }} <button @click="drive.loadUsage">重试</button></p>
      <FileSearchPanel v-if="searchActive && mobileViewport" :query="keyword" :parent-id="currentFolder" :initial-type="searchType" :sort="sortBy" :busy="drive.state.loading || nameSaving" @open="openSearchHit" @locate="openSearchHit($event, true)" @clear="keyword = ''" />
      <div v-if="!searchActive" class="m-file-list" :class="{ 'm-folder-files': currentFolder, 'is-grid': currentFolder && view === 'grid' }">
        <div v-if="nameEdit?.kind === 'create' && nameEdit.parentId === currentFolder && mode === 'all'" class="m-file-row is-editing draft-folder-row"><span class="m-folder"><FolderGlyph v-if="currentFolder" /><Folder v-else /></span><div><InlineNameEditor v-if="mobileViewport" v-model="nameEdit.name" :saving="nameSaving" :error="nameError" creating @save="saveName" @cancel="cancelName" /></div></div>
        <div
          v-for="item in filteredFiles"
          :key="item.id"
          class="m-file-row"
          :class="{ selected: checked.includes(item.id), 'is-editing': nameEdit?.kind === 'rename' && nameEdit.item.id === item.id }"
          @click="
            checked.length && mode !== 'trash'
              ? (checked = checked.includes(item.id)
                  ? checked.filter((id) => id !== item.id)
                  : [...checked, item.id])
              : openItem(item)
          "
        >
          <span class="m-folder"><FolderGlyph v-if="currentFolder && item.kind === 'folder'" /><component v-else :is="iconForFile(item)" /></span>
          <div>
            <InlineNameEditor v-if="mobileViewport && nameEdit?.kind === 'rename' && nameEdit.item.id === item.id" v-model="nameEdit.name" :saving="nameSaving" :error="nameError" :select-stem="item.kind === 'file'" @save="saveName" @cancel="cancelName" /><b v-else>{{ item.name }}</b
            ><small
              >{{ item.kind === "folder" ? "" : formatSize(item.size) + "　"
              }}{{
                currentFolder ? folderDateText(item.updatedAt) : dateText(
                  mode === "trash" ? item.deletedAt! : item.updatedAt,
                ).slice(0, 16)
              }}</small
            >
          </div>
          <input
            v-model="checked"
            type="checkbox"
            :value="item.id"
            :aria-label="`选择 ${item.name}`"
            @click.stop
          />
        </div>
        <p v-if="!filteredFiles.length && !nameEdit" class="m-empty">这里还没有文件</p>
        <p v-if="currentFolder && filteredFiles.length && !checked.length" class="m-folder-security"><ShieldCheck :size="16" />cendoDrive 保障你的数据安全 <ChevronRight :size="16" /></p>
      </div>
      <section
        v-if="checked.length && mode !== 'trash'"
        class="m-selection-sheet"
        aria-label="已选文件操作"
      >
        <div class="m-selection-actions">
          <button
            v-for="action in selectionActions"
            :key="action.label"
            type="button"
            :disabled="
              drive.state.loading ||
              (action.label === '文件详情' && checked.length !== 1) ||
              (action.label === '下载' && downloading) ||
              (action.label === '重命名' && checked.length !== 1) ||
              (action.label === '分享' && checked.length !== 1)
            "
            @click="mobileSelectionAction(action.label)"
          >
            <component
              :is="action.icon"
              :size="25"
              :stroke-width="1.9"
            /><span>{{ action.label }}</span>
          </button>
        </div>
      </section>
      <button
        v-if="mode !== 'trash' && !checked.length"
        class="m-fab"
        aria-label="上传"
        @click="chooseFiles"
      >
        <Plus :size="30" />
      </button>
    </template>
    <template v-else-if="mobileTab === 'profile'">
      <main class="profile-page">
        <header class="profile-header">
          <button class="profile-avatar" type="button" aria-label="账号管理" @click="showAccount=true"><img v-if="avatarUrl" :src="avatarUrl" alt=""><UserRound v-else :size="30" /></button>
          <div class="profile-identity">
            <div>
              <strong>{{ displayName }}</strong
              ><span>SVIP 1</span><ScanLine :size="19" />
            </div>
            <p>您还不是超级会员 <ChevronRight :size="17" /></p>
          </div>
          <div class="profile-header-actions">
            <button aria-label="设备" type="button" @click="showUnavailable('设备管理功能暂未开放。')">
              <MonitorSmartphone /></button
            ><button aria-label="签到" type="button" @click="showUnavailable('签到功能暂未开放。')"><CalendarDays /></button>
          </div>
        </header>
        <section class="profile-membership">
          <div class="membership-hero">
            <div class="membership-copy">
              <small>云端生活 · 更多可能</small>
              <h1>解锁 SVIP</h1>
              <p>新用户专享 · 低至 $3.40/月！</p>
            </div>
            <div class="membership-perks">
              <div><PackageOpen /><span>8G 解压</span></div>
              <div><CloudUpload /><span>300G 上传</span></div>
              <div><Folder /><span>5 万转存</span></div>
              <div><HardDrive /><span>5T 空间</span></div>
              <div><Gift /><span>等级福利</span></div>
            </div>
            <button type="button" class="membership-cta" @click="router.push({ name: 'membership' })">
              立即解锁 <ChevronRight :size="19" />
            </button>
          </div>
          <div class="membership-links">
            <button type="button" @click="router.push({ name: 'ai-points' })">我的 AI 点数</button><i></i
            ><button type="button" @click="router.push({ name: 'my-assets' })">我的资产</button>
          </div>
        </section>
        <section class="profile-shortcuts" aria-label="常用工具">
          <button
            v-for="tool in profileShortcuts"
            :key="tool.label"
            type="button"
            @click="openProfileShortcut(tool.label)"
          >
            <component :is="tool.icon" /><em
              v-if="tool.label === '回收站' && trashCount"
              >{{ trashCount > 99 ? "99+" : trashCount }}</em
            ><span>{{ tool.label }}</span>
          </button>
        </section>
        <div class="profile-card-pair">
          <section class="profile-storage">
            <div><strong>1.6T / 2T</strong><span>79%</span></div>
            <div class="storage-track"><i></i></div>
            <button type="button" @click="showUnavailable('容量管理功能暂未开放。')">管理空间 <ChevronRight :size="17" /></button>
          </section>
          <section class="profile-missions">
            <div class="mission-orb"><Crown /></div>
            <strong>任务系统</strong
            ><button type="button" @click="showUnavailable('任务奖励功能暂未开放。')">领 奖 励 <ChevronRight :size="17" /></button>
          </section>
        </div>
        <section class="profile-services" aria-label="更多服务">
          <button
            v-for="service in profileServices"
            :key="service.label"
            type="button"
            :class="service.tone"
            @click="openProfileService(service.label)"
          >
            <component :is="service.icon" /><span>{{ service.label }}</span>
          </button>
        </section>
        <section class="profile-promo">
          <div class="promo-gift"><Gift :size="52" /></div>
          <div>
            <strong>网盘 <em>SVIP</em> 会员活动</strong>
            <p>活动领取能力暂未开放</p>
          </div>
          <button type="button" @click="router.push({ name: 'membership' })">查看方案</button>
        </section>
        <section class="profile-game">
          <div>
            <h2>游戏中心</h2>
            <button type="button" @click="router.push({ name: 'game-center' })">
              免费下载券 <ChevronRight :size="18" />
            </button>
          </div>
          <p>探索更多云端乐趣</p>
        </section>
        <button class="profile-logout" type="button" @click="showAccount=true"><Settings :size="19" /><span>账号管理</span><ChevronRight :size="18" /></button>
        <button
          class="profile-logout"
          type="button"
          :disabled="loggingOut"
          @click="logout"
        >
          <LogOut :size="19" /><span>{{
            loggingOut ? "正在退出…" : "退出登录"
          }}</span
          ><ChevronRight :size="18" />
        </button>
      </main>
    </template>
    <MobileShareHub v-else />
    <nav
      v-if="
        !showMyShares &&
        !(mobileTab === 'files' && checked.length && mode !== 'trash')
      "
      class="m-bottom-nav"
    >
      <button
        :class="{ active: mobileTab === 'home' }"
        @click="mobileTab = 'home'"
      >
        <House /><span>首页</span></button
      ><button
        :class="{ active: mobileTab === 'files' }"
        @click="
          mobileTab = 'files';
          changeMode('all');
        "
      >
        <Folder /><span>文件</span></button
      ><button class="genflow" type="button" @click="showUnavailable('库库 AI 功能暂未开放。')">
        <i><Sparkles /></i><span>库库 AI</span></button
      ><button
        :class="{ active: mobileTab === 'share' }"
        @click="mobileTab = 'share'"
      >
        <Send /><em>99</em><span>共享</span></button
      ><button
        :class="{ active: mobileTab === 'profile' }"
        @click="mobileTab = 'profile'"
      >
        <UserRound /><span>我的</span>
      </button>
    </nav>
  </div>

  <div class="drive-shell desktop-drive">
    <nav class="desktop-rail">
      <BrandLogo /><button class="active"><Cloud /><span>首页</span></button
      ><button @click="showTransfers = true"><Upload /><span>传输</span></button
      ><button><UserRound /><span>好友</span></button><i></i
      ><button><Share2 /><span>同步空间</span></button
      ><button><HardDrive /><span>APP下载</span></button
      ><button><Image /><span>一刻相册</span></button
      ><button><LayoutGrid /><span>工具</span></button>
    </nav>
    <aside class="sidebar" :class="{ open: mobileNavOpen }">
      <BrandLogo /><button class="upload" @click="chooseFiles">
        <Plus :size="19" />上传文件
      </button>
      <nav class="side-nav">
        <a :class="{ active: mode === 'all' }" @click="changeMode('all')"
          ><HardDrive />全部文件</a
        ><a :class="{ active: mode === 'recent' }" @click="changeMode('recent')"
          ><Clock3 />最近</a
        ><a :class="{ active: mode === 'image' }" @click="changeMode('image')"
          ><Image />图片</a
        ><a :class="{ active: mode === 'video' }" @click="changeMode('video')"
          ><Video />视频</a
        ><a :class="{ active: mode === 'doc' }" @click="changeMode('doc')"
          ><FileText />文档</a
        ><a :class="{ active: mode === 'audio' }" @click="changeMode('audio')"
          ><Music2 />音频</a
        ><a :class="{ active: mode === 'other' }" @click="changeMode('other')"
          ><Archive />其他</a
        ><i></i
        ><a :class="{ active: mode === 'favorites' }" @click="changeMode('favorites')"><Star />我的收藏</a>
        <a :class="{ active: mode === 'hidden' }" @click="changeMode('hidden')"><LockKeyhole />隐藏空间</a>
        <a :class="{ active: mode === 'shares' }" @click="changeMode('shares')"
          ><Share2 />我的分享</a
        ><a :class="{ active: mode === 'trash' }" @click="changeMode('trash')"
          ><Trash2 />回收站<em v-if="trashCount" class="nav-count">{{
            trashCount > 99 ? "99+" : trashCount
          }}</em></a
        >
      </nav>
      <div class="storage">
        <template v-if="drive.state.usage"><div><span>已用 {{ formatBytes(drive.state.usage.usedBytes) }}</span><b>{{ formatBytes(drive.state.usage.limitBytes) }}</b></div><progress aria-label="存储空间（含上传预留）" :value="drive.state.usage.usedBytes + drive.state.usage.reservedBytes" :max="drive.state.usage.limitBytes || 1"></progress><small>回收站 {{ formatBytes(drive.state.usage.trashBytes) }} · 上传预留 {{ formatBytes(drive.state.usage.reservedBytes) }}</small></template>
        <p v-else>容量加载中…</p><p v-if="drive.state.usageError" role="alert">{{ drive.state.usageError }}</p><button @click="drive.loadUsage">刷新容量</button>
      </div>
    </aside>
    <button
      v-if="mobileNavOpen"
      class="nav-backdrop"
      aria-label="关闭导航"
      @click="mobileNavOpen = false"
    ></button>
    <main class="workspace">
      <header class="topbar">
        <button
          class="mobile-menu"
          aria-label="打开导航"
          @click="mobileNavOpen = true"
        >
          <Menu :size="21" />
        </button>
        <div class="search">
          <Search :size="18" /><input
            v-model="keyword"
            :placeholder="serverSearchEnabled ? '按文件名搜索' : '筛选当前列表文件名'" aria-label="搜索文件名" maxlength="100"
          /><kbd>⌘ K</kbd>
        </div>
        <div class="top-actions">
          <button><Bell :size="19" /><i></i></button
          ><button aria-label="账号管理" @click="showAccount=true"><Settings :size="19" /></button><span></span
          ><button class="user" @click="menuOpen = !menuOpen">
            <b><img v-if="avatarUrl" :src="avatarUrl" alt=""><template v-else>{{ displayName.slice(0, 1).toUpperCase() }}</template></b
            ><em>{{ displayName }}</em
            ><ChevronDown :size="15" />
          </button>
        </div>
        <div v-if="menuOpen" class="user-menu">
          <strong>{{ displayName }}</strong
          ><small>普通用户</small><button @click="showAccount=true;menuOpen=false">账号管理</button
          ><button :disabled="loggingOut" @click="logout">
            {{ loggingOut ? "正在退出…" : "退出登录" }}
          </button>
        </div>
      </header>
      <section class="content">
        <div class="content-title">
          <div>
            <h1>{{ title }}</h1>
            <p>{{ searchActive ? "按文件名搜索" : `共 ${filteredFiles.length} 个项目` }}</p>
          </div>
          <select v-model="sortBy" class="sort-select">
            <option value="time">按时间排序</option>
            <option value="name">按名称排序</option>
            <option value="size">按大小排序</option>
          </select>
        </div>
        <nav v-if="currentFolder" class="breadcrumb" aria-label="文件夹路径">
          <button type="button" aria-label="返回上一级" :disabled="drive.state.loading || nameSaving" @click="goParent"><ChevronLeft :size="16" />返回上一级</button>
          <button type="button" :disabled="drive.state.loading || nameSaving" @click="goRoot">全部文件</button>
          <template v-for="crumb in folderCrumbs" :key="crumb.id"><ChevronRight :size="14" /><span v-if="crumb.id === currentFolder" aria-current="page">{{ crumb.name }}</span><button v-else type="button" :disabled="drive.state.loading || nameSaving" @click="navigateFolder(crumb.id)">{{ crumb.name }}</button></template>
        </nav>
        <ShareList
          v-if="mode === 'shares'"
          :shares="drive.state.shares"
          :loading="drive.state.loading"
          @copy="copyShareLink"
          @cancel="cancelShareRecord"
        />
        <template v-else>
          <FileSearchPanel v-if="searchActive && !mobileViewport" :query="keyword" :parent-id="currentFolder" :initial-type="searchType" :sort="sortBy" :busy="drive.state.loading || nameSaving" @open="openSearchHit" @locate="openSearchHit($event, true)" @clear="keyword = ''" />
          <template v-if="!searchActive">
          <div class="toolbar">
            <div v-if="mode !== 'trash'" class="tool-left">
              <button @click="chooseFiles"><Upload :size="17" />上传</button
              ><button @click="createFolder">
                <Folder :size="17" />新建文件夹</button
              ><span></span
              ><button :disabled="!checked.length" @click="downloadSelected">
                <Download :size="17" />下载</button
              ><button
                :disabled="!checked.length || checked.length !== 1"
                @click="shareSelected"
              >
                <Share2 :size="17" />分享</button
              ><button :disabled="!checked.length" @click="removeSelected()">
                <Trash2 :size="17" />删除
              </button><button :disabled="!checked.length || drive.state.loading" @click="openTools('menu')"><MoreHorizontal :size="17" />更多操作</button>
            </div>
            <div v-else class="tool-left">
              <button
                :disabled="!checked.length || drive.state.loading"
                @click="restoreSelected()"
              >
                <RotateCcw :size="17" />恢复</button
              ><button
                :disabled="!checked.length || drive.state.loading"
                @click="permanentDelete()"
              >
                <Trash2 :size="17" />永久删除</button
              ><button
                :disabled="!trashCount || drive.state.loading"
                @click="clearTrash"
              >
                清空回收站
              </button>
            </div>
            <div class="view-switch">
              <button
                :class="{ active: view === 'list' }"
                @click="view = 'list'"
              >
                <List :size="18" /></button
              ><button
                :class="{ active: view === 'grid' }"
                @click="view = 'grid'"
              >
                <LayoutGrid :size="18" />
              </button>
            </div>
          </div>
          <div v-if="view === 'list'" class="file-table">
            <div class="table-head">
              <label
                ><input v-model="allVisibleSelected" type="checkbox" /><span
                  ><CheckSquare :size="13" /></span
              ></label>
              <div>文件名</div>
              <div>大小</div>
              <div>{{ mode === "trash" ? "删除时间" : "修改日期" }}</div>
              <div></div>
            </div>
            <div v-if="nameEdit?.kind === 'create' && nameEdit.parentId === currentFolder && mode === 'all'" class="file-row is-editing draft-folder-row"><div></div><div class="file-name"><Folder class="folder" :size="31" /><InlineNameEditor v-if="!mobileViewport" v-model="nameEdit.name" :saving="nameSaving" :error="nameError" creating @save="saveName" @cancel="cancelName" /></div><div>—</div><div>尚未创建</div><div></div></div>
            <div v-for="item in filteredFiles" :key="item.id" class="file-row" tabindex="0" :class="{ 'is-editing': nameEdit?.kind === 'rename' && nameEdit.item.id === item.id }" @keydown.f2.stop.prevent="renameItem(item)" @dblclick="openItem(item)"
            >
              <label
                ><input
                  v-model="checked"
                  type="checkbox"
                  :value="item.id" /><span><CheckSquare :size="13" /></span
              ></label>
              <div class="file-name" @click="openItem(item)">
                <component
                  :is="iconForFile(item)"
                  :class="item.kind"
                  :size="31"
                /><InlineNameEditor v-if="!mobileViewport && nameEdit?.kind === 'rename' && nameEdit.item.id === item.id" v-model="nameEdit.name" :saving="nameSaving" :error="nameError" :select-stem="item.kind === 'file'" @save="saveName" @cancel="cancelName" /><b v-else>{{ item.name }}</b>
              </div>
              <div>{{ formatSize(item.size) }}</div>
              <div>
                {{
                  dateText(mode === "trash" ? item.deletedAt! : item.updatedAt)
                }}
              </div>
              <button v-if="mode !== 'trash'" aria-label="文件操作" @click.stop="itemMenu(item)">
                <MoreHorizontal :size="18" />
              </button>
              <div v-else class="trash-row-actions">
                <button
                  :disabled="drive.state.loading"
                  title="恢复"
                  @click.stop="restoreSelected([item.id])"
                >
                  <RotateCcw :size="16" /></button
                ><button
                  :disabled="drive.state.loading"
                  title="永久删除"
                  @click.stop="permanentDelete([item.id])"
                >
                  <Trash2 :size="16" />
                </button>
              </div>
            </div>
            <div v-if="!filteredFiles.length && !nameEdit" class="empty">这里还没有文件</div>
          </div>
          <div v-else class="file-grid">
            <article v-if="nameEdit?.kind === 'create' && nameEdit.parentId === currentFolder && mode === 'all'" class="is-editing draft-folder-row"><Folder class="folder" :size="58" /><InlineNameEditor v-if="!mobileViewport" v-model="nameEdit.name" :saving="nameSaving" :error="nameError" creating @save="saveName" @cancel="cancelName" /></article>
            <article
              v-for="item in filteredFiles"
              :key="item.id" tabindex="0" :class="{ 'is-editing': nameEdit?.kind === 'rename' && nameEdit.item.id === item.id }" @keydown.f2.stop.prevent="renameItem(item)"
              @dblclick="openItem(item)"
            >
              <button v-if="mode !== 'trash'" aria-label="文件操作" @click.stop="itemMenu(item)">
                <MoreHorizontal :size="17" />
              </button>
              <div v-else class="trash-grid-actions">
                <button
                  :disabled="drive.state.loading"
                  title="恢复"
                  @click.stop="restoreSelected([item.id])"
                >
                  <RotateCcw :size="15" /></button
                ><button
                  :disabled="drive.state.loading"
                  title="永久删除"
                  @click.stop="permanentDelete([item.id])"
                >
                  <Trash2 :size="15" />
                </button>
              </div>
              <component
                :is="iconForFile(item)"
                :class="item.kind"
                :size="58"
                @click="openItem(item)"
              /><InlineNameEditor v-if="!mobileViewport && nameEdit?.kind === 'rename' && nameEdit.item.id === item.id" v-model="nameEdit.name" :saving="nameSaving" :error="nameError" :select-stem="item.kind === 'file'" @save="saveName" @cancel="cancelName" /><b v-else>{{ item.name }}</b
              ><small>{{
                dateText(
                  mode === "trash" ? item.deletedAt! : item.updatedAt,
                ).slice(0, 10)
              }}</small
              ><input v-model="checked" type="checkbox" :value="item.id" />
            </article>
          </div>
          </template>
        </template>
      </section>
    </main>
    <div v-if="notice" class="toast">{{ notice }}</div>
  </div>
</template>
