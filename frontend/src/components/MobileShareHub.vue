<script setup lang="ts">
import { Bell, Cloud, UserRound, Folder, Mail, MoreHorizontal, Search, UserRoundPlus, UsersRound } from '@lucide/vue';
import '../styles/share-hub.css';
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { listRooms, type Room } from '../api/chat';
const router=useRouter();
const rooms=ref<Room[]>([]); const chatError=ref('');
onMounted(async()=>{ try { rooms.value=await listRooms(); } catch { chatError.value='聊天列表加载失败'; } });
function openShortcut(label:string) {
  if(label==='新建群聊') void router.push('/groups/new');
  if(label==='加好友/群' || label==='通讯录') void router.push('/friends');
}

const shortcuts = [
  { label: '新建群聊', icon: UsersRound },
  { label: '加好友/群', icon: UserRoundPlus },
  { label: '转存和订阅', icon: Cloud },
  { label: '通讯录', icon: UserRound },
];
const messages = [
  { icon: 'bell', title: '暂无消息通知', subtitle: '系统通知', color: 'mint' },
  { icon: 'mail', title: '【提醒】您的特权券将于明日…', subtitle: '官方消息 丨 会员专属助手', color: 'blue', date: '06-06 10:49', unread: '89' },
  { icon: 'smile', title: '网盘元气圈', subtitle: '居然还有情侣经纪人这种职业?', color: 'gold', date: '2025-02-05', unread: '9' },
  { icon: 'bookmark', title: '订阅分享管理', subtitle: '更新提醒', color: 'orange', date: '2025-10-18', unread: '1', service: true },
  { icon: 'folder', title: '给朋友分享文件', subtitle: '立即体验全新千度网盘', color: 'cream', date: '2025-02-04' },
];
</script>

<template>
  <main class="mobile-share-hub" aria-label="共享">
    <header class="share-hub-header">
      <div class="share-hub-tabs" aria-label="共享分类">
        <button type="button" class="selected" aria-current="page">消息</button>
        <button type="button">聊天文件</button>
        <button type="button">文件共享</button>
      </div>
      <button type="button" class="share-hub-icon" aria-label="搜索消息" @click="router.push('/friends')"><Search /></button>
      <button type="button" class="share-hub-icon" aria-label="更多选项"><MoreHorizontal /></button>
    </header>
    <section class="share-hub-shortcuts" aria-label="快捷入口">
      <button v-for="shortcut in shortcuts" :key="shortcut.label" type="button" @click="openShortcut(shortcut.label)">
        <svg v-if="shortcut.label === '转存和订阅'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M6 20h12a5 5 0 0 0 1-9.9A7 7 0 0 0 5 10a5 5 0 0 0 1 10Z"/><path d="M9 13h6m-3-3 3 3-3 3"/></svg>
        <svg v-else-if="shortcut.label === '通讯录'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="6" r="5"/><path d="M13 12H9a6 6 0 0 0-6 7v2h8m5-5h6m-6 5h6"/></svg>
        <component v-else :is="shortcut.icon" :stroke-width="1.8" />
        <span>{{ shortcut.label }}</span>
      </button>
    </section>
    <section class="share-hub-messages" aria-label="消息列表">
      <p v-if="chatError" role="alert">{{ chatError }}</p>
      <button v-for="room in rooms" :key="room.id" type="button" class="share-hub-message" @click="router.push(`/chat/${room.id}`)">
        <span class="share-message-avatar blue"><UsersRound v-if="room.group"/><UserRound v-else/></span>
        <span class="share-message-copy"><span class="share-message-title"><strong>{{ room.name }}</strong></span><span class="share-message-subtitle">{{ room.group ? '群聊' : '好友聊天' }}{{ room.description ? ' · '+room.description : '' }}</span></span>
      </button>
      <button v-for="message in messages" :key="message.title" type="button" class="share-hub-message" :class="{ separated: message.service }">
        <span class="share-message-avatar" :class="message.color" aria-hidden="true">
          <Bell v-if="message.icon === 'bell'" class="filled-bell" />
          <Mail v-else-if="message.icon === 'mail'" class="filled-mail" />
          <svg v-else-if="message.icon === 'smile'" viewBox="0 0 40 40" fill="none"><path d="M20 7a14 14 0 1 0 14 14M13 21c1 7 11 9 15 1" stroke="white" stroke-width="3.4" stroke-linecap="round"/><circle cx="29" cy="11" r="3.5" stroke="white" stroke-width="3.4"/></svg>
          <svg v-else-if="message.icon === 'bookmark'" viewBox="0 0 40 40" fill="none"><path d="M11 9a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v24l-9-4-9 4Z" fill="white"/><path d="M20 14v8m-4-4h8" stroke="#f4822b" stroke-width="2.6" stroke-linecap="round"/></svg>
          <span v-else class="share-folder-icon"><Folder /><svg viewBox="0 0 24 24"><circle cx="12" cy="9" r="3"/><path d="M6 19v-2a6 6 0 0 1 12 0v2Z"/></svg></span>
        </span>
        <span class="share-message-copy">
          <span class="share-message-title"><strong>{{ message.title }}</strong><span v-if="message.service" class="share-service-tag">服务号</span></span>
          <span class="share-message-subtitle">{{ message.subtitle }}</span>
        </span>
        <span v-if="message.date" class="share-message-meta"><time>{{ message.date }}</time><span v-if="message.unread" class="share-unread">{{ message.unread }}</span></span>
      </button>
      <p class="share-hub-end"><span></span>已经到底啦<span></span></p>
    </section>
  </main>
</template>
