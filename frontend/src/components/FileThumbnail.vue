<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { DriveItemResponse } from "../api/drive";
import { getToken } from "../stores/auth";
import {
  cachedThumbnail,
  loadThumbnail,
  forgetThumbnail,
  thumbnailKey,
  type Thumbnail,
} from "../preview/thumbnails";
import { iconForFile } from "./fileIcon";
const props = withDefaults(
  defineProps<{ file: DriveItemResponse; size?: number }>(),
  { size: 40 },
);
const root = ref<HTMLElement>();
const thumbnail = ref<Thumbnail | null>(cachedThumbnail(props.file));
const image = computed(() => thumbnail.value?.url ?? "");
const html = computed(() => thumbnail.value?.html ?? "");
const ready = computed(() => !!thumbnail.value);
let observer: IntersectionObserver | undefined;
let revision = 0;
let visible = false;
async function load() {
  const run = ++revision;
  const result = await loadThumbnail(props.file);
  if (run === revision) thumbnail.value = result;
}
function failedImage() {
  forgetThumbnail(props.file);
  thumbnail.value = null;
}
onMounted(() => {
  if (!root.value || typeof IntersectionObserver === "undefined") return;
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        visible = true;
        observer?.disconnect();
        if (!thumbnail.value) void load();
      }
    },
    { rootMargin: "100px" },
  );
  observer.observe(root.value);
});
watch(
  () => thumbnailKey(props.file),
  () => {
    ++revision;
    thumbnail.value = cachedThumbnail(props.file);
    if (visible && !thumbnail.value) void load();
  },
);
watch(
  getToken,
  (token) => {
    ++revision;
    thumbnail.value = null;
    if (token && visible) void load();
  },
  { flush: "sync" },
);
onBeforeUnmount(() => {
  observer?.disconnect();
  ++revision;
  // The shared request and cached result outlive this view.
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
    <img v-if="image" :src="image" alt="" @error="failedImage()" />
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
.file-thumbnail > img {
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
