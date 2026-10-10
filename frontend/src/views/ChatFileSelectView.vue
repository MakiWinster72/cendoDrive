<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ChevronLeft, Send } from "@lucide/vue";
import CloudFileBrowser from "../components/CloudFileBrowser.vue";
import ChatFileGlyph from "../components/ChatFileGlyph.vue";
import { sendFileMessage } from "../api/chat";
import { driveErrorMessage, type DriveItemResponse } from "../api/drive";
import { formatSize } from "../stores/drive";
import "../styles/chat-files.css";
const route = useRoute(),
  router = useRouter(),
  room = String(route.params.id);
const selected = ref<DriveItemResponse | null>(null),
  busy = ref(false),
  error = ref("");
async function send() {
  if (!selected.value || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await sendFileMessage(room, selected.value.id);
    await router.push(`/chat/${room}`);
  } catch (cause) {
    error.value = driveErrorMessage(cause, "发送失败，文件已保留，请重试");
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="chat-files-page">
    <header class="chat-files-header">
      <button
        aria-label="返回聊天"
        :disabled="busy"
        @click="router.push(`/chat/${room}`)"
      >
        <ChevronLeft />
      </button>
      <h1>选择云盘文件</h1>
      <span>发送文件</span>
    </header>
    <p class="chat-files-intro">从我的云盘选择一个文件，发送到当前聊天</p>
    <CloudFileBrowser :disabled="busy" @select="selected = $event" />
    <footer class="chat-files-footer">
      <p v-if="error" class="chat-files-error" role="alert">{{ error }}</p>
      <div class="chat-files-confirm">
        <div class="chat-files-selection">
          <ChatFileGlyph v-if="selected" :name="selected.name" /><span
            ><strong>{{ selected?.name || "尚未选择文件" }}</strong
            ><small>{{
              selected
                ? formatSize(selected.size)
                : "进入文件夹后，点击文件选择"
            }}</small></span
          >
        </div>
        <button
          class="chat-files-primary"
          :disabled="!selected || busy"
          @click="send"
        >
          <Send :size="17" />{{ busy ? "发送中…" : "发送" }}
        </button>
      </div>
    </footer>
  </main>
</template>
