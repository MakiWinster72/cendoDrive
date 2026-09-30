<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Archive, Bell, CalendarDays, CheckSquare, Clock3, ClipboardCheck, CloudUpload, Coins, Crown, Gift, MonitorSmartphone, LogOut, PackageOpen, ScanLine, TicketPercent, Wallet, ChevronDown, ChevronRight, CircleUserRound, Cloud, Download, Eye, File, FileText, Folder, HardDrive, House, Image, LayoutGrid, List, Menu, MessageCircle, MoreHorizontal, Music2, Plus, Printer, RotateCcw, Search, Settings, Share2, SlidersHorizontal, Sparkles, Trash2, Upload, UserRound, Video, WandSparkles } from 'lucide-vue-next'
import BrandLogo from '../components/BrandLogo.vue'
import HomeToolIcon from '../components/HomeToolIcon.vue'
import UploadPanel from '../components/UploadPanel.vue'
import '../styles/profile.css'
import '../styles/home.css'
import { useAuth } from '../stores/auth'
import { formatSize, useDrive, type DriveItem } from '../stores/drive'

type Mode = 'all' | 'recent' | 'image' | 'video' | 'doc' | 'audio' | 'other' | 'shares' | 'trash'
const router = useRouter(), auth = useAuth(), drive = useDrive()
const view = ref<'list' | 'grid'>('list'), mode = ref<Mode>('all'), currentFolder = ref<string | null>(null)
const keyword = ref(''), checked = ref<string[]>([]), menuOpen = ref(false), mobileNavOpen = ref(false), uploadPanelOpen = ref(false)
const sortBy = ref<'name' | 'time' | 'size'>('time'), notice = ref(''), loggingOut = ref(false)
const mobileTab = ref<'home' | 'files' | 'share' | 'profile'>('home')
const recentItems = computed(() => [...drive.state.files].filter(item => !item.deletedAt && item.kind !== 'folder').sort((a, b) => +new Date(b.updatedAt) - +new Date(a.updatedAt)).slice(0, 3))
const savedItems = computed(() => drive.state.files.filter(item => !item.deletedAt && drive.state.shares.some(share => share.itemId === item.id && !share.cancelled)).slice(0, 3))
function openHomeCategory(next: Mode) { mobileTab.value = 'files'; void changeMode(next) }
const profileShortcuts = [
  { label: '我的收藏', icon: Sparkles }, { label: '我的分享', icon: Share2 },
  { label: '回收站', icon: Trash2 }, { label: '设备管理', icon: MonitorSmartphone },
  { label: '我的打印', icon: Printer }, { label: '转存与下载', icon: CloudUpload },
]
const profileServices = [
  { label: '借钱', icon: Wallet, tone: 'rose' }, { label: '免费领会员', icon: Gift, tone: 'mint' },
  { label: '照片冲印', icon: Printer, tone: 'orange' }, { label: '奇妙赏', icon: Coins, tone: 'rose' },
  { label: '一刻相册', icon: Image, tone: 'gold' }, { label: '活动中心', icon: TicketPercent, tone: 'orange' },
]
const displayName = computed(() => auth.user.value?.nickname || auth.user.value?.username || 'CendoDrive 用户')
const modeNames: Record<Mode, string> = { all: '全部文件', recent: '最近', image: '图片', video: '视频', doc: '文档', audio: '音频', other: '其他', shares: '我的分享', trash: '回收站' }
const title = computed(() => currentFolder.value ? drive.get(currentFolder.value)?.name || '文件夹' : modeNames[mode.value])
const folders = computed(() => drive.state.files.filter((item) => item.kind === 'folder' && !item.deletedAt))
const uploadFolders = computed(() => [{ id: null, name: displayName.value }, ...folders.value.map((folder) => ({ id: folder.id, name: folder.name }))])
const trashCount = computed(() => drive.state.files.filter((item) => Boolean(item.deletedAt)).length)
const filteredFiles = computed(() => {
  let items: DriveItem[]
  if (mode.value === 'trash') items = drive.state.files.filter((item) => Boolean(item.deletedAt))
  else if (mode.value === 'shares') {
    const ids = new Set(drive.state.shares.filter((share) => !share.cancelled && new Date(share.expiresAt) > new Date()).map((share) => share.itemId))
    items = drive.state.files.filter((item) => ids.has(item.id) && !item.deletedAt)
  } else if (mode.value === 'all') items = drive.state.files.filter((item) => !item.deletedAt && item.parentId === currentFolder.value)
  else {
    const kinds: Partial<Record<Mode, string[]>> = { image: ['image'], video: ['video'], doc: ['doc', 'pdf'], audio: ['audio'], other: ['other'] }
    items = drive.state.files.filter((item) => !item.deletedAt && item.kind !== 'folder' && (mode.value === 'recent' || kinds[mode.value]?.includes(item.kind)))
  }
  items = items.filter((item) => item.name.toLowerCase().includes(keyword.value.toLowerCase()))
  return [...items].sort((a, b) => sortBy.value === 'name' ? a.name.localeCompare(b.name, 'zh-CN') : sortBy.value === 'size' ? b.size - a.size : +new Date(mode.value === 'trash' ? b.deletedAt! : b.updatedAt) - +new Date(mode.value === 'trash' ? a.deletedAt! : a.updatedAt))
})
const allVisibleSelected = computed({
  get: () => filteredFiles.value.length > 0 && filteredFiles.value.every((item) => checked.value.includes(item.id)),
  set: (selected: boolean) => { checked.value = selected ? filteredFiles.value.map((item) => item.id) : [] },
})
const iconFor = (kind: string) => kind === 'folder' ? Folder : kind === 'image' ? Image : kind === 'video' ? Video : kind === 'audio' ? Music2 : ['pdf', 'doc'].includes(kind) ? FileText : File
const dateText = (date: string) => new Date(date).toLocaleString('zh-CN', { hour12: false }).replaceAll('/', '-')
function flash(text: string) { notice.value = text; window.setTimeout(() => notice.value = '', 2200) }
async function changeMode(next: Mode) {
  mode.value = next; currentFolder.value = null; checked.value = []; mobileNavOpen.value = false
  try {
    if (next === 'all') await drive.load(null)
    else if (next === 'trash') await drive.loadTrash()
  } catch { alert(drive.state.error) }
}
async function loadFolder(parentId: string | null) { try { await drive.load(parentId); currentFolder.value = parentId } catch { alert(drive.state.error) } }
async function openItem(item: DriveItem) { if (item.kind === 'folder' && mode.value !== 'trash') { mode.value = 'all'; checked.value = []; await loadFolder(item.id) } }
async function goRoot() { checked.value = []; await loadFolder(null) }
async function createFolder() { const name = prompt('请输入文件夹名称'); if (!name) return; try { await drive.createFolder(name, currentFolder.value); flash('文件夹创建成功') } catch { alert(drive.state.error) } }
function chooseFiles() { uploadPanelOpen.value = true }
function handleUploaded(item: DriveItem) { drive.addUploaded(item); flash(`已上传：${item.name}`) }
async function renameItem(item: DriveItem) { const name = prompt('请输入新名称', item.name); if (!name) return; try { await drive.rename(item.id, name); flash('重命名成功') } catch { alert(drive.state.error) } }
async function moveItem(item: DriveItem) { const hint = ['根目录', ...folders.value.filter((folder) => folder.id !== item.id).map((folder) => folder.name)].join('、'); const name = prompt(`移动到哪个文件夹？\n可选：${hint}`, '根目录'); if (!name) return; const target = name === '根目录' ? null : folders.value.find((folder) => folder.name === name)?.id; if (name !== '根目录' && !target) return alert('未找到目标文件夹'); try { await drive.move(item.id, target || null); checked.value = []; flash('移动成功') } catch { alert(drive.state.error) } }
function itemMenu(item: DriveItem) { if (mode.value === 'shares') { if (confirm('确定取消该分享吗？')) { const share = drive.state.shares.find((record) => record.itemId === item.id && !record.cancelled); if (share) drive.cancelShare(share.id); flash('分享已取消') } return } const action = prompt('输入操作：重命名 / 移动 / 删除', '重命名'); if (action === '重命名') renameItem(item); else if (action === '移动') moveItem(item); else if (action === '删除') removeSelected([item.id]) }
async function removeSelected(ids = checked.value) { if (!ids.length || !confirm('确定移入回收站吗？')) return; try { await drive.trash(ids); checked.value = []; flash('已移入回收站') } catch { alert(drive.state.error) } }
async function restoreSelected(ids = checked.value) { if (!ids.length) return; try { await drive.restore(ids); checked.value = checked.value.filter((id) => !ids.includes(id)); flash(ids.length > 1 ? `已恢复 ${ids.length} 个项目` : '文件已恢复') } catch { alert(drive.state.error) } }
async function permanentDelete(ids = checked.value) { if (!ids.length || !confirm(`永久删除 ${ids.length} 个项目后无法恢复，是否继续？`)) return; try { await drive.removeForever(ids); checked.value = checked.value.filter((id) => !ids.includes(id)); flash(ids.length > 1 ? `已永久删除 ${ids.length} 个项目` : '已永久删除') } catch { alert(drive.state.error) } }
async function clearTrash() { if (!trashCount.value || !confirm(`将永久删除回收站中的 ${trashCount.value} 个项目，是否继续？`)) return; try { await drive.emptyTrash(); checked.value = []; flash('回收站已清空') } catch { alert(drive.state.error) } }
async function shareSelected() { if (!checked.value.length) return; const days = Number(prompt('分享有效天数', '7') || 7); try { const record = drive.share(checked.value[0], days); const link = `${location.origin}/share/${record.id}`; await navigator.clipboard?.writeText(`${link} 提取码：${record.code}`).catch(() => {}); alert(`分享链接：${link}\n提取码：${record.code}\n有效期：${days} 天\n已尝试复制到剪贴板`) } catch (error) { alert(error instanceof Error ? error.message : '分享失败') } }
async function openProfileShortcut(label: string) { if (label === '回收站') { mobileTab.value = 'files'; await changeMode('trash') } else if (label === '我的分享') { mobileTab.value = 'share' } }
async function downloadSelected() { try { for (const itemId of checked.value) { const item = drive.get(itemId); if (item?.kind !== 'folder') await drive.download(itemId) } flash('已开始下载') } catch { alert(drive.state.error) } }
async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    const revoked = await auth.logout()
    await router.replace({ name: 'login', query: revoked ? {} : { logoutWarning: '1' } })
  } finally { loggingOut.value = false }
}
onMounted(async () => { await loadFolder(null); try { await drive.loadTrash() } catch { /* 回收站入口会再次加载并显示错误 */ } })
</script>

<template>
  <UploadPanel :open="uploadPanelOpen" :folder-options="uploadFolders" :initial-folder-id="currentFolder" @close="uploadPanelOpen = false" @uploaded="handleUploaded" />

  <div class="mobile-app">
    <template v-if="mobileTab === 'home'">
      <div class="m-home-top">
        <header class="m-home-head"><div class="m-vip"><span class="m-vip-envelope">领</span><span>会员免费领<small>新用户福利 ❯</small></span></div><div class="m-head-actions"><button aria-label="签到" @click="flash('签到功能即将上线')"><CalendarDays /></button><button aria-label="存储空间" @click="mobileTab='files'"><HardDrive /></button><button aria-label="上传文件" @click="chooseFiles"><Plus /></button></div></header>
        <button class="m-profile-search" @click="mobileTab='profile'"><span>{{ displayName }}的云端空间</span><WandSparkles :size="23" /></button>
        <section class="m-tools" aria-label="文件分类"><button @click="openHomeCategory('image')"><span class="m-tool-icon"><HomeToolIcon name="photo" /></span><span>相册</span></button><button @click="openHomeCategory('video')"><span class="m-tool-icon"><HomeToolIcon name="video" /></span><span>视频</span></button><button @click="flash('更多功能即将上线')"><span class="m-tool-icon"><HomeToolIcon name="career" /></span><span>求职</span></button><button @click="openHomeCategory('doc')"><span class="m-tool-icon"><HomeToolIcon name="document" /></span><span>文档</span></button><button @click="chooseFiles"><span class="m-tool-icon"><HomeToolIcon name="scan" /></span><span>扫描</span></button><button @click="openHomeCategory('audio')"><span class="m-tool-icon"><HomeToolIcon name="audio" /></span><span>听记</span></button></section>
        <div class="m-tools-indicator"><i></i><i></i></div>
      </div>
      <section class="m-panel"><div class="m-panel-title"><h2>最近</h2><button aria-label="查看最近文件" @click="openHomeCategory('recent')"><Eye :size="20" /><ChevronRight :size="20" /></button></div><div v-for="item in recentItems" :key="item.id" class="m-recent" @click="openHomeCategory('recent')"><span class="m-item-icon"><component :is="iconFor(item.kind)" /></span><div><b>{{ item.name }}</b><small>{{ dateText(item.updatedAt) }} · 我的资源</small></div></div><p v-if="!recentItems.length" class="m-home-empty">还没有文件，点击右上角 ＋ 上传第一份文件</p></section>
      <section class="m-banner"><div class="m-panel-title"><h2>转存 <span>订阅</span></h2><button aria-label="查看分享" @click="mobileTab='share'"><Eye :size="20" /><ChevronRight :size="20" /></button></div><div v-if="savedItems.length" class="m-saved-scroll"><button v-for="item in savedItems" :key="item.id" @click="openHomeCategory('shares')"><span class="m-item-icon"><component :is="iconFor(item.kind)" /></span><span><b>{{ item.name }}</b><small>位置：我的资源 ❯</small></span></button></div><p v-else class="m-home-empty">分享的文件会显示在这里</p></section>
      <section class="m-memory"><div class="m-panel-title"><h2>推荐 <span>创意</span></h2><button aria-label="更多推荐" @click="flash('更多内容即将上线')"><MoreHorizontal :size="22" /></button></div><div class="m-discover"><button @click="openHomeCategory('image')"><span class="m-discover-art photo"><Image :size="38" /></span><b>发现云端相册</b><small>随时找回珍贵瞬间</small></button><button @click="openHomeCategory('video')"><span class="m-discover-art film"><Video :size="38" /></span><b>收藏精彩视频</b><small>你的回忆都在这里</small></button></div></section>
    </template>
    <template v-else-if="mobileTab === 'files'">
      <header class="m-file-head"><h1>文件</h1><div><HardDrive :size="23" /><MoreHorizontal :size="24" /></div></header>
      <div class="m-search"><Search :size="19" /><input v-model="keyword" placeholder="搜索网盘文件" /></div>
      <div v-if="mode === 'trash'" class="m-trash-actions"><button :disabled="!filteredFiles.length || drive.state.loading" @click="allVisibleSelected = !allVisibleSelected">{{ allVisibleSelected ? '取消全选' : '全选' }}</button><button :disabled="!checked.length || drive.state.loading" @click="restoreSelected()"><RotateCcw :size="16" />恢复</button><button :disabled="!checked.length || drive.state.loading" @click="permanentDelete()"><Trash2 :size="16" />删除</button><button :disabled="!trashCount || drive.state.loading" @click="clearTrash">清空</button></div>
      <div class="m-filter"><button>智能排序 <SlidersHorizontal :size="15" /></button><button class="active">全部</button><button>我的资源</button><button>我创建的</button><button>我加工的</button></div>
      <div class="m-file-list"><div v-for="item in filteredFiles" :key="item.id" class="m-file-row" @click="openItem(item)"><span class="m-folder"><component :is="iconFor(item.kind)" fill="currentColor" /></span><div><b>{{ item.name }}</b><small>{{ item.kind === 'folder' ? '' : formatSize(item.size) + '　' }}{{ dateText(mode === 'trash' ? item.deletedAt! : item.updatedAt).slice(0,16) }}</small></div><input v-model="checked" type="checkbox" :value="item.id" @click.stop /></div><p v-if="!filteredFiles.length" class="m-empty">这里还没有文件</p></div>
      <button v-if="mode !== 'trash'" class="m-fab" aria-label="上传" @click="chooseFiles"><Plus :size="30" /></button>
    </template>
    <template v-else-if="mobileTab === 'profile'">
      <main class="profile-page">
        <header class="profile-header">
          <div class="profile-avatar"><UserRound :size="30" /></div>
          <div class="profile-identity"><div><strong>{{ displayName }}</strong><span>SVIP 1</span><ScanLine :size="19" /></div><p>您还不是超级会员 <ChevronRight :size="17" /></p></div>
          <div class="profile-header-actions"><button aria-label="设备" type="button"><MonitorSmartphone /></button><button aria-label="签到" type="button"><CalendarDays /></button></div>
        </header>
        <section class="profile-membership">
          <div class="membership-hero"><div class="membership-copy"><small>云端生活 · 更多可能</small><h1>解锁 SVIP</h1><p>新用户专享 · 低至 $3.40/月！</p></div>
            <div class="membership-perks"><div><PackageOpen /><span>8G 解压</span></div><div><CloudUpload /><span>300G 上传</span></div><div><Folder /><span>5 万转存</span></div><div><HardDrive /><span>5T 空间</span></div><div><Gift /><span>等级福利</span></div></div>
            <button type="button" class="membership-cta">立即解锁 <ChevronRight :size="19" /></button>
          </div>
          <div class="membership-links"><button type="button">我的 AI 点数</button><i></i><button type="button">我的资产</button></div>
        </section>
        <section class="profile-shortcuts" aria-label="常用工具"><button v-for="tool in profileShortcuts" :key="tool.label" type="button" @click="openProfileShortcut(tool.label)"><component :is="tool.icon" /><em v-if="tool.label === '回收站' && trashCount">{{ trashCount > 99 ? '99+' : trashCount }}</em><span>{{ tool.label }}</span></button></section>
        <div class="profile-card-pair"><section class="profile-storage"><div><strong>1.6T / 2T</strong><span>79%</span></div><div class="storage-track"><i></i></div><button type="button">管理空间 <ChevronRight :size="17" /></button></section><section class="profile-missions"><div class="mission-orb"><Crown /></div><strong>任务系统</strong><button type="button">领 奖 励 <ChevronRight :size="17" /></button></section></div>
        <section class="profile-services" aria-label="更多服务"><button v-for="service in profileServices" :key="service.label" type="button" :class="service.tone"><component :is="service.icon" /><span>{{ service.label }}</span></button></section>
        <section class="profile-promo"><div class="promo-gift"><Gift :size="52" /></div><div><strong>网盘 <em>SVIP</em> 会员免费送</strong><p>限时活动 · 领 90 天会员</p></div><button type="button">立即抢</button></section>
        <section class="profile-game"><div><h2>游戏中心</h2><button type="button">免费下载券 <ChevronRight :size="18" /></button></div><p>探索更多云端乐趣</p></section>
        <button class="profile-logout" type="button" :disabled="loggingOut" @click="logout"><LogOut :size="19" /><span>{{ loggingOut ? '正在退出…' : '退出登录' }}</span><ChevronRight :size="18" /></button>
      </main>
    </template>
    <template v-else><section class="m-placeholder"><Share2 :size="58" /><h2>我的分享</h2><p>已创建的分享可在 PC 端管理</p></section></template>
    <nav class="m-bottom-nav"><button :class="{active:mobileTab==='home'}" @click="mobileTab='home'"><House /><span>首页</span></button><button :class="{active:mobileTab==='files'}" @click="mobileTab='files';changeMode('all')"><Folder /><span>文件</span></button><button class="genflow" type="button" @click="flash('库库 AI 即将上线')"><i><Sparkles /></i><span>库库 AI</span></button><button :class="{active:mobileTab==='share'}" @click="mobileTab='share'"><Share2 /><em>99</em><span>共享</span></button><button :class="{active:mobileTab==='profile'}" @click="mobileTab='profile'"><UserRound /><span>我的</span></button></nav>
  </div>

  <div class="drive-shell desktop-drive">
    <nav class="desktop-rail"><BrandLogo /><button class="active"><Cloud /><span>首页</span></button><button><Upload /><span>传输</span></button><button><UserRound /><span>好友</span></button><i></i><button><Share2 /><span>同步空间</span></button><button><HardDrive /><span>APP下载</span></button><button><Image /><span>一刻相册</span></button><button><LayoutGrid /><span>工具</span></button></nav>
    <aside class="sidebar" :class="{ open: mobileNavOpen }"><BrandLogo /><button class="upload" @click="chooseFiles"><Plus :size="19" />上传文件</button>
      <nav class="side-nav">
        <a :class="{ active: mode === 'all' }" @click="changeMode('all')"><HardDrive />全部文件</a><a :class="{ active: mode === 'recent' }" @click="changeMode('recent')"><Clock3 />最近</a><a :class="{ active: mode === 'image' }" @click="changeMode('image')"><Image />图片</a><a :class="{ active: mode === 'video' }" @click="changeMode('video')"><Video />视频</a><a :class="{ active: mode === 'doc' }" @click="changeMode('doc')"><FileText />文档</a><a :class="{ active: mode === 'audio' }" @click="changeMode('audio')"><Music2 />音频</a><a :class="{ active: mode === 'other' }" @click="changeMode('other')"><Archive />其他</a><i></i><a :class="{ active: mode === 'shares' }" @click="changeMode('shares')"><Share2 />我的分享</a><a :class="{ active: mode === 'trash' }" @click="changeMode('trash')"><Trash2 />回收站<em v-if="trashCount" class="nav-count">{{ trashCount > 99 ? '99+' : trashCount }}</em></a>
      </nav><div class="storage"><div><span>Mock 已用 18.6 GB</span><b>1 TB</b></div><progress value="18.6" max="1000"></progress><button>扩容至 5 TB</button></div>
    </aside>
    <button v-if="mobileNavOpen" class="nav-backdrop" aria-label="关闭导航" @click="mobileNavOpen = false"></button>
    <main class="workspace"><header class="topbar"><button class="mobile-menu" aria-label="打开导航" @click="mobileNavOpen = true"><Menu :size="21" /></button><div class="search"><Search :size="18" /><input v-model="keyword" placeholder="搜索我的文件" /><kbd>⌘ K</kbd></div><div class="top-actions"><button><Bell :size="19" /><i></i></button><button><Settings :size="19" /></button><span></span><button class="user" @click="menuOpen = !menuOpen"><b>{{ displayName.slice(0, 1).toUpperCase() }}</b><em>{{ displayName }}</em><ChevronDown :size="15" /></button></div><div v-if="menuOpen" class="user-menu"><strong>{{ displayName }}</strong><small>普通用户</small><button :disabled="loggingOut" @click="logout">{{ loggingOut ? '正在退出…' : '退出登录' }}</button></div></header>
      <section class="content">
        <div class="content-title"><div><h1>{{ title }}</h1><p>共 {{ filteredFiles.length }} 个项目</p></div><select v-model="sortBy" class="sort-select"><option value="time">按时间排序</option><option value="name">按名称排序</option><option value="size">按大小排序</option></select></div>
        <div v-if="currentFolder" class="breadcrumb"><button @click="goRoot">全部文件</button><ChevronRight :size="14" /><span>{{ title }}</span></div>
        <div class="toolbar"><div v-if="mode !== 'trash'" class="tool-left"><button @click="chooseFiles"><Upload :size="17" />上传</button><button @click="createFolder"><Folder :size="17" />新建文件夹</button><span></span><button :disabled="!checked.length" @click="downloadSelected"><Download :size="17" />下载</button><button :disabled="!checked.length" @click="shareSelected"><Share2 :size="17" />分享</button><button :disabled="!checked.length" @click="removeSelected()"><Trash2 :size="17" />删除</button></div><div v-else class="tool-left"><button :disabled="!checked.length || drive.state.loading" @click="restoreSelected()"><RotateCcw :size="17" />恢复</button><button :disabled="!checked.length || drive.state.loading" @click="permanentDelete()"><Trash2 :size="17" />永久删除</button><button :disabled="!trashCount || drive.state.loading" @click="clearTrash">清空回收站</button></div><div class="view-switch"><button :class="{ active: view === 'list' }" @click="view = 'list'"><List :size="18" /></button><button :class="{ active: view === 'grid' }" @click="view = 'grid'"><LayoutGrid :size="18" /></button></div></div>
        <div v-if="view === 'list'" class="file-table"><div class="table-head"><label><input v-model="allVisibleSelected" type="checkbox" /><span><CheckSquare :size="13" /></span></label><div>文件名</div><div>大小</div><div>{{ mode === 'trash' ? '删除时间' : '修改日期' }}</div><div></div></div><div v-for="item in filteredFiles" :key="item.id" class="file-row" @dblclick="openItem(item)"><label><input v-model="checked" type="checkbox" :value="item.id" /><span><CheckSquare :size="13" /></span></label><div class="file-name" @click="openItem(item)"><component :is="iconFor(item.kind)" :class="item.kind" :size="31" fill="currentColor" /><b>{{ item.name }}</b></div><div>{{ formatSize(item.size) }}</div><div>{{ dateText(mode === 'trash' ? item.deletedAt! : item.updatedAt) }}</div><button v-if="mode !== 'trash'" @click.stop="itemMenu(item)"><MoreHorizontal :size="18" /></button><div v-else class="trash-row-actions"><button :disabled="drive.state.loading" title="恢复" @click.stop="restoreSelected([item.id])"><RotateCcw :size="16" /></button><button :disabled="drive.state.loading" title="永久删除" @click.stop="permanentDelete([item.id])"><Trash2 :size="16" /></button></div></div><div v-if="!filteredFiles.length" class="empty">这里还没有文件</div></div>
        <div v-else class="file-grid"><article v-for="item in filteredFiles" :key="item.id" @dblclick="openItem(item)"><button v-if="mode !== 'trash'" @click.stop="itemMenu(item)"><MoreHorizontal :size="17" /></button><div v-else class="trash-grid-actions"><button :disabled="drive.state.loading" title="恢复" @click.stop="restoreSelected([item.id])"><RotateCcw :size="15" /></button><button :disabled="drive.state.loading" title="永久删除" @click.stop="permanentDelete([item.id])"><Trash2 :size="15" /></button></div><component :is="iconFor(item.kind)" :class="item.kind" :size="58" fill="currentColor" @click="openItem(item)" /><b>{{ item.name }}</b><small>{{ dateText(mode === 'trash' ? item.deletedAt! : item.updatedAt).slice(0, 10) }}</small><input v-model="checked" type="checkbox" :value="item.id" /></article></div>
      </section>
    </main><div v-if="notice" class="toast">{{ notice }}</div>
  </div>
</template>
