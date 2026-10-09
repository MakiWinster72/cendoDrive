<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowUp, ChevronDown, History, MessageCircle, Plus, Search, Sparkles, Square, X } from "@lucide/vue";
import AiMessage from "../components/AiMessage.vue";
import { chatContext, getChatStatus, sendChat, type ChatMessage, type ChatStatus } from "../api/aiChat";

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
interface Conversation { id: number; title: string; messages: ChatMessage[]; draft: string }
const conversations = ref<Conversation[]>([]);
let nextId = 1;
const current = ref<Conversation>({ id: nextId++, title: "新对话", messages: [], draft: "" });
let request: AbortController | undefined;
let statusRequest: AbortController | undefined;
let rollback: (() => void) | undefined;
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
async function scrollBottom() {
  await nextTick();
  scroller.value?.scrollTo?.({ top: scroller.value.scrollHeight, behavior: "smooth" });
}
function cancel() {
  request?.abort();
  request = undefined;
  rollback?.();
  rollback = undefined;
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
  conversation.messages.push({ role: "user", content: question });
  rollback = () => { conversation.messages.pop(); draft.value = question; };
  void scrollBottom();
  try {
    const content = await sendChat(messages, controller.signal);
    if (request !== controller || controller.signal.aborted) return;
    conversation.messages.push({ role: "assistant", content });
    conversation.title = conversation.messages[0]!.content.slice(0, 28);
    if (!conversations.value.some(c => c.id === conversation.id)) conversations.value.unshift(conversation);
    rollback = undefined;
    void scrollBottom();
  } catch (cause) {
    if (request !== controller || controller.signal.aborted) return;
    rollback?.();
    rollback = undefined;
    const code = (cause as { response?: { data?: { code?: string } } }).response?.data?.code;
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
          <strong>{{ mode === 'chat' ? '扣扣AI' : '千度AI' }}</strong><ChevronDown :size="18" :class="{ rotated: modeMenu }" />
          <span>{{ mode === 'chat' ? '智能对话' : '文件智能搜索' }}</span>
        </button>
        <div v-if="modeMenu" id="ai-mode-menu" class="mode-menu">
          <button :aria-pressed="mode === 'chat'" @click="selectMode('chat')"><MessageCircle :size="20" /><span><b>智能对话</b><small>扣扣AI · 灵感与解答</small></span><i v-if="mode === 'chat'" /></button>
          <button :aria-pressed="mode === 'search'" @click="selectMode('search')"><Search :size="20" /><span><b>千度AI</b><small>文件智能搜索 · 即将开放</small></span><i v-if="mode === 'search'" /></button>
        </div>
      </div>
      <div class="header-actions">
        <button class="icon-button" aria-label="对话历史" :disabled="mode !== 'chat'" :aria-expanded="historyOpen" aria-controls="ai-history" @click="historyOpen = !historyOpen"><History :size="22" /></button>
        <button class="icon-button" aria-label="新建对话" :disabled="mode !== 'chat'" @click="newConversation"><Plus :size="24" /></button>
      </div>
    </header>

    <aside v-if="historyOpen" id="ai-history" class="history-panel" aria-label="对话历史列表">
      <div><h2>对话历史</h2><button class="icon-button" aria-label="关闭对话历史" @click="historyOpen = false"><X :size="18" /></button></div>
      <p>仅保留在当前页面，离开或刷新后清空。</p>
      <p v-if="!saved.length" class="empty-history">还没有完成的对话，开始聊聊吧。</p>
      <button v-for="conversation in saved" :key="conversation.id" class="history-item" :aria-pressed="current.id === conversation.id" @click="selectConversation(conversation)"><MessageCircle :size="18" /><span>{{ conversation.title }}</span></button>
    </aside>

    <div ref="scroller" class="ai-scroll">
      <section v-if="mode === 'search'" class="search-intro">
        <span class="feature-tag"><Sparkles :size="15" />千度AI · 即将开放</span>
        <div class="search-emblem"><Search :size="42" /></div>
        <h1>文件那么多，<br />一句话就能找到。</h1>
        <p>不止搜索文件名，更能理解文件里的内容。</p>
        <div class="search-example">“帮我找出提到项目预算的文档”</div>
        <p class="future-note">文件智能搜索正在规划中，当前仅提供模式入口。<br />未来将在上传时索引文件名与内容，经 AI 向量化后存入向量数据库，让提问与文件内容智能匹配。</p>
        <button class="outline-button" @click="selectMode('chat')">先和扣扣AI聊聊 <MessageCircle :size="17" /></button>
      </section>
      <template v-else>
        <section v-if="!current.messages.length" class="welcome">
          <div class="greeting-row">
            <div class="greeting"><h1>Hello<span>!</span></h1><h2>我是扣扣AI</h2><p>很高兴遇见你，有什么我可以帮你？</p></div>
            <div class="robot" aria-hidden="true"><div class="antenna" /><div class="robot-head"><div class="robot-face"><i /><i /></div><div class="robot-smile" /></div><div class="robot-body"><Sparkles :size="21" /></div><div class="robot-shadow" /></div>
          </div>
          <div class="suggestion-heading"><Sparkles :size="16" />从一个小问题开始</div>
          <div class="suggestions"><button v-for="suggestion in suggestions" :key="suggestion" @click="draft = suggestion; input?.focus()">{{ suggestion }}<span>↗</span></button></div>
        </section>
        <section v-else class="conversation" aria-label="智能对话消息" :aria-busy="pending" aria-live="polite">
          <article v-for="(message, index) in current.messages" :key="index" :class="['message', message.role]">
            <span class="message-author">{{ message.role === 'user' ? '你' : '扣扣AI' }}</span>
            <div v-if="message.role === 'user'" class="user-content">{{ message.content }}</div><AiMessage v-else :content="message.content" />
          </article>
          <div v-if="pending" class="thinking" role="status"><span /><span /><span />扣扣AI正在思考…</div>
        </section>
      </template>
    </div>

    <footer v-if="mode === 'chat'" class="composer-wrap">
      <p v-if="statusLoading" class="service-notice" role="status">正在连接智能对话…</p>
      <p v-else-if="statusError" class="service-notice" role="alert">{{ statusError }} <button @click="loadStatus">重新连接</button></p>
      <p v-else-if="status && !status.configured" class="service-notice" role="status">智能对话尚未配置，请联系管理员。<button @click="loadStatus">刷新状态</button></p>
      <p v-if="error" class="chat-error" role="alert">{{ error }}<button :disabled="!canSend" @click="send">重试</button></p>
      <form class="composer" @submit.prevent="send">
        <textarea ref="input" v-model="draft" aria-label="输入你的问题" placeholder="有问题，尽管问我…" rows="2" maxlength="8000" :readonly="pending" @keydown="onKeydown" @compositionstart="composing = true" @compositionend="composing = false" />
        <div class="composer-tools"><span><Sparkles :size="15" />智能对话<span class="model-name" v-if="status?.configured"> · {{ status.model }}</span></span>
          <button v-if="pending" type="button" class="send-button" aria-label="停止回答" @click="cancel"><Square :size="16" /></button>
          <button v-else type="submit" class="send-button" aria-label="发送问题" :disabled="!canSend"><ArrowUp :size="22" /></button>
        </div>
      </form>
      <p class="composer-hint">内容由 AI 生成，请核实重要信息<span>Enter 发送 · Shift + Enter 换行</span></p>
    </footer>
  </main>
</template>

<style scoped>
.ai-page { --ink: #20282f; --muted: #64717c; color: var(--ink); background: #fff; height: 100dvh; min-height: 420px; display: flex; flex-direction: column; position: relative; }
.ai-header { width: min(100%, 1060px); margin: 0 auto; padding: 22px 28px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; }
button { font: inherit; cursor: pointer; }
button:disabled { opacity: .4; cursor: not-allowed; }
button:focus-visible, textarea:focus-visible { outline: 2px solid #009eb4; outline-offset: 4px; }
.icon-button { border: 0; background: transparent; color: var(--ink); width: 40px; height: 40px; display: inline-flex; align-items: center; justify-content: center; border-radius: 12px; }
.icon-button:hover:not(:disabled) { background: #f1f6f7; }
.header-actions { display: flex; gap: 8px; }
.mode-control { position: relative; margin-left: 48px; }
.mode-trigger { border: 0; background: none; display: grid; grid-template-columns: auto auto; align-items: center; gap: 4px 8px; padding: 6px 12px; }
.mode-trigger strong { font-size: 21px; letter-spacing: -.7px; }
.mode-trigger > span { grid-column: 1 / -1; color: var(--muted); font-size: 11px; }
.rotated { transform: rotate(180deg); }
.mode-menu { position: absolute; z-index: 5; width: 260px; left: 50%; transform: translateX(-50%); top: calc(100% + 10px); padding: 7px; border: 1px solid #e8edef; background: #fff; box-shadow: 0 12px 40px #24333c18; border-radius: 16px; }
.mode-menu button { display: flex; gap: 12px; align-items: center; width: 100%; border: 0; background: none; padding: 12px; border-radius: 10px; text-align: left; }
.mode-menu button:hover, .mode-menu button[aria-pressed=true] { background: #effbfc; }
.mode-menu b, .mode-menu small { display: block; } .mode-menu b { font-size: 14px; } .mode-menu small { color: var(--muted); margin-top: 5px; font-size: 11px; }
.mode-menu i { width: 6px; height: 6px; border-radius: 50%; background: #0397ae; margin-left: auto; }
.ai-scroll { overflow-y: auto; flex: 1; min-height: 0; padding: 0 28px; }
.welcome { max-width: 740px; margin: 70px auto 40px; }
.greeting-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.greeting h1 { font-size: clamp(48px, 7vw, 76px); font-weight: 800; line-height: 1.1; letter-spacing: -4px; margin: 0 0 18px; }
.greeting h1 span { color: #009fb7; } .greeting h2 { font-size: 28px; margin: 0 0 12px; letter-spacing: -1px; } .greeting p { color: var(--muted); font-size: 14px; line-height: 1.7; margin: 0; }
.robot { width: 158px; height: 174px; position: relative; flex-shrink: 0; transform: rotate(8deg); }
.antenna { width: 7px; height: 19px; background: #26bcd4; margin: auto; position: relative; border-radius: 5px; }
.antenna::before { content: ''; width: 15px; height: 15px; background: #61e0ef; border: 3px solid #22bad0; border-radius: 50%; position: absolute; top: -9px; left: -4px; }
.robot-head { position: relative; background: linear-gradient(145deg, #92f4ff, #13b9d5 70%, #0093bb); border-radius: 35px; height: 100px; box-shadow: inset 2px 3px 4px #d7fbff, 0 8px 20px #32b9d329; padding: 23px 19px; }
.robot-head::before, .robot-head::after { content: ''; width: 13px; height: 28px; background: #26bed9; position: absolute; top: 40px; border-radius: 8px; } .robot-head::before { left: -8px; } .robot-head::after { right: -8px; }
.robot-face { background: #ddfaff; border: 3px solid #11a9c7; height: 45px; border-radius: 20px; display: flex; align-items: center; justify-content: center; gap: 28px; box-shadow: inset 0 2px 4px #4db3cc40; }
.robot-face i { width: 9px; height: 15px; background: #174864; border-radius: 7px; }
.robot-smile { width: 16px; height: 8px; border-bottom: 3px solid #167996; border-radius: 50%; margin: 5px auto; }
.robot-body { width: 65px; height: 40px; border-radius: 15px 15px 24px 24px; background: linear-gradient(145deg, #7fedf7, #16b7d5); margin: 8px auto; display: flex; align-items: center; justify-content: center; color: white; }
.robot-shadow { width: 90px; height: 12px; margin: auto; border-radius: 50%; background: #4ab4c323; filter: blur(4px); }
.suggestion-heading { display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--muted); margin: 48px 0 16px; }
.suggestions { display: grid; gap: 11px; max-width: 430px; }
.suggestions button { display: flex; align-items: center; justify-content: space-between; gap: 12px; border: 0; border-radius: 13px; padding: 16px 18px; background: #f4f6f7; color: #45515c; text-align: left; font-size: 14px; }
.suggestions button:hover { background: #eaf8fa; } .suggestions span { color: #83949d; }
.composer-wrap { width: min(100%, 796px); margin: 0 auto; padding: 12px 28px 22px; }
.composer { border: 1px solid #e3e9eb; border-radius: 22px; background: #f7f9fa; padding: 18px 18px 12px; }
.composer:focus-within { border-color: #41b7c8; }
.composer textarea { width: 100%; resize: none; border: 0; background: transparent; color: var(--ink); font: inherit; font-size: 15px; line-height: 1.7; min-height: 52px; max-height: 180px; outline: none; }
.composer textarea::placeholder { color: #75828c; }
.composer-tools { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.composer-tools > span { display: flex; align-items: center; color: #586873; gap: 6px; font-size: 12px; overflow: hidden; } .model-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 230px; }
.send-button { width: 38px; height: 38px; border: none; border-radius: 50%; color: white; background: #087e92; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.composer-hint { color: var(--muted); font-size: 10px; text-align: center; margin: 12px 0 0; } .composer-hint span { margin-left: 20px; }
.service-notice, .chat-error { margin: 0 0 10px; font-size: 12px; color: #67757e; line-height: 1.6; } .chat-error { color: #b13d3d; }
.service-notice button, .chat-error button { border: 0; padding: 3px 8px; color: #007d92; background: transparent; text-decoration: underline; }
.conversation { width: min(100%, 740px); margin: 28px auto; }
.message { margin-bottom: 28px; font-size: 14px; } .message-author { display: block; font-size: 11px; color: var(--muted); margin-bottom: 8px; }
.message.user { margin-left: auto; width: fit-content; max-width: 85%; } .message.user .message-author { text-align: right; } .user-content { border-radius: 18px 18px 4px 18px; background: #eaf7f9; padding: 12px 18px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.thinking { color: #64727c; display: flex; align-items: center; gap: 5px; font-size: 12px; margin: 10px 0 24px; } .thinking span { width: 5px; height: 5px; border-radius: 50%; background: #28a7bc; } .thinking span:last-of-type { margin-right: 8px; }
.history-panel { position: absolute; z-index: 4; right: max(28px, calc((100vw - 1004px) / 2)); top: 90px; width: min(350px, calc(100% - 40px)); max-height: 65vh; overflow: auto; background: #fff; padding: 18px; border: 1px solid #e4ecef; box-shadow: 0 12px 40px #24333c18; border-radius: 18px; }
.history-panel > div { display: flex; justify-content: space-between; align-items: center; } .history-panel h2 { font-size: 16px; margin: 0; } .history-panel p { font-size: 11px; line-height: 1.7; color: var(--muted); } .history-panel .empty-history { padding: 18px 0; }
.history-item { width: 100%; display: flex; gap: 10px; align-items: center; text-align: left; padding: 12px 8px; background: none; border: 0; border-radius: 10px; font-size: 13px; } .history-item span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .history-item:hover, .history-item[aria-pressed=true] { background: #effafb; }
.search-intro { max-width: 650px; margin: 64px auto 50px; text-align: center; } .feature-tag { display: inline-flex; align-items: center; gap: 6px; border: 1px solid #dbeff2; padding: 7px 13px; border-radius: 20px; font-size: 12px; color: #087b8b; }
.search-emblem { width: 94px; height: 94px; border-radius: 30px; margin: 32px auto; background: #e6f8fa; color: #1493a7; display: grid; place-items: center; } .search-intro h1 { font-size: clamp(28px, 5vw, 40px); line-height: 1.5; letter-spacing: -1.5px; margin: 0 0 18px; } .search-intro > p { color: var(--muted); font-size: 14px; line-height: 1.9; }
.search-example { background: #f5f7f8; border-radius: 14px; padding: 20px 14px; margin: 26px auto; font-size: 14px; color: #596771; max-width: 420px; } .search-intro .future-note { font-size: 12px; max-width: 470px; margin: 0 auto 26px; } .outline-button { display: inline-flex; gap: 10px; align-items: center; border: 1px solid #d4e4e8; background: white; color: #087e92; border-radius: 12px; padding: 12px 18px; font-size: 13px; }
@media (max-width: 600px) { .ai-header { padding: 16px 14px; } .header-actions { gap: 0; } .mode-control { margin-left: 40px; } .mode-trigger strong { font-size: 19px; } .ai-scroll { padding: 0 24px; } .welcome { margin-top: 54px; } .greeting-row { gap: 8px; } .greeting h1 { font-size: 52px; letter-spacing: -3px; } .greeting h2 { font-size: 22px; } .greeting p { font-size: 12px; max-width: 200px; } .robot { width: 118px; height: 143px; } .robot-head { height: 82px; border-radius: 28px; padding: 19px 12px; } .robot-face { height: 36px; gap: 22px; } .robot-body { height: 30px; width: 54px; margin-top: 6px; } .robot-shadow { width: 70px; height: 9px; } .suggestion-heading { margin-top: 40px; } .suggestions button { font-size: 12px; padding: 15px 14px; } .composer-wrap { padding: 12px 18px max(16px, env(safe-area-inset-bottom)); } .composer { padding: 14px 14px 10px; border-radius: 19px; } .composer-hint span { display: none; } .model-name { max-width: 150px; } .search-intro { margin-top: 42px; } }
@media (max-width: 360px) { .ai-scroll { padding: 0 18px; } .robot { transform: scale(.85) rotate(8deg); margin: 0 -9px; } .greeting h1 { font-size: 44px; } .greeting h2 { font-size: 20px; } }
</style>
