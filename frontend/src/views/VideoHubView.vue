<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ChevronLeft, ChevronRight, Circle, ListFilter, Play, Search, Video, X } from "@lucide/vue";
import FilePreview from "../components/FilePreview.vue";
import { fileCategory } from "../components/fileIcon";
import type { DriveItem } from "../stores/drive";
import { formatBytes, useDrive } from "../stores/drive";
import { useRouter } from "vue-router";
import "../styles/mobile-media.css";

const router = useRouter();
const drive = useDrive();
const activeTab = ref<"mine" | "downloaded" | "transferred">("mine");
const categoryOpen = ref(false);
const extensionFilter = ref("全部");
const searchOpen = ref(false);
const keyword = ref("");
const loadError = ref("");
const preview = ref<DriveItem | null>(null);
const selectedIds = ref<string[]>([]);
const extensionFilters = ["全部", "MP4", "MOV", "AVI", "其他"];

const videoFiles = computed(() => drive.state.files
  .filter(item => fileCategory(item) === "video" && !drive.isDeleted(item) && !drive.isHidden(item))
  .sort((a, b) => +new Date(b.updatedAt) - +new Date(a.updatedAt)));
const filteredVideos = computed(() => videoFiles.value.filter(item => {
  const extension = item.name.match(/\.([^.]+)$/)?.[1]?.toUpperCase() ?? "其他";
  const matchesExtension = extensionFilter.value === "全部"
    || (extensionFilter.value === "其他" ? !["MP4", "MOV", "AVI"].includes(extension) : extension === extensionFilter.value);
  return matchesExtension && item.name.toLowerCase().includes(keyword.value.trim().toLowerCase());
}));
const recentVideos = computed(() => videoFiles.value.slice(0, 5));

function goBack() {
  if (window.history.state?.back) router.back();
  else void router.replace({ name: "home" });
}
function toggleSelected(id: string) {
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter(itemId => itemId !== id)
    : [...selectedIds.value, id];
}
async function loadVideos() {
  loadError.value = "";
  try { await drive.load(null); }
  catch { loadError.value = drive.state.error || "视频列表加载失败"; }
}
onMounted(() => { void loadVideos(); });
</script>

<template>
  <main class="media-hub video-hub">
    <header class="media-header">
      <button aria-label="返回" @click="goBack"><ChevronLeft /></button>
      <h1>视频</h1>
      <button aria-label="搜索视频" @click="searchOpen = !searchOpen"><Search /></button>
    </header>
    <div v-if="searchOpen" class="video-search"><Search :size="17" /><input v-model="keyword" autofocus placeholder="搜索我的视频" /><button aria-label="关闭搜索" @click="searchOpen = false; keyword = ''"><X :size="17" /></button></div>

    <div class="media-scroll">
      <section class="recent-video-section">
        <div class="media-section-title"><h2>最近视频</h2><button @click="activeTab = 'mine'">查看更多 <ChevronRight :size="15" /></button></div>
        <div v-if="recentVideos.length" class="recent-video-strip">
          <button v-for="(video, index) in recentVideos" :key="video.id" class="recent-video-card" :class="`video-tone-${index % 4}`" @click="preview = video">
            <span class="recent-video-art"><Video :size="39" /></span>
            <span class="recent-video-name">{{ video.name }}</span><Play class="recent-play" :size="17" fill="currentColor" />
          </button>
        </div>
        <p v-else class="media-empty compact">暂无观看记录</p>
      </section>

      <div class="video-quick-actions">
        <button :class="{ active: activeTab === 'downloaded' }" @click="activeTab = activeTab === 'downloaded' ? 'mine' : 'downloaded'"><span class="quick-symbol download-symbol">↓</span>下载的视频</button>
        <button :class="{ active: activeTab === 'transferred' }" @click="activeTab = activeTab === 'transferred' ? 'mine' : 'transferred'"><span class="quick-symbol transfer-symbol">➜</span>转存的视频</button>
      </div>

      <section class="my-video-section">
        <div class="media-section-title mine-video-title">
          <h2>{{ activeTab === 'downloaded' ? '下载的视频' : activeTab === 'transferred' ? '转存的视频' : '我的视频' }}</h2>
          <button class="category-toggle" :aria-pressed="categoryOpen" @click="categoryOpen = !categoryOpen"><ListFilter :size="16" />分类<span class="toggle-pill" :class="{ on: categoryOpen }"><i /></span></button>
        </div>
        <div v-if="categoryOpen && activeTab === 'mine'" class="video-filters">
          <button v-for="filter in extensionFilters" :key="filter" :class="{ active: extensionFilter === filter }" @click="extensionFilter = filter">{{ filter }}</button>
        </div>
        <div v-if="activeTab !== 'mine'" class="media-empty video-empty-state">
          <span class="empty-video-icon"><Video :size="27" /></span>
          <strong>暂无{{ activeTab === 'downloaded' ? '下载' : '转存' }}的视频</strong>
          <span>相关视频会显示在这里</span>
        </div>
        <div v-else-if="loadError" class="media-empty video-empty-state">
          <strong>{{ loadError }}</strong><button class="retry-button" @click="loadVideos">重试</button>
        </div>
        <div v-else-if="!filteredVideos.length" class="media-empty video-empty-state">
          <span class="empty-video-icon"><Video :size="27" /></span>
          <strong>{{ keyword ? '没有找到匹配的视频' : '暂无视频' }}</strong>
          <span>{{ keyword ? '试试其他文件名' : '上传视频后会显示在这里' }}</span>
        </div>
        <div v-else class="video-file-list">
          <article v-for="video in filteredVideos" :key="video.id" class="video-file-row">
            <button class="video-file-main" @click="preview = video">
              <span class="video-thumbnail"><Video :size="22" /></span>
              <span class="video-file-info"><strong>{{ video.name }}</strong><small>{{ new Date(video.updatedAt).toLocaleString('zh-CN', { hour12: false }) }} · {{ formatBytes(video.size) }}</small></span>
            </button>
            <button class="video-select" :aria-label="selectedIds.includes(video.id) ? '取消选择' : '选择视频'" :aria-pressed="selectedIds.includes(video.id)" @click="toggleSelected(video.id)">
              <Circle v-if="!selectedIds.includes(video.id)" :size="17" /><span v-else>✓</span>
            </button>
          </article>
        </div>
      </section>
      <p class="media-demo-note">视频列表读取网盘文件；观看历史、下载和转存分类暂未接入</p>
    </div>
    <FilePreview v-if="preview" :file="preview" @close="preview = null" />
  </main>
</template>
