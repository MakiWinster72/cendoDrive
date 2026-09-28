<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Archive, Bell, CheckSquare, ChevronDown, Clock3, Download, File, FileText, Folder, Grid2X2, HardDrive, Image, LayoutGrid, List, Menu, MoreHorizontal, Music2, Plus, Search, Settings, Share2, Star, Trash2, Upload, Users, Video } from 'lucide-vue-next'
import BrandLogo from '../components/BrandLogo.vue'
import { useAuth } from '../stores/auth'

const router = useRouter()
const auth = useAuth()
const view = ref<'list' | 'grid'>('list')
const keyword = ref('')
const checked = ref<string[]>([])
const menuOpen = ref(false)
const mobileNavOpen = ref(false)

const files = [
  { id: '1', name: '工作资料', kind: 'folder', size: '—', date: '2026-09-27 18:42' },
  { id: '2', name: '旅行照片', kind: 'folder', size: '—', date: '2026-09-25 20:16' },
  { id: '3', name: '项目方案.pdf', kind: 'pdf', size: '8.6 MB', date: '2026-09-21 10:08' },
  { id: '4', name: '季度总结.docx', kind: 'doc', size: '2.4 MB', date: '2026-09-18 16:30' },
  { id: '5', name: '海边日落.jpg', kind: 'image', size: '5.1 MB', date: '2026-09-12 12:05' },
]
const filteredFiles = computed(() => files.filter((file) => file.name.toLowerCase().includes(keyword.value.toLowerCase())))
const iconFor = (kind: string) => kind === 'folder' ? Folder : kind === 'image' ? Image : kind === 'pdf' ? FileText : File

async function logout() {
  auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <div class="drive-shell">
    <aside class="sidebar" :class="{ open: mobileNavOpen }">
      <BrandLogo />
      <button class="upload"><Plus :size="19" />上传文件</button>
      <nav class="side-nav">
        <a class="active" @click="mobileNavOpen = false"><HardDrive />全部文件</a>
        <a><Clock3 />最近</a>
        <a><Image />图片</a>
        <a><Video />视频</a>
        <a><FileText />文档</a>
        <a><Music2 />音频</a>
        <a><Archive />其他</a>
        <i></i>
        <a><Share2 />我的分享</a>
        <a><Trash2 />回收站</a>
      </nav>
      <div class="storage">
        <div><span>已用 18.6 GB</span><b>1 TB</b></div>
        <progress value="18.6" max="1000"></progress>
        <button>扩容至 5 TB</button>
      </div>
    </aside>
    <button v-if="mobileNavOpen" class="nav-backdrop" aria-label="关闭导航" @click="mobileNavOpen = false"></button>

    <main class="workspace">
      <header class="topbar">
        <button class="mobile-menu" aria-label="打开导航" @click="mobileNavOpen = true"><Menu :size="21" /></button>
        <div class="search"><Search :size="18" /><input v-model="keyword" placeholder="搜索我的文件" /><kbd>⌘ K</kbd></div>
        <div class="top-actions"><button><Bell :size="19" /><i></i></button><button><Settings :size="19" /></button><span></span><button class="user" @click="menuOpen = !menuOpen"><b>{{ auth.user.value?.name?.slice(0, 1).toUpperCase() || '云' }}</b><em>{{ auth.user.value?.name || '云盘用户' }}</em><ChevronDown :size="15" /></button></div>
        <div v-if="menuOpen" class="user-menu"><strong>{{ auth.user.value?.name }}</strong><small>普通用户</small><button @click="logout">退出登录</button></div>
      </header>

      <section class="content">
        <div class="content-title"><div><h1>全部文件</h1><p>共 {{ files.length }} 个文件</p></div><button><Users :size="17" />新建共享</button></div>
        <div class="toolbar">
          <div class="tool-left"><button><Upload :size="17" />上传</button><button><Folder :size="17" />新建文件夹</button><span></span><button :disabled="!checked.length"><Download :size="17" />下载</button><button :disabled="!checked.length"><Share2 :size="17" />分享</button><button :disabled="!checked.length"><Trash2 :size="17" />删除</button><button><MoreHorizontal :size="18" /></button></div>
          <div class="view-switch"><button :class="{ active: view === 'list' }" @click="view = 'list'"><List :size="18" /></button><button :class="{ active: view === 'grid' }" @click="view = 'grid'"><LayoutGrid :size="18" /></button></div>
        </div>

        <div v-if="view === 'list'" class="file-table">
          <div class="table-head"><label><input type="checkbox" /><span></span></label><div>文件名</div><div>大小</div><div>修改日期</div><div></div></div>
          <div v-for="item in filteredFiles" :key="item.id" class="file-row">
            <label><input v-model="checked" type="checkbox" :value="item.id" /><span><CheckSquare :size="13" /></span></label>
            <div class="file-name"><component :is="iconFor(item.kind)" :class="item.kind" :size="31" fill="currentColor" /><b>{{ item.name }}</b><span><Star :size="15" /></span></div>
            <div>{{ item.size }}</div><div>{{ item.date }}</div><button><MoreHorizontal :size="18" /></button>
          </div>
          <div v-if="!filteredFiles.length" class="empty">没有找到相关文件</div>
        </div>

        <div v-else class="file-grid">
          <article v-for="item in filteredFiles" :key="item.id"><button><MoreHorizontal :size="17" /></button><component :is="iconFor(item.kind)" :class="item.kind" :size="58" fill="currentColor" /><b>{{ item.name }}</b><small>{{ item.date.slice(0, 10) }}</small></article>
        </div>
      </section>
    </main>
  </div>
</template>
