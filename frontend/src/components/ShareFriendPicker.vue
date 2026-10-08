<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import { Check, Search, UserRound, LoaderCircle } from "@lucide/vue";
import { listRooms, searchUsers, openDirect, sendFileMessage, type Room, type Person } from "../api/chat";
import { driveErrorMessage } from "../api/drive";
import { formatBytes, type DriveItem } from "../stores/drive";
import { iconForFile } from "./fileIcon";
const props = defineProps<{ file: DriveItem }>();
const emit = defineEmits<{ sent: [name: string]; busy: [value: boolean] }>();
type Recipient = { kind: "room"; id: string; name: string } | { kind: "person"; id: number; name: string };
const query = ref(""), rooms = ref<Room[]>([]), people = ref<Person[]>([]);
const selected = ref<Recipient | null>(null), loading = ref(false), sending = ref(false), error = ref("");
let alive = true, revision = 0, debounce: ReturnType<typeof setTimeout> | undefined;
async function load() {
  const request = ++revision;
  loading.value = true; error.value = "";
  try {
    if (query.value.trim()) {
      const result = await searchUsers(query.value.trim());
      if (alive && request === revision) people.value = result;
    } else {
      const result = await listRooms();
      if (alive && request === revision) rooms.value = result.filter(room => !room.group);
    }
  } catch (reason) {
    if (alive && request === revision) error.value = driveErrorMessage(reason, "好友加载失败，请重试");
  } finally { if (alive && request === revision) loading.value = false; }
}
watch(query, () => {
  ++revision; clearTimeout(debounce); selected.value = null; people.value = []; error.value = ""; loading.value = true;
  debounce = setTimeout(() => void load(), 250);
});
async function send() {
  if (!selected.value || sending.value) return;
  const recipient = selected.value;
  sending.value = true; emit("busy", true); error.value = "";
  try {
    const roomId = recipient.kind === "room" ? recipient.id : (await openDirect(recipient.id)).id;
    await sendFileMessage(roomId, props.file.id);
    if (alive) emit("sent", recipient.name);
  } catch (reason) {
    if (alive) error.value = driveErrorMessage(reason, "发送失败，请重试");
  } finally { sending.value = false; emit("busy", false); }
}
onMounted(() => void load());
onUnmounted(() => { alive = false; ++revision; clearTimeout(debounce); });
</script>

<template>
  <div class="share-friends">
    <label class="friend-search"><Search :size="18"/><input v-model="query" aria-label="搜索好友" placeholder="搜索昵称或用户名" :disabled="sending" /></label>
    <p class="friends-caption">{{ query.trim() ? '搜索结果' : '最近联系 · 选择一位好友' }}</p>
    <div v-if="loading" class="friends-state" role="status"><LoaderCircle :size="20" class="spinner"/>正在查找好友…</div>
    <div v-else-if="error" class="friends-state"><p role="alert">{{ error }}</p><button type="button" :disabled="sending" @click="load">重新加载</button></div>
    <div v-else class="friends-list">
      <template v-if="query.trim()">
        <button v-for="person in people" :key="person.id" type="button" :disabled="sending" :aria-pressed="selected?.kind === 'person' && selected.id === person.id" @click="selected = { kind: 'person', id: person.id, name: person.nickname || person.username }">
          <span class="friend-avatar"><UserRound :size="23"/></span><span class="friend-name">{{ person.nickname || person.username }}<small>@{{ person.username }}</small></span><Check v-if="selected?.kind === 'person' && selected.id === person.id" class="friend-check" :size="20"/>
        </button>
        <p v-if="!people.length" class="friends-state">没有找到好友，试试完整用户名</p>
      </template>
      <template v-else>
        <button v-for="room in rooms" :key="room.id" type="button" :disabled="sending" :aria-pressed="selected?.kind === 'room' && selected.id === room.id" @click="selected = { kind: 'room', id: room.id, name: room.name }">
          <span class="friend-avatar"><UserRound :size="23"/></span><span class="friend-name">{{ room.name }}</span><Check v-if="selected?.kind === 'room' && selected.id === room.id" class="friend-check" :size="20"/>
        </button>
        <p v-if="!rooms.length" class="friends-state">暂无最近联系，可搜索昵称或用户名发送</p>
      </template>
    </div>
    <footer class="friend-send"><div class="friend-file"><component :is="iconForFile(file)" :size="27"/><span>{{ file.name }}<small>{{ formatBytes(file.size) }}</small></span></div><button type="button" class="friend-send-button" :disabled="!selected || sending || loading" @click="send">{{ sending ? '发送中…' : '发送' }}</button></footer>
  </div>
</template>

<style scoped>
.share-friends { display: flex; flex-direction: column; min-height: min(500px, 65dvh); padding: 18px 22px 0; }
.friend-search { display: flex; align-items: center; gap: 9px; color: #8e94a1; background: #eceef2; border-radius: 10px; padding: 0 13px; }
.friend-search input { width: 100%; min-width: 0; height: 43px; border: 0; outline: 0; background: transparent; color: #141c2b; font-size: 14px; }
.friend-search:focus-within { outline: 2px solid #2686ff; }
.friends-caption { margin: 20px 0 9px; color: #8b91a0; font-size: 12px; }
.friends-list { flex: 1; overflow-y: auto; max-height: 42dvh; }
.friends-list > button { width: 100%; display: flex; gap: 12px; align-items: center; padding: 13px 0; border: 0; border-bottom: 1px solid #eceef3; background: none; text-align: left; }
.friend-avatar { width: 42px; height: 42px; display: grid; place-items: center; color: #5986b9; background: #e5eef9; border-radius: 50%; }
.friend-name { flex: 1; color: #121b2b; overflow: hidden; text-overflow: ellipsis; }
.friend-name small, .friend-file small { display: block; font-size: 11px; color: #9095a2; margin-top: 4px; }
.friend-check { color: #2788ff; }
.friends-state { flex: 1; padding: 32px 0; text-align: center; color: #878d9a; font-size: 13px; }
.friends-state button { border: 0; color: #2788ff; background: none; padding: 12px; }
.friend-send { display: flex; align-items: center; gap: 15px; margin-top: auto; padding: 18px 0 max(20px, env(safe-area-inset-bottom)); }
.friend-file { flex: 1; min-width: 0; display: flex; align-items: center; gap: 9px; color: #4792dd; }
.friend-file span { color: #20293a; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.friend-send-button { min-width: 80px; height: 40px; padding: 0 15px; border: 0; border-radius: 8px; background: #2788ff; color: #fff; }
.friend-send-button:disabled { background: #d6dbe4; color: #fff; }
.spinner { animation: spin 1s linear infinite; vertical-align: middle; margin-right: 5px; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinner { animation: none; } }
button:focus-visible { outline: 2px solid #2788ff; outline-offset: 2px; }
</style>
