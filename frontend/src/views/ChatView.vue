<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronLeft, Send } from '@lucide/vue';
import { listRooms, getMessages, sendMessage, type Room, type Message } from '../api/chat';
import { useAuth } from '../stores/auth';
import '../styles/chat.css';
const route=useRoute(); const router=useRouter(); const auth=useAuth(); const id=String(route.params.id);
const room=ref<Room>(); const messages=ref<Message[]>([]); const content=ref(''); const error=ref(''); const busy=ref(false); const list=ref<HTMLElement>();
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
onUnmounted(()=>{ stopped=true; clearTimeout(timer); });
</script>
<template><main class="chat-page conversation-page"><header class="chat-header"><button aria-label="返回共享" @click="router.push({path:'/',query:{tab:'share'}})"><ChevronLeft/></button><h1>{{ room?.name || '聊天' }}</h1><span></span></header><p v-if="room?.group" class="group-number">群号：{{ room.id }}<br/>{{ room.description }}</p><p v-if="error" role="alert" class="chat-error">{{ error }}</p><section ref="list" class="chat-history" aria-label="聊天消息"><p v-if="!messages.length" class="chat-empty">还没有消息，打个招呼吧</p><article v-for="message in messages" :key="message.id" class="chat-message" :class="{mine:String(message.senderId)===auth.user.value?.id}"><small>{{ message.senderName }} · {{ new Date(message.createdAt).toLocaleTimeString() }}</small><p>{{ message.content }}</p></article></section><form class="chat-compose" @submit.prevent="send"><textarea v-model="content" aria-label="消息内容" placeholder="输入消息…" maxlength="2000" rows="2"/><button class="chat-primary" :disabled="busy || !content.trim()" aria-label="发送消息"><Send/>{{ busy ? '发送中' : '发送' }}</button></form></main></template>
