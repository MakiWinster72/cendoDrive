<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ChevronLeft, ChevronRight, Eye, Download, FolderInput, ShieldCheck, FileQuestion, RefreshCw } from '@lucide/vue';
import ChatFileGlyph from '../components/ChatFileGlyph.vue';
import FilePreview from '../components/FilePreview.vue';
import { useChatFile } from './useChatFile';
import { downloadChatFile, previewChatFile } from '../api/chat';
import { driveErrorMessage } from '../api/drive';
import { formatSize } from '../stores/drive';
import { previewFormat } from '../preview/formats';
import '../styles/chat-files.css';
const router=useRouter(), {room,messageId,file,loading,error,load}=useChatFile();
const preview=ref(false), unsupported=ref(false), downloading=ref(false), notice=ref('');
const format=computed(()=>file.value ? previewFormat(file.value.name) : null);
function openPreview() { if(!file.value) return; unsupported.value=!format.value; preview.value=!!format.value; }
async function download() {
  if(!file.value || downloading.value) return;
  downloading.value=true; notice.value='';
  try { await downloadChatFile(room.value,messageId.value,file.value.name); notice.value='下载已开始，请在浏览器下载列表查看'; }
  catch(cause) { error.value=driveErrorMessage(cause,'下载失败，请重试'); }
  finally { downloading.value=false; }
}
async function fetchContent(signal: AbortSignal) {
  if(!file.value) throw new Error('文件不可用');
  return previewChatFile(room.value,messageId.value,file.value,signal);
}
</script>
<template><main class="chat-files-page chat-file-detail"><header class="chat-files-header"><button aria-label="返回聊天" @click="router.push(`/chat/${room}`)"><ChevronLeft/></button><h1>文件操作</h1><span>聊天文件</span></header><div v-if="loading" class="cloud-state" role="status"><span class="cloud-spinner"></span><p>正在加载文件…</p></div><template v-else-if="file"><section class="chat-file-hero"><ChatFileGlyph :name="file.name"/><h2>{{ file.name }}</h2><p>{{ formatSize(file.size) }} <span>·</span> {{ file.name.includes('.') ? file.name.split('.').pop()?.toUpperCase() : '文件' }}</p><small>来自聊天 · 原文件</small></section><section class="chat-file-menu" aria-label="文件操作"><button @click="openPreview"><span class="chat-action-icon"><Eye/></span><span><strong>预览文件</strong><small>{{ format ? '在线查看，无需转存' : '查看此格式的预览支持情况' }}</small></span><ChevronRight :size="18"/></button><button :disabled="downloading" @click="download"><span class="chat-action-icon"><Download/></span><span><strong>{{ downloading ? '正在下载…' : '下载文件' }}</strong><small>保存到当前设备</small></span><ChevronRight :size="18"/></button><button @click="router.push(`/chat/${room}/files/${messageId}/save`)"><span class="chat-action-icon"><FolderInput/></span><span><strong>转存到我的云盘</strong><small>选择文件夹，保存独立副本</small></span><ChevronRight :size="18"/></button></section><section v-if="unsupported" class="chat-preview-unsupported" role="status"><FileQuestion :size="32"/><div><strong>不支持预览此文件</strong><p>可以下载到设备查看，或转存到我的云盘。</p></div></section><p class="chat-file-safety"><ShieldCheck :size="15"/>转存后的副本不受发送者删除原文件影响</p></template><div v-else class="cloud-state"><FileQuestion :size="44"/><p>文件暂时不可用</p><button class="cloud-retry" @click="load"><RefreshCw :size="16"/>重新加载</button></div><p v-if="error" class="chat-files-error" role="alert">{{ error }}</p><p v-if="notice" class="chat-file-status" role="status">{{ notice }}</p><FilePreview v-if="preview && file" :file="file" :fetch-content="fetchContent" :download-content="download" @close="preview=false"/></main></template>
