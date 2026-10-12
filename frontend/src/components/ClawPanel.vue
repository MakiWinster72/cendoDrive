<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from "vue";
import { useOutsideClick } from "./useOutsideClick";
import AiMessage from "./AiMessage.vue";
import AiWelcome from "./AiWelcome.vue";
import { ArrowUp, ChevronDown, FileText, MessageCircle, RefreshCw, Search, Square, X } from "@lucide/vue";
import { askClaw, getClawStatus, reindexClaw, type ClawAnswer, type ClawStatus } from "../api/claw";
const status = ref<ClawStatus>();
const supportedFiles = computed(() => status.value?.files.filter(file => /\.(txt|md|csv|pdf|docx)$/i.test(file.name)) ?? []);
const question = ref("");
const pending = ref(false);
const indexing = ref(false);
const error = ref("");
type Turn = { question: string; answer: ClawAnswer };
type Conversation = { id: number; title: string; turns: Turn[]; draft: string };
let nextId = 1;
const current = ref<Conversation>({ id: nextId++, title: "新对话", turns: [], draft: "" });
const conversations = ref<Conversation[]>([]);
const turns = computed(() => current.value.turns);
const saved = computed(() => conversations.value.filter(conversation => conversation.turns.length));
const historyOpen = ref(false);
const filesOpen = ref(false);
const filesPanel = ref<HTMLElement>();
const filesTrigger = ref<HTMLElement>();
useOutsideClick([filesPanel, filesTrigger], () => { filesOpen.value = false; });
const composing = ref(false);
const statusError = ref("");
const input = ref<HTMLTextAreaElement>();
const scroller = ref<HTMLElement>();
const suggestions = ["帮我找出提到项目预算的文档", "总结网盘里的课程笔记", "哪些文件提到了项目进度？"];
let visible = true;
let refreshing = false;
async function scrollBottom() { await nextTick(); scroller.value?.scrollTo?.({ top: scroller.value.scrollHeight, behavior: "smooth" }); }
function newConversation() {
  stop(); current.value.draft = question.value;
  current.value = { id: nextId++, title: "新对话", turns: [], draft: "" };
  question.value = ""; error.value = ""; historyOpen.value = false;
  void nextTick(() => input.value?.focus());
}
function selectConversation(conversation: Conversation) {
  stop(); current.value.draft = question.value; current.value = conversation;
  question.value = conversation.draft; error.value = ""; historyOpen.value = false; void scrollBottom();
}
function toggleHistory() { historyOpen.value = !historyOpen.value; filesOpen.value = false; }
defineExpose({ newConversation, toggleHistory, historyOpen });
const controller = new AbortController();
let active: AbortController | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
const canSend = computed(() => status.value?.configured && !!question.value.trim() && question.value.length <= 2000 && !pending.value);
const labels: Record<string, string> = { READY: "已索引", PENDING: "等待索引", PROCESSING: "正在索引", RETRY: "等待重试", FAILED: "索引失败", UNSUPPORTED: "无法提取", NEEDS_REINDEX: "需要重建" };
async function refresh() {
  if (refreshing || controller.signal.aborted) return;
  refreshing = true; statusError.value = "";
  clearTimeout(timer);
  try { const value = await getClawStatus(controller.signal); if (!controller.signal.aborted) status.value = value; }
  catch { if (!controller.signal.aborted) statusError.value = "无法获取文档状态，请刷新重试。"; }
  finally { refreshing = false; if (visible && !controller.signal.aborted) timer = setTimeout(() => void refresh(), 10000); }
}
async function send() {
  if (!canSend.value) return;
  const text = question.value.trim();
  const conversation = current.value;
  const request = new AbortController(); active = request;
  pending.value = true; error.value = "";
  question.value = ""; conversation.draft = "";
  filesOpen.value = false;
  void scrollBottom();
  try {
    const previous = conversation.turns.at(-1)?.question.slice(0, 300);
    const answer = await askClaw(text, previous, request.signal);
    if (!request.signal.aborted && active === request) {
      conversation.turns.push({ question: text, answer });
      conversation.title = conversation.turns[0]!.question.slice(0, 28);
      if (!conversations.value.some(saved => saved.id === conversation.id)) conversations.value.unshift(conversation);
      void scrollBottom();
    }
  } catch { if (!request.signal.aborted && active === request) {
    if (current.value === conversation && !question.value) { question.value = text; conversation.draft = text; }
    error.value = "文档问答失败，请检查索引状态后重试。问题已保留。";
  } }
  finally { if (active === request) { pending.value = false; active = undefined; } }
}
function stop() { active?.abort(); active = undefined; pending.value = false; }
async function reindex(fileId?: string) {
  if (indexing.value) return;
  indexing.value = true; error.value = "";
  try { await reindexClaw(fileId, controller.signal); await refresh(); }
  catch { if (!controller.signal.aborted) error.value = "无法提交索引任务，请检查服务配置或文件大小后重试。"; }
  finally { indexing.value = false; }
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing && !composing.value) { event.preventDefault(); void send(); }
}
onMounted(refresh);
onActivated(() => { visible = true; if (!timer) void refresh(); });
onDeactivated(() => { visible = false; stop(); clearTimeout(timer); timer = undefined; historyOpen.value = false; filesOpen.value = false; });
onBeforeUnmount(() => { controller.abort(); stop(); clearTimeout(timer); });
</script>

<template>
  <section class="claw-session" aria-label="扣扣Claw文件问答" @keydown.esc="filesOpen = false; historyOpen = false">
    <aside v-if="historyOpen" id="claw-history" class="history-panel" aria-label="文件问答历史列表">
      <div><h2>对话历史</h2><button class="icon-button" aria-label="关闭对话历史" @click="historyOpen = false"><X :size="18" /></button></div>
      <p>仅保留在当前页面，离开或刷新后清空。</p>
      <p v-if="!saved.length" class="empty-history">还没有对话，开始聊聊吧。</p>
      <button v-for="conversation in saved" :key="conversation.id" class="history-item" :aria-pressed="current.id === conversation.id" @click="selectConversation(conversation)"><MessageCircle :size="18" /><span>{{ conversation.title }}</span></button>
    </aside>
    <div ref="scroller" class="ai-scroll">
      <AiWelcome v-if="!turns.length && !pending" name="扣扣Claw" description="根据网盘文件内容搜索文件" :suggestions="suggestions" @select="value => { question = value; input?.focus(); }" />
      <section v-else class="conversation" aria-label="文件问答消息" aria-live="polite" :aria-busy="pending">
        <template v-for="(turn, i) in turns" :key="i">
          <article class="message user"><span class="message-author">你</span><div class="user-content">{{ turn.question }}</div></article>
          <article class="message assistant">
            <span class="message-author">扣扣Claw</span><AiMessage :content="turn.answer.content" />
            <div v-if="turn.answer.sources.length" class="sources">
              <span class="sources-label"><FileText :size="14" />参考文件 · {{ turn.answer.sources.length }}</span>
              <details v-for="source in turn.answer.sources" :key="source.citation" class="source">
                <summary><span class="citation">{{ source.citation }}</span><FileText :size="16" /><span class="source-name">{{ source.name }}</span><ChevronDown :size="14" /></summary>
                <div class="source-body"><span>{{ source.path }}</span><AiMessage class="source-excerpt" :content="source.excerpt" excerpt /></div>
              </details>
            </div>
          </article>
        </template>
        <article v-if="pending" class="message user"><span class="message-author">你</span><div class="user-content">{{ question }}</div></article>
        <div v-if="pending" class="thinking" role="status"><span /><span /><span />扣扣Claw正在查找文档并思考…</div>
      </section>
    </div>
    <footer class="composer-wrap">
      <p v-if="statusError" class="service-notice" role="alert">{{ statusError }}<button @click="refresh">刷新状态</button></p>
      <p v-else-if="!status" class="service-notice" role="status">正在连接文件问答…</p>
      <p v-else-if="!status.configured" class="service-notice" role="status">扣扣Claw尚未配置，请联系管理员。<button @click="refresh">刷新状态</button></p>
      <p v-if="error" class="chat-error" role="alert">{{ error }}<button :disabled="!canSend" @click="send">重试</button></p>
      <Transition name="files-popover">
      <div ref="filesPanel" v-if="filesOpen" id="claw-files" class="files-panel">
        <div class="files-heading"><h2>文档状态</h2><button class="icon-button" aria-label="关闭文档状态" @click="filesOpen = false"><X :size="18" /></button></div>
        <template v-if="status">
          <p class="file-counts">已索引 {{ status.ready }} · 处理中 {{ status.pending }} · 失败 {{ status.failed }} · 无法提取 {{ status.unsupported }} · 需要重建 {{ status.needsReindex }}</p>
          <div class="index-actions"><button :disabled="refreshing" @click="refresh"><RefreshCw :size="14" />刷新状态</button><button :disabled="!status.configured || indexing" @click="reindex()">确认重建已有文档索引</button></div>
          <p class="hint">重建会将文档内容发送到已配置的向量模型。</p>
          <ul v-if="supportedFiles.length" class="file-list"><li v-for="file in supportedFiles" :key="file.fileId"><FileText :size="17" /><div><b>{{ file.name }}</b><span>{{ file.path }}</span></div><span class="file-state">{{ labels[file.status] ?? file.status }}</span><button v-if="['FAILED', 'NEEDS_REINDEX'].includes(file.status)" :disabled="indexing || !status.configured" @click="reindex(file.fileId)">确认重试</button></li></ul>
          <p v-else class="hint">还没有可索引的文档，上传后可在这里查看。</p>
        </template>
      </div>
      </Transition>
      <form class="composer" @submit.prevent="send">
        <textarea ref="input" v-model="question" aria-label="搜索网盘文档内容" placeholder="想找什么文件，尽管问我…" maxlength="2000" rows="2" :readonly="pending" @keydown="onKeydown" @compositionstart="composing = true" @compositionend="composing = false" />
        <div class="composer-tools"><span><Search :size="15" />文件问答<button ref="filesTrigger" type="button" class="document-status" :aria-expanded="filesOpen" aria-controls="claw-files" @click="filesOpen = !filesOpen; historyOpen = false"><FileText :size="14" /><span>{{ status ? `${status.ready} 个文档` : '文档状态' }}</span><ChevronDown :size="13" :class="{ rotated: filesOpen }" /></button></span>
          <button v-if="pending" type="button" class="send-button" aria-label="停止回答" @click="stop"><Square :size="16" /></button><button v-else type="submit" class="send-button" aria-label="发送问题" :disabled="!canSend"><ArrowUp :size="22" /></button>
        </div>
      </form>
    </footer>
  </section>
</template>

<style scoped src="../styles/ai.css"></style>
<style scoped>
.claw-session { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.composer-wrap { position: relative; flex-shrink: 0; }
.document-status { display: inline-flex; align-items: center; gap: 5px; border: 0; border-radius: 8px; background: #edf1f3; color: #586873; padding: 5px 8px; margin-left: 6px; font-size: 11px; white-space: nowrap; }
.document-status:hover { background: #eaf7f9; color: #087e92; }
.files-panel { position: absolute; bottom: calc(100% + 8px); left: 28px; right: 28px; padding: 16px 18px; max-height: min(52dvh, 420px); overflow-y: auto; background: white; border: 1px solid #e4ecef; border-radius: 18px; box-shadow: 0 12px 40px #24333c18; z-index: 3; }
.files-heading { display: flex; align-items: center; justify-content: space-between; } .files-heading h2 { font-size: 15px; margin: 0; }
.file-counts, .hint { font-size: 11px; color: var(--muted); line-height: 1.8; }
.index-actions { display: flex; flex-wrap: wrap; gap: 8px; } .index-actions button, .file-list button { display: inline-flex; align-items: center; gap: 6px; border: 1px solid #d4e4e8; border-radius: 9px; padding: 7px 10px; background: #f7f9fa; color: #087e92; font-size: 11px; }
.file-list { list-style: none; padding: 0; margin: 12px 0 0; } .file-list li { display: flex; align-items: center; gap: 9px; padding: 12px 0; border-top: 1px solid #edf1f3; font-size: 11px; color: var(--muted); } .file-list li > svg { flex-shrink: 0; } .file-list li > div { flex: 1; min-width: 0; } .file-list b { display: block; color: var(--ink); font-weight: 500; overflow-wrap: anywhere; } .file-list li > div > span { display: block; margin-top: 4px; overflow-wrap: anywhere; } .file-state { white-space: nowrap; }
.sources { margin-top: 18px; } .sources-label { display: flex; align-items: center; gap: 6px; color: var(--muted); font-size: 11px; margin-bottom: 10px; }
.source { border: 1px solid #e4ecef; border-radius: 12px; margin-top: 8px; background: #f7f9fa; overflow: hidden; } .source summary { display: flex; align-items: center; gap: 8px; padding: 11px 13px; cursor: pointer; color: #586873; font-size: 12px; list-style: none; } .source summary::-webkit-details-marker { display: none; } .source summary > svg { flex-shrink: 0; } .source-name { flex: 1; overflow-wrap: anywhere; } .citation { display: grid; place-items: center; flex-shrink: 0; min-width: 20px; height: 20px; border-radius: 6px; background: #e4f3f6; color: #087e92; font-size: 10px; } .source[open] summary > svg:last-child { transform: rotate(180deg); }
.source-body { padding: 0 13px 12px; color: var(--muted); font-size: 11px; overflow-wrap: anywhere; } .source-excerpt { min-width: 0; font-size: 12px; color: #45515c; margin: 10px 0 0; }
@media (max-width: 600px) { .files-panel { left: 18px; right: 18px; padding: 12px 14px; } .file-list li { flex-wrap: wrap; } .file-list li > div { min-width: 50%; } }
</style>
