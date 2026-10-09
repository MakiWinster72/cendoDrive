<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Download, X, FileText, RefreshCw } from "@lucide/vue";
import { fetchPreviewFile } from "../api/preview";
import { downloadFile, driveErrorMessage, type DriveItemResponse } from "../api/drive";
import { useTransfers } from "../stores/transfers";
import { decodeText, previewFormat } from "../preview/formats";
import "../styles/preview.css";
const PdfPreview = defineAsyncComponent(() => import("./PdfPreview.vue"));
const props = defineProps<{ file: DriveItemResponse; fetchContent?: (signal: AbortSignal) => Promise<Blob>; downloadContent?: () => Promise<void> }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement | null>(null);
const format = computed(() => previewFormat(props.file.name));
const loading = ref(true), error = ref(""), downloading = ref(false);
const blob = ref<Blob | null>(null), url = ref(""), text = ref(""), html = ref("");
const source = ref(false);
let controller: AbortController | undefined;
function release() {
  controller?.abort();
  if (url.value) URL.revokeObjectURL(url.value);
  url.value = ""; blob.value = null; text.value = ""; html.value = "";
}
async function load() {
  release();
  error.value = ""; loading.value = true; source.value = false;
  const request = new AbortController();
  controller = request;
  const file = props.file;
  const kind = previewFormat(file.name)?.kind;
  try {
    const content = await (props.fetchContent ? props.fetchContent(request.signal) : fetchPreviewFile(file, request.signal));
    if (request.signal.aborted) return;
    if (kind === "text" || kind === "markdown") {
      const decoded = decodeText(await content.arrayBuffer());
      if (request.signal.aborted) return;
      text.value = decoded;
      if (kind === "markdown") {
        const { renderMarkdown } = await import("../preview/render");
        const result = await renderMarkdown(decoded);
        if (request.signal.aborted) return;
        html.value = result;
      }
    } else if (kind === "docx") {
      const { renderDocx } = await import("../preview/render");
      const result = await renderDocx(await content.arrayBuffer());
      if (request.signal.aborted) return;
      html.value = result;
    }
    if (request.signal.aborted) return;
    blob.value = content;
    if (kind === "image" || kind === "video") url.value = URL.createObjectURL(content);
  } catch (cause) {
    if (!request.signal.aborted) error.value = driveErrorMessage(cause, "预览加载失败，请重试或下载查看");
  } finally { if (controller === request) loading.value = false; }
}
async function download() {
  if (downloading.value) return;
  downloading.value = true;
  try {
    await (props.downloadContent ? props.downloadContent() : downloadFile(props.file.id, props.file.name));
    if (!props.downloadContent) useTransfers().recordDownload(props.file.name, props.file.size, props.file.id);
  }
  catch (cause) { error.value = driveErrorMessage(cause, "下载失败，请重试"); if (!props.downloadContent) useTransfers().recordFailure('download', props.file.name, props.file.size, error.value); }
  finally { downloading.value = false; }
}
let disposed = false;
const previousOverflow = document.body.style.overflow;
onMounted(async () => {
  await nextTick();
  if (disposed) return;
  dialog.value?.showModal();
  document.body.style.overflow = "hidden";
});
watch(() => props.file, () => { void load(); }, { immediate: true });
onBeforeUnmount(() => {
  disposed = true;
  release();
  dialog.value?.close();
  document.body.style.overflow = previousOverflow;
});
</script>
<template>
  <Teleport to="body">
    <dialog ref="dialog" class="file-preview" aria-labelledby="preview-title" @cancel.prevent="emit('close')" @click.self="emit('close')">
      <div class="preview-shell">
        <header class="preview-header">
          <FileText :size="20" aria-hidden="true" />
          <div class="preview-heading"><h2 id="preview-title" :title="file.name">{{ file.name }}</h2><span>文件预览 · {{ format?.kind === 'markdown' ? 'Markdown' : format?.kind?.toUpperCase() || '未知格式' }}</span></div>
          <button class="preview-download" :aria-label="downloading ? '下载中' : '下载原文件'" :disabled="downloading" @click="download"><Download :size="18" /><span>{{ downloading ? '下载中' : '下载' }}</span></button>
          <button autofocus aria-label="关闭文件预览" @click="emit('close')"><X :size="22" /></button>
        </header>
        <main class="preview-content" :aria-busy="loading">
          <div v-if="loading" class="preview-state" role="status"><span class="preview-spinner"></span><p>正在加载预览…</p></div>
          <div v-else-if="error" class="preview-state" role="alert"><FileText :size="40" /><p>{{ error }}</p><button @click="load"><RefreshCw :size="16" />重新加载</button><small>也可以使用右上角的下载按钮</small></div>
          <img v-else-if="format?.kind === 'image'" class="preview-image" :src="url" :alt="file.name" @error="error = '图片无法显示，可能格式不受浏览器支持或文件已损坏，请下载查看'" />
          <video v-else-if="format?.kind === 'video'" class="preview-video" :src="url" controls playsinline preload="metadata" @error="error = '视频无法播放，可能编码不受浏览器支持或文件已损坏，请下载查看'" />
          <PdfPreview v-else-if="format?.kind === 'pdf' && blob" :blob="blob" @error="error = $event" />
          <template v-else-if="format?.kind === 'text' || format?.kind === 'markdown'">
            <div v-if="format.kind === 'markdown'" class="preview-text-tabs"><button :aria-pressed="!source" @click="source = false">阅读</button><button :aria-pressed="source" @click="source = true">源码</button></div>
            <pre v-if="format.kind === 'text' || source" class="preview-text">{{ text || '（空文件）' }}</pre>
            <article v-else class="preview-document" v-html="html || '<p>（空文件）</p>'"></article>
          </template>
          <article v-else-if="format?.kind === 'docx'" class="preview-document" v-html="html || '<p>（空文档）</p>'"></article>
        </main>
      </div>
    </dialog>
  </Teleport>
</template>
