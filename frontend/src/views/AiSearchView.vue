<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import AiSearchPanel from "../components/AiSearchPanel.vue";
import FilePreview from "../components/FilePreview.vue";
import { searchAiFiles } from "../api/aiSearch";
import type { AiSearchHit } from "../api/aiSearchTypes";
import { getFileDetails, type DriveItemResponse } from "../api/drive";

const router = useRouter();
const previewTarget = ref<DriveItemResponse | null>(null);
const openError = ref("");

async function openFile(hit: AiSearchHit) {
  openError.value = "";
  try {
    const { file } = await getFileDetails(hit.fileId);
    if (file.kind !== "file") throw new Error("不是文件");
    previewTarget.value = file;
  } catch {
    openError.value = "文件已不可用，请刷新搜索结果后重试。";
  }
}
</script>

<template>
  <AiSearchPanel :search="searchAiFiles" @back="router.push({ name: 'home' })" @open="openFile" />
  <FilePreview v-if="previewTarget" :file="previewTarget" @close="previewTarget = null" />
  <div v-if="openError" class="ai-open-error" role="alert">
    {{ openError }}<button type="button" @click="openError = ''">关闭</button>
  </div>
</template>

<style scoped>
.ai-open-error { position: fixed; z-index: 20; right: 20px; bottom: 20px; display: flex; align-items: center; gap: 16px; max-width: calc(100vw - 40px); padding: 14px 18px; background: #17243b; color: #fff; border-radius: 10px; box-shadow: 0 8px 28px #17243b33; }
.ai-open-error button { border: 0; background: transparent; color: #a8d0ff; }
</style>
