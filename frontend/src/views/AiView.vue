<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowUp, ChevronDown, History, MessageCircle, Plus, Search, Sparkles, Square, X } from "@lucide/vue";
import AiMessage from "../components/AiMessage.vue";
import ClawPanel from "../components/ClawPanel.vue";
import AiWelcome from "../components/AiWelcome.vue";
import { chatContext, getChatStatus, streamChat, type ChatMessage, type ChatStatus } from "../api/aiChat";

const router = useRouter();
const route = useRoute();
const mode = computed(() => route.query.mode === "search" ? "search" : "chat");
const modeMenu = ref(false);
const historyOpen = ref(false);
const status = ref<ChatStatus | null>(null);
const statusLoading = ref(false);
const statusError = ref("");
const error = ref("");
const draft = ref("");
const composing = ref(false);
const pending = ref(false);
const scroller = ref<HTMLElement>();
const input = ref<HTMLTextAreaElement>();
const claw = ref<InstanceType<typeof ClawPanel>>();
interface ConversationMessage extends ChatMessage { interrupted?: boolean }
interface Conversation { id: number; title: string; messages: ConversationMessage[]; draft: string }
const conversations = ref<Conversation[]>([]);
let nextId = 1;
const current = ref<Conversation>({ id: nextId++, title: "新对话", messages: [], draft: "" });
let request: AbortController | undefined;
let statusRequest: AbortController | undefined;
let rollback: (() => void) | undefined;
let retainPartial: (() => boolean) | undefined;
const suggestions = ["帮我写一份清晰的工作周报", "用简单的语言解释什么是人工智能", "帮我规划高效的一天"];
const canSend = computed(() => !!draft.value.trim() && draft.value.length <= 8000 && status.value?.configured && !pending.value);
const saved = computed(() => conversations.value.filter(c => c.messages.length));

async function loadStatus() {
  statusRequest?.abort();
  const controller = new AbortController();
  statusRequest = controller;
  statusLoading.value = true;
  statusError.value = "";
  try {
    const value = await getChatStatus(controller.signal);
    if (!controller.signal.aborted) status.value = value;
  } catch {
    if (!controller.signal.aborted) statusError.value = "暂时无法连接智能对话，请重试。";
  } finally {
    if (statusRequest === controller) statusLoading.value = false;
  }
}
async function scrollBottom(behavior: ScrollBehavior = "smooth") {
  await nextTick();
  scroller.value?.scrollTo?.({ top: scroller.value.scrollHeight, behavior });
}
function saveConversation(conversation: Conversation) {
  conversation.title = conversation.messages[0]!.content.slice(0, 28);
  if (!conversations.value.some(c => c.id === conversation.id)) conversations.value.unshift(conversation);
}
function cancel(keepPartial = false) {
  request?.abort();
  request = undefined;
  if (!keepPartial || !retainPartial?.()) rollback?.();
  rollback = undefined; retainPartial = undefined;
  pending.value = false;
}
async function send() {
  if (!canSend.value || mode.value !== "chat") return;
  const question = draft.value.trim();
  const conversation = current.value;
  const messages = chatContext(conversation.messages, question);
  const controller = new AbortController();
  request = controller;
  pending.value = true;
  error.value = "";
  draft.value = "";
  const start = conversation.messages.length;
  conversation.messages.push({ role: "user", content: question });
  rollback = () => { conversation.messages.splice(start); draft.value = question; };
  retainPartial = () => {
    const answer = conversation.messages[start + 1];
    if (!answer?.content.trim()) return false;
    answer.interrupted = true; saveConversation(conversation); return true;
  };
  const updateAnswer = (content: string) => {
    if (request !== controller || controller.signal.aborted) return;
    const node = scroller.value;
    const follow = !node || node.scrollHeight - node.scrollTop - node.clientHeight < 80;
    const answer = conversation.messages[start + 1];
    if (answer) answer.content = content;
    else conversation.messages.push({ role: "assistant", content });
    if (follow) void scrollBottom("auto");
  };
  void scrollBottom();
  try {
    const content = await streamChat(messages, controller.signal, updateAnswer);
    if (request !== controller || controller.signal.aborted) return;
    updateAnswer(content); saveConversation(conversation);
    rollback = undefined; retainPartial = undefined;
  } catch (cause) {
    if (request !== controller || controller.signal.aborted) return;
    rollback?.();
    rollback = undefined; retainPartial = undefined;
    const failure = cause as { code?: string; response?: { data?: { code?: string } } };
    const code = failure.code ?? failure.response?.data?.code;
    error.value = code === "AI_RATE_LIMITED" ? "模型服务繁忙，请稍后重试。" : code === "AI_NOT_CONFIGURED" ? "智能对话尚未配置，请联系管理员。" : "回答失败或超时，请重试。你的问题已保留。";
  } finally {
    if (request === controller) { pending.value = false; request = undefined; }
  }
}
function newConversation() {
  cancel();
  current.value.draft = draft.value;
  current.value = { id: nextId++, title: "新对话", messages: [], draft: "" };
  draft.value = ""; error.value = ""; historyOpen.value = false;
  void nextTick(() => input.value?.focus());
}
function selectConversation(conversation: Conversation) {
  cancel(); current.value.draft = draft.value;
  current.value = conversation; draft.value = conversation.draft;
  error.value = ""; historyOpen.value = false; void scrollBottom();
}
function selectMode(value: "chat" | "search") {
  modeMenu.value = false;
  void router.replace({ name: "ai", query: value === "search" ? { mode: "search" } : {} });
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing && !composing.value) {
    event.preventDefault(); void send();
  }
}
watch(mode, () => { cancel(); error.value = ""; historyOpen.value = false; modeMenu.value = false; });
onMounted(loadStatus);
onBeforeUnmount(() => { cancel(); statusRequest?.abort(); });
</script>

<template>
  <main class="ai-page" @keydown.esc="modeMenu = false; historyOpen = false">
    <header class="ai-header">
      <button class="icon-button" aria-label="返回网盘" @click="router.push({ name: 'home' })"><X :size="23" /></button>
      <div class="mode-control">
        <button class="mode-trigger" aria-label="切换AI模式" :aria-expanded="modeMenu" aria-controls="ai-mode-menu" @click="modeMenu = !modeMenu">
          <strong>{{ mode === 'chat' ? '扣扣AI' : '扣扣Claw' }}</strong><ChevronDown :size="18" :class="{ rotated: modeMenu }" />
          <span>{{ mode === 'chat' ? '智能对话' : '根据网盘文件内容搜索文件' }}</span>
        </button>
        <div v-if="modeMenu" id="ai-mode-menu" class="mode-menu">
          <button :aria-pressed="mode === 'chat'" @click="selectMode('chat')"><MessageCircle :size="20" /><span><b>智能对话</b><small>扣扣AI · 灵感与解答</small></span><i v-if="mode === 'chat'" /></button>
          <button :aria-pressed="mode === 'search'" @click="selectMode('search')"><Search :size="20" /><span><b>扣扣Claw</b><small>根据网盘文件内容搜索文件</small></span><i v-if="mode === 'search'" /></button>
        </div>
      </div>
      <div class="header-actions">
        <button class="icon-button" aria-label="对话历史" :aria-expanded="mode === 'chat' ? historyOpen : claw?.historyOpen" :aria-controls="mode === 'chat' ? 'ai-history' : 'claw-history'" @click="mode === 'chat' ? historyOpen = !historyOpen : claw?.toggleHistory()"><History :size="22" /></button>
        <button class="icon-button" aria-label="新建对话" @click="mode === 'chat' ? newConversation() : claw?.newConversation()"><Plus :size="24" /></button>
      </div>
    </header>

    <aside v-if="mode === 'chat' && historyOpen" id="ai-history" class="history-panel" aria-label="对话历史列表">
      <div><h2>对话历史</h2><button class="icon-button" aria-label="关闭对话历史" @click="historyOpen = false"><X :size="18" /></button></div>
      <p>仅保留在当前页面，离开或刷新后清空。</p>
      <p v-if="!saved.length" class="empty-history">还没有对话，开始聊聊吧。</p>
      <button v-for="conversation in saved" :key="conversation.id" class="history-item" :aria-pressed="current.id === conversation.id" @click="selectConversation(conversation)"><MessageCircle :size="18" /><span>{{ conversation.title }}</span></button>
    </aside>

    <KeepAlive><ClawPanel v-if="mode === 'search'" ref="claw" /></KeepAlive>
    <div v-if="mode === 'chat'" ref="scroller" class="ai-scroll">
        <AiWelcome v-if="!current.messages.length" name="扣扣AI" description="很高兴遇见你，有什么我可以帮你？" :suggestions="suggestions" @select="value => { draft = value; input?.focus(); }" />
        <section v-else class="conversation" aria-label="智能对话消息" :aria-busy="pending" aria-live="polite">
          <article v-for="(message, index) in current.messages" :key="index" :class="['message', message.role]">
            <span class="message-author">{{ message.role === 'user' ? '你' : '扣扣AI' }}</span>
            <div v-if="message.role === 'user'" class="user-content">{{ message.content }}</div><AiMessage v-else :content="message.content" />
            <p v-if="message.interrupted" class="interrupted-note">回答已停止，内容未完成。</p>
          </article>
          <div v-if="pending" class="thinking" role="status"><span /><span /><span />{{ current.messages.at(-1)?.role === 'assistant' ? '扣扣AI正在回答…' : '扣扣AI正在思考…' }}</div>
        </section>
    </div>

    <footer v-if="mode === 'chat'" class="composer-wrap">
      <p v-if="statusLoading" class="service-notice" role="status">正在连接智能对话…</p>
      <p v-else-if="statusError" class="service-notice" role="alert">{{ statusError }} <button @click="loadStatus">重新连接</button></p>
      <p v-else-if="status && !status.configured" class="service-notice" role="status">智能对话尚未配置，请联系管理员。<button @click="loadStatus">刷新状态</button></p>
      <p v-if="error" class="chat-error" role="alert">{{ error }}<button :disabled="!canSend" @click="send">重试</button></p>
      <form class="composer" @submit.prevent="send">
        <textarea ref="input" v-model="draft" aria-label="输入你的问题" placeholder="有问题，尽管问我…" rows="2" maxlength="8000" :readonly="pending" @keydown="onKeydown" @compositionstart="composing = true" @compositionend="composing = false" />
        <div class="composer-tools"><span><Sparkles :size="15" />智能对话</span>
          <button v-if="pending" type="button" class="send-button" aria-label="停止回答" @click="cancel(true)"><Square :size="16" /></button>
          <button v-else type="submit" class="send-button" aria-label="发送问题" :disabled="!canSend"><ArrowUp :size="22" /></button>
        </div>
      </form>
      <p class="composer-hint">内容由 AI 生成，请核实重要信息<span>Enter 发送 · Shift + Enter 换行</span></p>
    </footer>
  </main>
</template>

<style scoped src="../styles/ai.css"></style>
