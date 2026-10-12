<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { DriveItemResponse } from "../api/drive";
import { fetchPreviewFile } from "../api/preview";
import { decodeText, previewFormat, previewLimit } from "../preview/formats";
import { thumbnailSlot } from "../preview/thumbnailQueue";
import { iconForFile } from "./fileIcon";

const props = withDefaults(
  defineProps<{
    file: DriveItemResponse;
    size?: number;
  }>(),
  { size: 40 },
);
const root = ref<HTMLElement>();
const canvas = ref<HTMLCanvasElement>();
const image = ref("");
const html = ref("");
const ready = ref(false);
let observer: IntersectionObserver | undefined;
let controller: AbortController | undefined;
let cleanupPdf: (() => void) | undefined;
let revision = 0;
let visible = false;

function clear() {
  ++revision;
  controller?.abort();
  cleanupPdf?.();
  cleanupPdf = undefined;
  if (image.value) URL.revokeObjectURL(image.value);
  image.value = "";
  html.value = "";
  ready.value = false;
}
async function load() {
  clear();
  const file = props.file;
  const format = previewFormat(file.name);
  if (
    file.kind !== "file" ||
    file.deletedAt ||
    !format ||
    !["image", "pdf", "docx", "markdown"].includes(format.kind) ||
    file.size > previewLimit(format.kind)
  )
    return;
  const run = revision;
  const abort = new AbortController();
  controller = abort;
  const current = () => run === revision && !abort.signal.aborted;
  const release = await thumbnailSlot();
  try {
    if (!current()) return;
    const blob = await fetchPreviewFile(file, abort.signal);
    if (!current()) return;
    if (format.kind === "image") {
      image.value = URL.createObjectURL(blob);
    } else if (format.kind === "pdf") {
      const { getDocument, GlobalWorkerOptions } = await import("pdfjs-dist");
      const { default: workerUrl } =
        await import("pdfjs-dist/build/pdf.worker.min.mjs?url");
      const data = await blob.arrayBuffer();
      if (!current()) return;
      GlobalWorkerOptions.workerSrc = workerUrl;
      const task = getDocument({
        data,
        maxImageSize: 16_000_000,
        enableXfa: false,
        cMapUrl: `${import.meta.env.BASE_URL}pdfjs/cmaps/`,
        cMapPacked: true,
        standardFontDataUrl: `${import.meta.env.BASE_URL}pdfjs/standard_fonts/`,
        wasmUrl: `${import.meta.env.BASE_URL}pdfjs/wasm/`,
      });
      cleanupPdf = () => {
        void task.destroy();
      };
      task.onPassword = () => {
        void task.destroy();
      };
      const pdf = await task.promise;
      const page = await pdf.getPage(1);
      if (!current() || !canvas.value) return;
      const natural = page.getViewport({ scale: 1 });
      const viewport = page.getViewport({
        scale: 240 / Math.max(natural.width, natural.height),
      });
      const target = canvas.value;
      target.width = Math.ceil(viewport.width);
      target.height = Math.ceil(viewport.height);
      const rendering = page.render({
        canvas: target,
        canvasContext: target.getContext("2d")!,
        viewport,
      });
      cleanupPdf = () => {
        rendering.cancel();
        void task.destroy();
      };
      await rendering.promise;
      if (current()) ready.value = true;
      await task.destroy();
      if (current()) cleanupPdf = undefined;
    } else {
      const { renderDocx, renderMarkdown } = await import("../preview/render");
      const buffer = await blob.arrayBuffer();
      const content =
        format.kind === "docx"
          ? await renderDocx(buffer)
          : await renderMarkdown(decodeText(buffer).slice(0, 32_000));
      if (!current()) return;
      html.value = content;
      ready.value = !!content.trim();
    }
  } catch {
    // The normal file icon remains available when a thumbnail cannot be produced.
  } finally {
    if (current()) {
      cleanupPdf?.();
      cleanupPdf = undefined;
    }
    release();
  }
}
onMounted(() => {
  if (!root.value || typeof IntersectionObserver === "undefined") return;
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        visible = true;
        observer?.disconnect();
        void load();
      }
    },
    { rootMargin: "100px" },
  );
  observer.observe(root.value);
});
watch(
  () => [
    props.file.id,
    props.file.name,
    props.file.updatedAt,
    props.file.size,
    props.file.deletedAt,
  ],
  () => {
    if (visible) void load();
    else clear();
  },
);
onBeforeUnmount(() => {
  observer?.disconnect();
  clear();
});
</script>
<template>
  <span
    ref="root"
    class="file-thumbnail"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      '--thumbnail-scale': String(size / 274),
    }"
    aria-hidden="true"
  >
    <component
      v-if="!ready"
      :is="iconForFile(file)"
      :size="size"
      :class="file.kind"
    />
    <img
      v-if="image"
      :src="image"
      alt=""
      @load="ready = true"
      @error="clear()"
    />
    <canvas ref="canvas" v-show="ready && !image && !html" />
    <span v-if="html && ready" class="file-thumbnail-paper"
      ><span class="file-thumbnail-document" v-html="html"
    /></span>
  </span>
</template>
<style scoped>
.file-thumbnail {
  display: inline-flex;
  position: relative;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  vertical-align: middle;
  overflow: hidden;
  border-radius: 4px;
}
.file-thumbnail > img,
.file-thumbnail > canvas {
  position: absolute;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.file-thumbnail-paper {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: white;
  border: 1px solid #dce1e8;
  color: #263040;
}
.file-thumbnail-document {
  display: block;
  width: 240px;
  padding: 16px;
  font: 12px/1.5 serif;
  transform: scale(var(--thumbnail-scale));
  transform-origin: top left;
  pointer-events: none;
}
.file-thumbnail-document :deep(img) {
  max-width: 100%;
}
.file-thumbnail-document :deep(p),
.file-thumbnail-document :deep(h1),
.file-thumbnail-document :deep(h2) {
  margin: 0 0 8px;
}
</style>
