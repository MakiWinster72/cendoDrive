<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ChevronLeft, FolderInput, CheckCircle2, Folder } from '@lucide/vue';
import CloudFileBrowser from '../components/CloudFileBrowser.vue';
import ChatFileGlyph from '../components/ChatFileGlyph.vue';
import { useChatFile } from './useChatFile';
import { saveChatFile } from '../api/chat';
import { driveErrorMessage } from '../api/drive';
import { formatSize } from '../stores/drive';
import { useTransfers } from '../stores/transfers';
import '../styles/chat-files.css';
const router=useRouter(), {room,messageId,file,loading,error,load}=useChatFile();
const transfers=useTransfers();
const parent=ref<string | null>(null), directory=ref('我的云盘'), directoryReady=ref(false), busy=ref(false), saved=ref(false);
watch([room,messageId],()=>{ parent.value=null; directory.value='我的云盘'; directoryReady.value=false; saved.value=false; });
function location(id: string | null, name: string) { parent.value=id; directory.value=name; directoryReady.value=true; }
async function save() {
  if(!file.value || busy.value || saved.value || !directoryReady.value) return;
  busy.value=true; error.value='';
  try { const copy=await saveChatFile(room.value,messageId.value,parent.value); transfers.recordTransfer(copy.name,copy.size); saved.value=true; }
  catch(cause) { error.value=driveErrorMessage(cause,'转存失败，请确认剩余容量和文件状态后重试'); if(file.value) transfers.recordFailure('transfer',file.value.name,file.value.size,error.value); }
  finally { busy.value=false; }
}
</script>
<template><main class="chat-files-page"><header class="chat-files-header"><button aria-label="返回文件操作" :disabled="busy" @click="router.push(`/chat/${room}/files/${messageId}`)"><ChevronLeft/></button><h1>转存到我的云盘</h1><span>选择位置</span></header><div v-if="loading" class="cloud-state" role="status"><span class="cloud-spinner"></span><p>正在加载文件…</p></div><template v-else-if="file"><div v-if="saved" class="chat-save-success" role="status"><CheckCircle2 :size="60"/><h2>转存成功</h2><p>{{ file.name }}</p><small>已保存到「{{ directory }}」，这是你的独立副本</small><button class="chat-files-primary" @click="router.push({path:'/',query:{tab:'files'}})">查看我的云盘</button><button class="chat-files-back" @click="router.push(`/chat/${room}/files/${messageId}`)">返回文件操作</button></div><template v-else><div class="chat-save-source"><ChatFileGlyph :name="file.name"/><span><strong>{{ file.name }}</strong><small>{{ formatSize(file.size) }} · 转存将占用云盘容量</small></span></div><CloudFileBrowser :key="`${room}/${messageId}`" folders-only :disabled="busy" @select="directoryReady=false" @directory="location"/><footer class="chat-files-footer"><p v-if="error" class="chat-files-error" role="alert">{{ error }}</p><div class="chat-files-confirm"><div class="chat-files-selection"><Folder :size="24"/><span><small>转存位置</small><strong>{{ directory }}</strong></span></div><button class="chat-files-primary" :disabled="busy || !directoryReady" @click="save"><FolderInput :size="17"/>{{ busy ? '转存中…' : '转存到此处' }}</button></div></footer></template></template><div v-else class="cloud-state"><p role="alert">{{ error }}</p><button class="cloud-retry" @click="load">重新加载</button></div></main></template>
