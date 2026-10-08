<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ChevronLeft, ChevronRight, FolderInput, X } from "@lucide/vue";
import { iconForFile } from "./fileIcon";
import { formatBytes } from "../stores/drive";
import { useFileSearch } from "./useFileSearch";
import type { FileSearchHit, SearchType } from "../api/drive";
import "../styles/search.css";

const props = defineProps<{
  query: string;
  parentId: string | null;
  initialType: SearchType;
  sort: "time" | "name" | "size";
  busy?: boolean;
}>();
const emit = defineEmits<{ open: [hit: FileSearchHit]; locate: [hit: FileSearchHit]; clear: [] }>();
const scope = ref<"all" | "folder">(props.parentId ? "folder" : "all");
const type = ref<SearchType>(props.initialType);
watch(() => props.parentId, id => { scope.value = id ? "folder" : "all"; });
watch(() => props.initialType, value => { type.value = value; });
const params = computed(() => ({ q: props.query, scope: scope.value,
  parentId: scope.value === "folder" ? props.parentId : null, type: type.value, sort: props.sort }));
const { result, loading, error, page, goPage, retry } = useFileSearch(params);
const pageCount = computed(() => Math.max(1, Math.ceil((result.value?.total ?? 0) / 20)));
</script>

<template>
  <section class="file-search-panel" aria-label="文件名搜索结果" :aria-busy="loading">
    <div class="file-search-controls">
      <label>范围<select v-model="scope" aria-label="搜索范围"><option value="all">全网盘</option><option value="folder">当前目录及子目录</option></select></label>
      <label>类型<select v-model="type" aria-label="搜索类型"><option value="all">全部类型</option><option value="folder">文件夹</option><option value="doc">文档</option><option value="image">图片</option><option value="video">视频</option><option value="audio">音频</option><option value="other">其他</option></select></label>
      <button type="button" class="file-search-clear" aria-label="退出搜索" @click="emit('clear')"><X :size="18" />退出搜索</button>
    </div>
    <p class="file-search-note">仅匹配文件名，不搜索正文或图片文字；隐藏空间和回收站不参与搜索。</p>
    <p v-if="loading" class="file-search-status" role="status">正在搜索…</p>
    <p v-else-if="error" class="file-search-status" role="alert">{{ error }} <button type="button" @click="retry">重试</button></p>
    <template v-else-if="result">
      <p class="file-search-summary" role="status">找到 {{ result.total }} 个项目</p>
      <ul v-if="result.items.length" class="file-search-results">
        <li v-for="hit in result.items" :key="hit.file.id">
          <button type="button" class="file-search-open" :disabled="busy" :aria-label="`打开 ${hit.file.name}`" @click="emit('open', hit)">
            <component :is="iconForFile(hit.file)" :size="30" :class="hit.file.kind" />
            <span><b>{{ hit.file.name }}</b><small>{{ hit.path === '/' ? '我的网盘' : '我的网盘' + hit.path }} · {{ hit.file.kind === 'folder' ? '文件夹' : formatBytes(hit.file.size) }}</small></span>
          </button>
          <button type="button" class="file-search-locate" :disabled="busy" :aria-label="`打开 ${hit.file.name} 所在目录`" @click="emit('locate', hit)"><FolderInput :size="18" /><span>所在目录</span></button>
        </li>
      </ul>
      <p v-else class="file-search-status">没有匹配的文件名，请调整关键词、范围或类型。</p>
      <nav v-if="result.total > 20" class="file-search-pagination" aria-label="搜索分页">
        <button type="button" aria-label="搜索上一页" :disabled="page === 0" @click="goPage(page - 1)"><ChevronLeft :size="18" />上一页</button>
        <span>{{ page + 1 }} / {{ pageCount }}</span>
        <button type="button" aria-label="搜索下一页" :disabled="page + 1 >= pageCount" @click="goPage(page + 1)">下一页<ChevronRight :size="18" /></button>
      </nav>
    </template>
  </section>
</template>
