<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ChevronLeft, Search, ScanLine, ContactRound, Tag, ChevronRight } from 'lucide-vue-next';
import { searchUsers, searchGroups, openDirect, joinGroup, type Person, type Room } from '../api/chat';
import { useAuth } from '../stores/auth';
import '../styles/chat.css';
import '../styles/chat-entry.css';
const router = useRouter();
const auth = useAuth();
const query = ref(''); const users = ref<Person[]>([]); const groups = ref<Room[]>([]);
const busy = ref(false); const actionBusy = ref(false); const searched = ref(false); const error = ref(''); const notice = ref('');
async function search() {
  if (busy.value) return;
  error.value=''; users.value=[]; groups.value=[]; searched.value=false;
  if (!query.value.trim()) return;
  busy.value=true;
  try { [users.value, groups.value] = await Promise.all([searchUsers(query.value),searchGroups(query.value)]); searched.value=true; }
  catch { error.value='搜索失败，请检查网络后重试'; }
  finally { busy.value=false; }
}
async function chat(user: Person) {
  if(actionBusy.value) return; actionBusy.value=true; error.value='';
  try { const room=await openDirect(user.id); await router.push(`/chat/${room.id}`); }
  catch { error.value='无法开始聊天，请重试'; } finally { actionBusy.value=false; }
}
async function join(room: Room) {
  if(actionBusy.value) return; actionBusy.value=true; error.value='';
  try { await joinGroup(room.id); await router.push(`/chat/${room.id}`); }
  catch { error.value='加入群聊失败，请重试'; } finally { actionBusy.value=false; }
}
const entries = [{label:'扫一扫加好友/群',icon:ScanLine},{label:'添加通讯录好友',icon:ContactRound},{label:'通过标签查找陌生人',icon:Tag}];
</script>
<template>
  <main class="chat-page discovery-page">
    <header class="chat-header"><button aria-label="返回共享" @click="router.push({path:'/',query:{tab:'share'}})"><ChevronLeft /></button><h1>加好友/群</h1><span></span></header>
    <form class="chat-search" @submit.prevent="search"><Search /><input v-model="query" aria-label="搜索用户ID、用户名或群号" placeholder="搜 / 用户 / 群 / 标签" maxlength="64" /><button v-if="query.trim() || busy" :disabled="busy" type="submit">{{ busy ? '搜索中' : '搜索' }}</button></form>
    <section class="discovery-cards"><button @click="notice='我的二维码仅作展示，暂未开放'"><svg class="qr-art" viewBox="0 0 28 28" aria-hidden="true"><defs><linearGradient id="entry-qr" x2="1" y2="1"><stop stop-color="#39beff"/><stop offset="1" stop-color="#2288ff"/></linearGradient></defs><rect x="1" y="1" width="12" height="12" rx="4" fill="url(#entry-qr)"/><rect x="16" y="1" width="12" height="12" rx="4" fill="#20cafa"/><path d="M16 10V5a4 4 0 0 1 4-4h4Z" fill="#2988ff"/><rect x="1" y="16" width="12" height="12" rx="4" fill="#2988ff"/><path d="M18 18v8m4-6v5m4-7v8" stroke="#00b9ff" stroke-width="3" stroke-linecap="round"/></svg><strong>我的二维码</strong><span>扫一扫，加我好友</span></button><button @click="notice='我的口令仅作展示，暂未开放'"><svg class="key-art" viewBox="0 0 28 28" aria-hidden="true"><defs><linearGradient id="entry-key" x2="1" y2="1"><stop stop-color="#ffda7a"/><stop offset="1" stop-color="#ffad32"/></linearGradient></defs><path d="m16 12 9-9m-3 3 3 3" fill="none" stroke="#ffa32a" stroke-width="5" stroke-linecap="round"/><circle cx="11" cy="18" r="10" fill="url(#entry-key)"/><path d="M20 15a10 10 0 0 1-10 13c1-6 5-10 10-13" fill="#ff9f20"/></svg><strong>我的口令</strong><span>粘贴到微信、QQ 加好友</span></button></section>
    <p v-if="notice" class="chat-notice" role="status">{{ notice }}</p><p v-if="error" class="chat-error" role="alert">{{ error }}</p>
    <section v-if="searched" class="search-results" aria-label="搜索结果"><h2>搜索结果</h2><p v-if="!users.length && !groups.length">没有找到用户或群聊，试试用户 ID、用户名或完整群号</p>
      <div v-for="user in users" :key="user.id" class="chat-result"><span class="chat-avatar">{{ user.nickname.slice(0,1) }}</span><div><strong>{{ user.nickname }}</strong><small>{{ user.username }} · ID {{ user.id }}</small></div><button :disabled="actionBusy || String(user.id)===auth.user.value?.id" @click="chat(user)">{{ String(user.id)===auth.user.value?.id ? '我自己' : '加好友 / 聊天' }}</button></div>
      <div v-for="room in groups" :key="room.id" class="chat-result"><span class="chat-avatar">群</span><div><strong>{{ room.name }}</strong><small>{{ room.description }}</small></div><button :disabled="actionBusy" @click="join(room)">加入群聊</button></div>
    </section>
    <section class="discovery-entries"><button v-for="entry in entries" :key="entry.label" @click="notice=entry.label+'仅作展示，暂未开放'"><component :is="entry.icon"/><span>{{ entry.label }}</span><ChevronRight /></button></section>
  </main>
</template>
