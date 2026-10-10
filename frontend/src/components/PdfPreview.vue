<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  getDocument,
  GlobalWorkerOptions,
  type PDFDocumentProxy,
  type RenderTask,
} from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
GlobalWorkerOptions.workerSrc = workerUrl;
const props = defineProps<{ blob: Blob }>();
const emit = defineEmits<{ error: [message: string] }>();
const canvas = ref<HTMLCanvasElement | null>(null);
const page = ref(1),
  pages = ref(0),
  busy = ref(true);
let document: PDFDocumentProxy | undefined;
let task: ReturnType<typeof getDocument> | undefined;
let rendering: RenderTask | undefined;
let disposed = false;
let revision = 0;
async function draw() {
  const run = ++revision;
  rendering?.cancel();
  busy.value = true;
  try {
    if (!document) return;
    const sheet = await document.getPage(page.value);
    await nextTick();
    if (disposed || run !== revision || !canvas.value) return;
    const natural = sheet.getViewport({ scale: 1 });
    const width = Math.min(1100, canvas.value.parentElement!.clientWidth - 32);
    const scale = Math.min(2, window.devicePixelRatio || 1);
    const viewport = sheet.getViewport({
      scale: Math.max(0.2, width / natural.width),
    });
    const target = canvas.value;
    target.width = Math.floor(viewport.width * scale);
    target.height = Math.floor(viewport.height * scale);
    target.style.width = `${viewport.width}px`;
    target.style.height = `${viewport.height}px`;
    rendering = sheet.render({
      canvas: target,
      canvasContext: target.getContext("2d")!,
      viewport,
      transform: [scale, 0, 0, scale, 0, 0],
    });
    await rendering.promise;
  } catch (error) {
    if (
      !disposed &&
      run === revision &&
      (error as Error).name !== "RenderingCancelledException"
    )
      emit("error", "PDF 无法解析，可能已损坏或需要密码，请下载查看");
  } finally {
    if (run === revision) busy.value = false;
  }
}
let resize: ResizeObserver | undefined;
onMounted(async () => {
  try {
    const data = await props.blob.arrayBuffer();
    if (disposed) return;
    task = getDocument({
      data,
      maxImageSize: 16_000_000,
      cMapUrl: `${import.meta.env.BASE_URL}pdfjs/cmaps/`,
      cMapPacked: true,
      standardFontDataUrl: `${import.meta.env.BASE_URL}pdfjs/standard_fonts/`,
      wasmUrl: `${import.meta.env.BASE_URL}pdfjs/wasm/`,
      enableXfa: false,
    });
    task.onPassword = () => {
      emit("error", "此 PDF 需要密码，请下载后打开");
      void task?.destroy();
    };
    document = await task.promise;
    if (disposed) return;
    pages.value = document.numPages;
    await draw();
    if (disposed || !canvas.value?.parentElement) return;
    resize = new ResizeObserver(() => {
      void draw();
    });
    resize.observe(canvas.value.parentElement);
  } catch {
    if (!disposed) emit("error", "PDF 无法解析，请下载查看");
  }
});
watch(page, () => {
  void draw();
});
onBeforeUnmount(() => {
  disposed = true;
  ++revision;
  resize?.disconnect();
  rendering?.cancel();
  void task?.destroy();
});
</script>
<template>
  <div class="preview-pdf">
    <div class="preview-pdf-pages">
      <button
        :disabled="page <= 1 || busy"
        @click="page--"
        aria-label="PDF 上一页"
      >
        上一页
      </button>
      <span aria-live="polite">{{ page }} / {{ pages || "…" }}</span>
      <button
        :disabled="page >= pages || busy"
        @click="page++"
        aria-label="PDF 下一页"
      >
        下一页
      </button>
    </div>
    <div class="preview-pdf-sheet" :aria-busy="busy">
      <canvas ref="canvas" :aria-label="`PDF 第 ${page} 页`" role="img" />
    </div>
  </div>
</template>
