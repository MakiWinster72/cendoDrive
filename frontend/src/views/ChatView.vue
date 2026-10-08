<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronLeft, Send, Paperclip, FileText } from '@lucide/vue';
import { listRooms, getMessages, sendMessage, sendFileMessage, saveChatFile, type Room, type Message } from '../api/chat';
import { useAuth } from '../stores/auth';
import { driveErrorMessage, type DriveItemResponse } from '../api/drive';
import { uploadFile, uploadErrorMessage } from '../api/files';
import '../styles/chat.css';
const route=useRoute(); const router=useRouter(); const auth=useAuth(); const id=String(route.params.id);
const room=ref<Room>(); const messages=ref<Message[]>([]); const content=ref(''); const error=ref(''); const busy=ref(false); const list=ref<HTMLElement>();
const fileBusy=ref(false); const fileNotice=ref(''); const saving=ref<number>(); const saved=ref(new Set<number>());
const uploadInput=ref<HTMLInputElement>(); const pendingFile=ref<DriveItemResponse>(); const uploadAbort=new AbortController();
async function choose(file: DriveItemResponse) {
  fileBusy.value=true; error.value='';
  try { await sendFileMessage(id,file.id); pendingFile.value=undefined; fileNotice.value='文件已发送'; await refresh(); }
  catch(e) { error.value=driveErrorMessage(e,'文件发送失败，请重试'); }
  finally { fileBusy.value=false; }
}
async function upload(event: Event) {
  const input=event.target as HTMLInputElement; const file=input.files?.[0]; if(!file) return;
  fileBusy.value=true; error.value=''; fileNotice.value='正在上传文件…';
  try {
    const uploaded=await uploadFile({file,parentId:null,signal:uploadAbort.signal,onProgress:p=>{fileNotice.value=`正在上传 ${p}%`;}});
    pendingFile.value=uploaded; await choose(uploaded);
    if(pendingFile.value) fileNotice.value='已上传到云盘，但消息未发送，可重试';
  } catch(e) { error.value=uploadErrorMessage(e); fileNotice.value=''; }
  finally { fileBusy.value=false; input.value=''; }
}
async function save(message: Message) {
  if(saving.value!==undefined || saved.value.has(message.id)) return;
  saving.value=message.id; error.value='';
  try { await saveChatFile(id,message.id); saved.value.add(message.id); fileNotice.value='已转存到我的云盘根目录'; }
  catch(e) { error.value=driveErrorMessage(e,'转存失败，源文件可能已删除，请重试'); }
  finally { saving.value=undefined; }
}
function size(bytes: number) { return bytes<1024 ? `${bytes} B` : bytes<1048576 ? `${(bytes/1024).toFixed(1)} KB` : `${(bytes/1048576).toFixed(1)} MB`; }

let stopped=false; let timer: ReturnType<typeof setTimeout> | undefined;
async function refresh() {
  try {
    const additions=await getMessages(id,messages.value.at(-1)?.id ?? 0);
    if(stopped) return;
    const nearBottom=!list.value || list.value.scrollHeight-list.value.scrollTop-list.value.clientHeight<100;
    messages.value.push(...additions.filter(m=>!messages.value.some(old=>old.id===m.id))); error.value='';
    if(nearBottom && additions.length) { await nextTick(); list.value?.scrollTo(0,list.value.scrollHeight); }
  } catch { if(!stopped) error.value='消息加载失败，请检查网络或聊天权限'; }
}
async function poll() { await refresh(); if(!stopped) timer=setTimeout(poll,2500); }
async function send() {
  if(busy.value || !content.value.trim()) return;
  busy.value=true;
  try { await sendMessage(id,content.value); content.value=''; await refresh(); await nextTick(); list.value?.scrollTo(0,list.value.scrollHeight); }
  catch { error.value='发送失败，消息已保留，请重试'; } finally { busy.value=false; }
}
onMounted(async()=>{ try { room.value=(await listRooms()).find(r=>r.id===id); } catch { error.value='聊天信息加载失败'; } if(!stopped) void poll(); });
onUnmounted(()=>{ stopped=true; uploadAbort.abort(); clearTimeout(timer); });
</script>
<template><main class="chat-page conversation-page"><header class="chat-header"><button aria-label="返回共享" @click="router.push({path:'/',query:{tab:'share'}})"><ChevronLeft/></button><h1>{{ room?.name || '聊天' }}</h1><span></span></header><p v-if="room?.group" class="group-number">群号：{{ room.id }}<br/>{{ room.description }}</p><p v-if="error" role="alert" class="chat-error">{{ error }}</p><section ref="list" class="chat-history" aria-label="聊天消息"><p v-if="!messages.length" class="chat-empty">还没有消息，打个招呼吧</p><article v-for="message in messages" :key="message.id" class="chat-message" :class="{mine:String(message.senderId)===auth.user.value?.id}"><small>{{ message.senderName }} · {{ new Date(message.createdAt).toLocaleTimeString() }}</small><button v-if="message.attachment" class="chat-file-card" :disabled="saving!==undefined || saved.has(message.id)" @click="save(message)" :aria-label="`转存文件 ${message.attachment.name}`"><FileText/><span><strong>{{ message.attachment.name }}</strong><small>{{ size(message.attachment.size) }}</small><em>{{ saved.has(message.id) ? '已转存' : saving===message.id ? '转存中…' : '点击转存到我的云盘' }}</em></span></button><p v-else>{{ message.content }}</p></article></section><p v-if="fileNotice" role="status" class="chat-notice">{{ fileNotice }}</p><div class="chat-file-actions"><button :disabled="fileBusy" @click="router.push(`/chat/${id}/files/select`)"><Paperclip/>云盘文件</button><button :disabled="fileBusy" @click="uploadInput?.click()">本地文件</button><button v-if="pendingFile" :disabled="fileBusy" @click="choose(pendingFile)">重试发送已上传文件</button><input ref="uploadInput" type="file" hidden aria-label="选择本地文件" @change="upload"/></div><form class="chat-compose" @submit.prevent="send"><textarea v-model="content" aria-label="消息内容" placeholder="输入消息…" maxlength="2000" rows="2"/><button class="chat-primary" :disabled="busy || !content.trim()" aria-label="发送消息"><Send/>{{ busy ? '发送中' : '发送' }}</button></form></main></template>
