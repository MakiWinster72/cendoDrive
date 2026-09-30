<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Archive, Bell, CalendarDays, CheckSquare, ChevronDown, ChevronRight, CircleUserRound, Cloud, Clock3, Download, Eye, File, FileText, Folder, HardDrive, House, Image, LayoutGrid, List, Menu, MessageCircle, MoreHorizontal, Music2, Plus, Printer, RotateCcw, Search, Settings, Share2, SlidersHorizontal, Sparkles, Trash2, Upload, UserRound, Video, WandSparkles } from 'lucide-vue-next'
import BrandLogo from '../components/BrandLogo.vue'
import UploadPanel from '../components/UploadPanel.vue'
import { useAuth } from '../stores/auth'
import { formatSize, useDrive, type DriveItem } from '../stores/drive'

type Mode = 'all' | 'recent' | 'image' | 'video' | 'doc' | 'audio' | 'other' | 'shares' | 'trash'
const router = useRouter(), auth = useAuth(), drive = useDrive()
const view = ref<'list' | 'grid'>('list'), mode = ref<Mode>('all'), currentFolder = ref<string | null>(null)
const keyword = ref(''), checked = ref<string[]>([]), menuOpen = ref(false), mobileNavOpen = ref(false), uploadPanelOpen = ref(false)
const sortBy = ref<'name' | 'time' | 'size'>('time'), notice = ref('')
const mobileTab = ref<'home' | 'files' | 'share' | 'profile'>('files')
const displayName = computed(() => auth.user.value?.nickname || auth.user.value?.username || 'CendoDrive 用户')
const modeNames: Record<Mode, string> = { all: '全部文件', recent: '最近', image: '图片', video: '视频', doc: '文档', audio: '音频', other: '其他', shares: '我的分享', trash: '回收站' }
const title = computed(() => currentFolder.value ? drive.get(currentFolder.value)?.name || '文件夹' : modeNames[mode.value])
const folders = computed(() => drive.state.files.filter((item) => item.kind === 'folder' && !item.deletedAt))
const uploadFolders = computed(() => [{ id: null, name: displayName.value }, ...folders.value.map((folder) => ({ id: folder.id, name: folder.name }))])
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
  return [...items].sort((a, b) => sortBy.value === 'name' ? a.name.localeCompare(b.name, 'zh-CN') : sortBy.value === 'size' ? b.size - a.size : +new Date(b.updatedAt) - +new Date(a.updatedAt))
})
const iconFor = (kind: string) => kind === 'folder' ? Folder : kind === 'image' ? Image : kind === 'video' ? Video : kind === 'audio' ? Music2 : ['pdf', 'doc'].includes(kind) ? FileText : File
const dateText = (date: string) => new Date(date).toLocaleString('zh-CN', { hour12: false }).replaceAll('/', '-')
function flash(text: string) { notice.value = text; window.setTimeout(() => notice.value = '', 2200) }
function changeMode(next: Mode) { mode.value = next; currentFolder.value = null; checked.value = []; mobileNavOpen.value = false }
function openItem(item: DriveItem) { if (item.kind === 'folder' && mode.value !== 'trash') { mode.value = 'all'; currentFolder.value = item.id; checked.value = [] } }
function goRoot() { currentFolder.value = null; checked.value = [] }
function createFolder() { const name = prompt('请输入文件夹名称'); if (!name) return; try { drive.createFolder(name, currentFolder.value); flash('文件夹创建成功') } catch (e) { alert(e instanceof Error ? e.message : '创建失败') } }
function chooseFiles() { uploadPanelOpen.value = true }
function handleUploaded(item: DriveItem) { drive.addUploaded(item); flash(`已上传：${item.name}`) }
function renameItem(item: DriveItem) { const name = prompt('请输入新名称', item.name); if (name) { drive.rename(item.id, name); flash('重命名成功') } }
function moveItem(item: DriveItem) { const hint = ['根目录', ...folders.value.filter((folder) => folder.id !== item.id).map((folder) => folder.name)].join('、'); const name = prompt(`移动到哪个文件夹？\n可选：${hint}`, '根目录'); if (!name) return; const target = name === '根目录' ? null : folders.value.find((folder) => folder.name === name)?.id; if (name !== '根目录' && !target) return alert('未找到目标文件夹'); drive.move(item.id, target || null); checked.value = []; flash('移动成功') }
function itemMenu(item: DriveItem) { if (mode.value === 'shares') { if (confirm('确定取消该分享吗？')) { const share = drive.state.shares.find((record) => record.itemId === item.id && !record.cancelled); if (share) drive.cancelShare(share.id); flash('分享已取消') } return } const action = prompt('输入操作：重命名 / 移动 / 删除', '重命名'); if (action === '重命名') renameItem(item); else if (action === '移动') moveItem(item); else if (action === '删除') removeSelected([item.id]) }
function removeSelected(ids = checked.value) { if (!ids.length || !confirm('确定移入回收站吗？')) return; drive.trash(ids); checked.value = []; flash('已移入回收站') }
function restoreSelected() { drive.restore(checked.value); checked.value = []; flash('文件已恢复') }
function permanentDelete() { if (!checked.value.length || !confirm('永久删除后无法恢复，是否继续？')) return; drive.removeForever(checked.value); checked.value = []; flash('已永久删除') }
function clearTrash() { if (confirm('确定清空回收站吗？')) { drive.emptyTrash(); checked.value = []; flash('回收站已清空') } }
async function shareSelected() { if (!checked.value.length) return; const days = Number(prompt('分享有效天数', '7') || 7); const record = drive.share(checked.value[0], days); const link = `${location.origin}/share/${record.id}`; await navigator.clipboard?.writeText(`${link} 提取码：${record.code}`).catch(() => {}); alert(`分享链接：${link}\n提取码：${record.code}\n有效期：${days} 天\n已尝试复制到剪贴板`) }
function downloadSelected() { checked.value.forEach((itemId) => { const item = drive.get(itemId); if (!item || item.kind === 'folder') return; const blob = new Blob([`CendoDrive Mock 文件：${item.name}`]); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = item.name; link.click(); URL.revokeObjectURL(link.href) }); flash('已开始模拟下载') }
async function logout() { const revoked = await auth.logout(); await router.replace({ name: 'login', query: revoked ? {} : { logoutWarning: '1' } }) }
</script>

<template>
  <UploadPanel :open="uploadPanelOpen" :folder-options="uploadFolders" :initial-folder-id="currentFolder" @close="uploadPanelOpen = false" @uploaded="handleUploaded" />

  <div class="mobile-app">
    <template v-if="mobileTab === 'home'">
      <header class="m-home-head"><div class="m-vip"><b>VIP</b><span>畅听有声书<small>去领取</small></span></div><div><Bell /><HardDrive /><button class="m-upload-trigger" aria-label="上传文件" @click="chooseFiles"><Plus /></button></div></header>
      <section class="m-profile-search"><div class="m-avatar">C</div><span>{{ displayName }}</span><button><WandSparkles :size="20" /></button></section>
      <section class="m-tools"><button><FileText /><span>听记</span></button><button><CalendarDays /><span>天天练</span></button><button><MessageCircle /><span>笔记</span></button><button><Upload /><span>备份</span></button><button><HardDrive /><span>同步</span></button><button @click="changeMode('doc'); mobileTab='files'"><FileText /><span>文档</span></button><button><Music2 /><span>音频</span></button><button><Printer /><span>打印</span></button><button><Sparkles /><span>星盘</span></button><button><LayoutGrid /><span>全部工具</span></button></section>
      <section class="m-panel"><div class="m-panel-title"><h2>最近</h2><button><Eye :size="17" /><ChevronRight :size="18" /></button></div><div v-for="item in drive.state.files.filter(item => !item.deletedAt && item.kind !== 'folder').slice(0,3)" :key="item.id" class="m-recent"><span><component :is="iconFor(item.kind)" /></span><div><b>{{ item.name }}</b><small>{{ dateText(item.updatedAt) }}　来自 CendoDrive</small></div></div></section>
      <section class="m-banner"><div><h2>转存 <small>订阅<i></i></small></h2><p>收藏分享内容，随时查看</p></div></section>
      <section class="m-memory"><div class="m-panel-title"><h2>回忆</h2><button><Eye :size="17" /><ChevronRight :size="18" /></button></div><div class="memory-art"><span>把每一份美好，留在云端</span></div></section>
    </template>
    <template v-else-if="mobileTab === 'files'">
      <header class="m-file-head"><h1>文件</h1><div><HardDrive :size="23" /><MoreHorizontal :size="24" /></div></header>
      <div class="m-search"><Search :size="19" /><input v-model="keyword" placeholder="搜索网盘文件" /></div>
      <div class="m-filter"><button>智能排序 <SlidersHorizontal :size="15" /></button><button class="active">全部</button><button>我的资源</button><button>我创建的</button><button>我加工的</button></div>
      <div class="m-file-list"><div v-for="item in filteredFiles" :key="item.id" class="m-file-row" @click="openItem(item)"><span class="m-folder"><component :is="iconFor(item.kind)" fill="currentColor" /></span><div><b>{{ item.name }}</b><small>{{ item.kind === 'folder' ? '常看　' : formatSize(item.size) + '　' }}{{ dateText(item.updatedAt).slice(0,16) }}</small></div><input v-model="checked" type="checkbox" :value="item.id" @click.stop /></div><p v-if="!filteredFiles.length" class="m-empty">这里还没有文件</p></div>
      <button class="m-fab" aria-label="上传" @click="chooseFiles"><Plus :size="30" /></button>
    </template>
    <template v-else><section class="m-placeholder"><component :is="mobileTab === 'share' ? Share2 : CircleUserRound" :size="58" /><h2>{{ mobileTab === 'share' ? '我的分享' : '个人中心' }}</h2><p>{{ mobileTab === 'share' ? '已创建的分享可在 PC 端管理' : displayName }}</p></section></template>
    <nav class="m-bottom-nav"><button :class="{active:mobileTab==='home'}" @click="mobileTab='home'"><House /><span>首页</span></button><button :class="{active:mobileTab==='files'}" @click="mobileTab='files';changeMode('all')"><Folder /><span>文件</span></button><button class="genflow"><i><Sparkles /></i><span>GenFlow</span></button><button :class="{active:mobileTab==='share'}" @click="mobileTab='share'"><Share2 /><em>25</em><span>共享</span></button><button :class="{active:mobileTab==='profile'}" @click="mobileTab='profile'"><UserRound /><span>我的</span></button></nav>
  </div>

  <div class="drive-shell desktop-drive">
    <nav class="desktop-rail"><BrandLogo /><button class="active"><Cloud /><span>首页</span></button><button><Upload /><span>传输</span></button><button><UserRound /><span>好友</span></button><i></i><button><Share2 /><span>同步空间</span></button><button><HardDrive /><span>APP下载</span></button><button><Image /><span>一刻相册</span></button><button><LayoutGrid /><span>工具</span></button></nav>
    <aside class="sidebar" :class="{ open: mobileNavOpen }"><BrandLogo /><button class="upload" @click="chooseFiles"><Plus :size="19" />上传文件</button>
      <nav class="side-nav">
        <a :class="{ active: mode === 'all' }" @click="changeMode('all')"><HardDrive />全部文件</a><a :class="{ active: mode === 'recent' }" @click="changeMode('recent')"><Clock3 />最近</a><a :class="{ active: mode === 'image' }" @click="changeMode('image')"><Image />图片</a><a :class="{ active: mode === 'video' }" @click="changeMode('video')"><Video />视频</a><a :class="{ active: mode === 'doc' }" @click="changeMode('doc')"><FileText />文档</a><a :class="{ active: mode === 'audio' }" @click="changeMode('audio')"><Music2 />音频</a><a :class="{ active: mode === 'other' }" @click="changeMode('other')"><Archive />其他</a><i></i><a :class="{ active: mode === 'shares' }" @click="changeMode('shares')"><Share2 />我的分享</a><a :class="{ active: mode === 'trash' }" @click="changeMode('trash')"><Trash2 />回收站</a>
      </nav><div class="storage"><div><span>Mock 已用 18.6 GB</span><b>1 TB</b></div><progress value="18.6" max="1000"></progress><button>扩容至 5 TB</button></div>
    </aside>
    <button v-if="mobileNavOpen" class="nav-backdrop" aria-label="关闭导航" @click="mobileNavOpen = false"></button>
    <main class="workspace"><header class="topbar"><button class="mobile-menu" aria-label="打开导航" @click="mobileNavOpen = true"><Menu :size="21" /></button><div class="search"><Search :size="18" /><input v-model="keyword" placeholder="搜索我的文件" /><kbd>⌘ K</kbd></div><div class="top-actions"><button><Bell :size="19" /><i></i></button><button><Settings :size="19" /></button><span></span><button class="user" @click="menuOpen = !menuOpen"><b>{{ displayName.slice(0, 1).toUpperCase() }}</b><em>{{ displayName }}</em><ChevronDown :size="15" /></button></div><div v-if="menuOpen" class="user-menu"><strong>{{ displayName }}</strong><small>普通用户</small><button @click="logout">退出登录</button></div></header>
      <section class="content">
        <div class="content-title"><div><h1>{{ title }}</h1><p>共 {{ filteredFiles.length }} 个项目</p></div><select v-model="sortBy" class="sort-select"><option value="time">按时间排序</option><option value="name">按名称排序</option><option value="size">按大小排序</option></select></div>
        <div v-if="currentFolder" class="breadcrumb"><button @click="goRoot">全部文件</button><ChevronRight :size="14" /><span>{{ title }}</span></div>
        <div class="toolbar"><div v-if="mode !== 'trash'" class="tool-left"><button @click="chooseFiles"><Upload :size="17" />上传</button><button @click="createFolder"><Folder :size="17" />新建文件夹</button><span></span><button :disabled="!checked.length" @click="downloadSelected"><Download :size="17" />下载</button><button :disabled="!checked.length" @click="shareSelected"><Share2 :size="17" />分享</button><button :disabled="!checked.length" @click="removeSelected()"><Trash2 :size="17" />删除</button></div><div v-else class="tool-left"><button :disabled="!checked.length" @click="restoreSelected"><RotateCcw :size="17" />恢复</button><button :disabled="!checked.length" @click="permanentDelete"><Trash2 :size="17" />永久删除</button><button @click="clearTrash">清空回收站</button></div><div class="view-switch"><button :class="{ active: view === 'list' }" @click="view = 'list'"><List :size="18" /></button><button :class="{ active: view === 'grid' }" @click="view = 'grid'"><LayoutGrid :size="18" /></button></div></div>
        <div v-if="view === 'list'" class="file-table"><div class="table-head"><label></label><div>文件名</div><div>大小</div><div>修改日期</div><div></div></div><div v-for="item in filteredFiles" :key="item.id" class="file-row" @dblclick="openItem(item)"><label><input v-model="checked" type="checkbox" :value="item.id" /><span><CheckSquare :size="13" /></span></label><div class="file-name" @click="openItem(item)"><component :is="iconFor(item.kind)" :class="item.kind" :size="31" fill="currentColor" /><b>{{ item.name }}</b></div><div>{{ formatSize(item.size) }}</div><div>{{ dateText(item.updatedAt) }}</div><button v-if="mode !== 'trash'" @click.stop="itemMenu(item)"><MoreHorizontal :size="18" /></button></div><div v-if="!filteredFiles.length" class="empty">这里还没有文件</div></div>
        <div v-else class="file-grid"><article v-for="item in filteredFiles" :key="item.id" @dblclick="openItem(item)"><button v-if="mode !== 'trash'" @click.stop="itemMenu(item)"><MoreHorizontal :size="17" /></button><component :is="iconFor(item.kind)" :class="item.kind" :size="58" fill="currentColor" @click="openItem(item)" /><b>{{ item.name }}</b><small>{{ dateText(item.updatedAt).slice(0, 10) }}</small><input v-model="checked" type="checkbox" :value="item.id" /></article></div>
      </section>
    </main><div v-if="notice" class="toast">{{ notice }}</div>
  </div>
</template>
