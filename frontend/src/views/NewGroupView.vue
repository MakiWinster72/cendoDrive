<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ChevronLeft } from '@lucide/vue';
import { createGroup } from '../api/chat';
import '../styles/chat.css';
import '../styles/chat-entry.css';
const router=useRouter(); const name=ref(''); const description=ref(''); const searchable=ref(false); const overflow=ref(true); const busy=ref(false); const error=ref(''); const notice=ref('');
async function create() {
  if(!name.value.trim() || busy.value) return;
  busy.value=true; error.value='';
  try { const room=await createGroup(name.value,description.value,searchable.value); await router.push(`/chat/${room.id}`); }
  catch { error.value='创建群聊失败，请重试'; } finally { busy.value=false; }
}
</script>
<template>
  <main class="chat-page group-page"><form @submit.prevent="create">
    <header class="chat-header"><button type="button" aria-label="返回共享" @click="router.push({path:'/',query:{tab:'share'}})"><ChevronLeft/></button><h1>新建群聊</h1><button class="chat-primary" :disabled="!name.trim() || busy" type="submit">{{ busy ? '创建中' : '创建' }}</button></header>
    <label class="group-label" for="group-name">群名称<span>*</span></label><div class="group-field"><input id="group-name" v-model="name" placeholder="请输入群聊名称" maxlength="20" required/><small>{{ name.length }}/20</small></div>
    <label class="group-label" for="group-description">群简介</label><div class="group-field group-description"><textarea id="group-description" v-model="description" placeholder="简单说说您想在群内讨论的话题，以及希望哪些人加入群聊" maxlength="50"/><small>{{ description.length }}/50</small></div>
    <h2 class="group-label">进群方式</h2><section class="group-options"><label><span>通过搜索群号进群</span><input v-model="searchable" type="checkbox" role="switch"/><i aria-hidden="true"></i></label><label><span>群成员超过上限将自动创建新群</span><input v-model="overflow" type="checkbox" role="switch" @change="notice='自动创建新群仅作展示，不会实际创建'"/><i aria-hidden="true"></i></label></section>
    <p v-if="error" class="chat-error" role="alert">{{ error }}</p><p v-if="notice" class="chat-notice" role="status">{{ notice }}</p>
    <footer class="group-footer">为维护群内信息生态健康，请遵守 <button type="button" @click="notice='请文明交流，勿发布违法、有害或骚扰内容。'">《群聊公约》</button></footer>
  </form></main>
</template>
