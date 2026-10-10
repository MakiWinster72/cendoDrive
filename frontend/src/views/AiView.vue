<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  ArrowUp,
  AudioLines,
  BookOpen,
  Brain,
  Camera,
  Check,
  ChevronDown,
  Clock3,
  ClockArrowLeft,
  ClipboardList,
  ContactRound,
  Copy,
  Download,
  Ellipsis,
  FilePenLine,
  FileType,
  FileUp,
  Folder,
  Globe,
  Image,
  ImagePlus,
  Languages,
  MessageCirclePlus,
  MicAudioLines,
  Presentation,
  RadioTower,
  RotateCcw,
  Scissors,
  Sparkles,
  Square,
  ThumbsDown,
  ThumbsUp,
  Video,
  Volume2,
  X,
} from "@lucide/vue";
import AiMessage from "../components/AiMessage.vue";
import UnavailableFeatureDialog from "../components/UnavailableFeatureDialog.vue";
import {
  chatContext,
  getChatStatus,
  sendChat,
  type ChatMessage,
  type ChatStatus,
} from "../api/aiChat";

const router = useRouter();
const draft = ref("");
const composing = ref(false);
const pending = ref(false);
const onlineSearch = ref(false);
const mode = ref<"kuku" | "standard">("kuku");
const modeMenuOpen = ref(false);
const moreMenuOpen = ref(false);
const status = ref<ChatStatus | null>(null);
const statusLoading = ref(false);
const statusError = ref("");
const error = ref("");
const unavailableOpen = ref(false);
const copied = ref(false);
const scroller = ref<HTMLElement>();
const input = ref<HTMLTextAreaElement>();
const messages = ref<ChatMessage[]>([]);
let request: AbortController | undefined;
let statusRequest: AbortController | undefined;

const tools = [
  { title: "视频课件", subtitle: "一键生成并导出网课PPT…", icon: Presentation },
  { title: "音频会议纪要", subtitle: "高质量纪要AI帮写", icon: MicAudioLines },
  { title: "小说生成视频", subtitle: "一键生成小说视频", icon: BookOpen },
  { title: "拍图写作", subtitle: "拍图/上传图片生成作文", icon: Image },
  { title: "AI写真", subtitle: "定制创意写真", icon: ContactRound },
  { title: "播客解析", subtitle: "智能总结，一键生成", icon: RadioTower },
  { title: "PPT逐字稿", subtitle: "轻松应对PPT汇报/演讲", icon: FileType },
  { title: "视频精转文稿", subtitle: "视频转文字，一键导出", icon: Languages },
];
const suggestions = [
  "热点：村上春树作息健康到逆天",
  "周末两天去哪儿玩",
  "第一次露营要带什么",
  "下班后总犯困怎么办",
];
const quickTools = [
  { label: "AI剪辑", icon: Scissors },
  { label: "文件整理", icon: Folder },
  { label: "资源下载", icon: Download },
  { label: "生成PPT", icon: Presentation },
  { label: "AI视频", icon: Video },
  { label: "AI生图", icon: ImagePlus },
  { label: "拍照解题", icon: Camera },
  { label: "作业批改", icon: FilePenLine },
  { label: "相册创意", icon: Image },
  { label: "AI出题", icon: ClipboardList },
  { label: "定时任务", icon: Clock3 },
];
const canSend = computed(
  () =>
    Boolean(draft.value.trim()) &&
    draft.value.length <= 8000 &&
    status.value?.configured &&
    !pending.value,
);
const lastAssistant = computed(
  () =>
    [...messages.value]
      .reverse()
      .find((message) => message.role === "assistant")?.content ?? "",
);

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
    if (!controller.signal.aborted)
      statusError.value = "暂时无法连接智能对话，请重试。";
  } finally {
    if (statusRequest === controller) statusLoading.value = false;
  }
}
async function scrollBottom() {
  await nextTick();
  scroller.value?.scrollTo?.({
    top: scroller.value.scrollHeight,
    behavior: "smooth",
  });
}
function showUnavailable() {
  unavailableOpen.value = true;
}
function selectMode(value: "kuku" | "standard") {
  stop();
  mode.value = value;
  modeMenuOpen.value = false;
  moreMenuOpen.value = false;
}
function fillSuggestion(value: string) {
  draft.value = value;
  void nextTick(() => input.value?.focus());
}
function stop() {
  request?.abort();
  request = undefined;
  if (pending.value && messages.value.at(-1)?.role === "user") {
    draft.value = messages.value.pop()?.content ?? draft.value;
  }
  pending.value = false;
}
async function send() {
  if (!canSend.value) return;
  const question = draft.value.trim();
  const context = chatContext(messages.value, question);
  const controller = new AbortController();
  request = controller;
  pending.value = true;
  error.value = "";
  draft.value = "";
  messages.value.push({ role: "user", content: question });
  void scrollBottom();
  try {
    const content = await sendChat(context, controller.signal);
    if (request !== controller || controller.signal.aborted) return;
    messages.value.push({ role: "assistant", content });
    void scrollBottom();
  } catch (cause) {
    if (request !== controller || controller.signal.aborted) return;
    messages.value.pop();
    draft.value = question;
    const code = (cause as { response?: { data?: { code?: string } } }).response
      ?.data?.code;
    error.value =
      code === "AI_RATE_LIMITED"
        ? "模型服务繁忙，请稍后重试。"
        : code === "AI_NOT_CONFIGURED"
          ? "智能对话尚未配置，请联系管理员。"
          : "回答失败或超时，请重试。你的问题已保留。";
  } finally {
    if (request === controller) {
      request = undefined;
      pending.value = false;
    }
  }
}
function resetConversation() {
  stop();
  messages.value = [];
  draft.value = "";
  error.value = "";
  mode.value = "kuku";
  modeMenuOpen.value = false;
  moreMenuOpen.value = false;
  void nextTick(() => input.value?.focus());
}
function onKeydown(event: KeyboardEvent) {
  if (
    event.key === "Enter" &&
    !event.shiftKey &&
    !event.isComposing &&
    !composing.value
  ) {
    event.preventDefault();
    void send();
  }
}
async function copyAnswer(content = lastAssistant.value) {
  if (!content) return;
  try {
    await navigator.clipboard.writeText(content);
    copied.value = true;
    window.setTimeout(() => {
      copied.value = false;
    }, 1200);
  } catch {
    copied.value = false;
  }
}
async function copyQuestion(content: string) {
  try {
    await navigator.clipboard.writeText(content);
  } catch {
    /* Clipboard permission can be denied. */
  }
}
async function regenerate(index: number) {
  if (pending.value) return;
  const question =
    messages.value[index - 1]?.role === "user"
      ? messages.value[index - 1]!.content
      : "";
  if (!question) return;
  messages.value = messages.value.slice(0, index - 1);
  draft.value = question;
  await send();
}

onMounted(loadStatus);
onBeforeUnmount(() => {
  stop();
  statusRequest?.abort();
});
</script>

<template>
  <main
    :class="['kuku-page', { 'welcome-mode': mode === 'kuku' }]"
    @click="
      moreMenuOpen = false;
      modeMenuOpen = false;
    "
  >
    <header class="kuku-header">
      <button class="plain-icon" aria-label="返回上一页" @click="router.back()">
        <X />
      </button>
      <div class="mode-switch">
        <button
          class="mode-label"
          aria-label="切换AI模式"
          :aria-expanded="modeMenuOpen"
          @click.stop="
            modeMenuOpen = !modeMenuOpen;
            moreMenuOpen = false;
          "
        >
          <span>{{ mode === "kuku" ? "扣扣AI" : "标准模式" }}</span
          ><ChevronDown :size="18" />
        </button>
        <div v-if="modeMenuOpen" class="mode-popover">
          <button
            :class="{ selected: mode === 'kuku' }"
            @click="selectMode('kuku')"
          >
            <Sparkles :size="19" /><span
              ><b>扣扣AI</b><small>Hello 欢迎页</small></span
            ><Check v-if="mode === 'kuku'" :size="17" />
          </button>
          <button
            :class="{ selected: mode === 'standard' }"
            @click="selectMode('standard')"
          >
            <Presentation :size="19" /><span
              ><b>标准模式</b><small>AI 工具中心</small></span
            ><Check v-if="mode === 'standard'" :size="17" />
          </button>
        </div>
      </div>
      <div class="header-actions">
        <button
          class="plain-icon"
          aria-label="对话历史"
          @click="showUnavailable"
        >
          <ClockArrowLeft />
        </button>
        <button
          class="plain-icon"
          aria-label="新建对话"
          @click="resetConversation"
        >
          <MessageCirclePlus />
        </button>
        <div class="more-wrap">
          <button
            class="plain-icon"
            aria-label="更多功能"
            :aria-expanded="moreMenuOpen"
            @click.stop="
              moreMenuOpen = !moreMenuOpen;
              modeMenuOpen = false;
            "
          >
            <Ellipsis />
          </button>
          <div v-if="moreMenuOpen" class="more-menu" @click.stop>
            <button @click="showUnavailable">
              <Brain /><span>记忆中心</span>
            </button>
            <button @click="showUnavailable">
              <Sparkles /><span>AI点数充值</span>
            </button>
            <button @click="showUnavailable">
              <ClipboardList /><span>AI点数明细</span>
            </button>
          </div>
        </div>
      </div>
    </header>

    <div ref="scroller" class="kuku-scroll">
      <section v-if="!messages.length && mode === 'kuku'" class="kuku-welcome">
        <div class="orb" aria-hidden="true"><i /><i /><i /></div>
        <div class="hello-copy">
          <h1>Hello</h1>
          <h2>欢迎使用扣扣AI</h2>
        </div>
        <div class="suggestion-list">
          <button
            v-for="suggestion in suggestions"
            :key="suggestion"
            @click="fillSuggestion(suggestion)"
          >
            {{ suggestion }}
          </button>
        </div>
      </section>
      <section
        v-else-if="!messages.length"
        class="tool-grid"
        aria-label="AI功能入口"
      >
        <button
          v-for="tool in tools"
          :key="tool.title"
          class="tool-card"
          @click="showUnavailable"
        >
          <component :is="tool.icon" class="tool-icon" />
          <strong>{{ tool.title }}</strong
          ><span>{{ tool.subtitle }}</span>
        </button>
        <button class="explore-more" @click="showUnavailable">
          <Sparkles />探索更多<span>›</span>
        </button>
      </section>

      <section
        v-else
        class="conversation"
        :class="{
          'kuku-conversation': mode === 'kuku',
          'is-thinking': mode === 'kuku' && pending,
        }"
        aria-label="智能对话消息"
        :aria-busy="pending"
        aria-live="polite"
      >
        <template v-for="(message, index) in messages" :key="index">
          <article v-if="message.role === 'user'" class="user-message">
            <div>{{ message.content }}</div>
            <span class="user-actions"
              ><button
                aria-label="复制问题"
                @click="copyQuestion(message.content)"
              >
                <Copy :size="18" /></button
              ><i /><button
                aria-label="编辑问题"
                @click="
                  draft = message.content;
                  input?.focus();
                "
              >
                <FilePenLine :size="18" /></button
            ></span>
          </article>
          <article v-else class="assistant-message">
            <AiMessage :content="message.content" />
            <div
              v-if="mode === 'kuku'"
              class="answer-actions kuku-answer-actions"
            >
              <button aria-label="重新回答" @click="regenerate(index)">
                <RotateCcw :size="22" />
              </button>
              <span
                ><button aria-label="朗读回答" @click="showUnavailable">
                  <Volume2 :size="22" /></button
                ><button
                  :aria-label="copied ? '已复制' : '复制回答'"
                  @click="copyAnswer(message.content)"
                >
                  <Check v-if="copied" :size="21" /><Copy
                    v-else
                    :size="21"
                  /><b>{{ copied ? "已复制" : "复制" }}</b>
                </button></span
              >
            </div>
            <div v-else class="answer-actions">
              <button @click="regenerate(index)">
                <RotateCcw :size="17" />重新回答
              </button>
              <span
                ><button
                  :aria-label="copied ? '已复制' : '复制回答'"
                  @click="copyAnswer(message.content)"
                >
                  <Check v-if="copied" :size="18" /><Copy
                    v-else
                    :size="18"
                  /></button
                ><i /><button aria-label="点赞" @click="showUnavailable">
                  <ThumbsUp :size="18" /></button
                ><i /><button aria-label="点踩" @click="showUnavailable">
                  <ThumbsDown :size="18" /></button
              ></span>
            </div>
          </article>
        </template>
        <article
          v-if="pending && mode === 'kuku'"
          class="kuku-thinking"
          role="status"
        >
          <div class="thinking-label">思考中…</div>
          <div class="thinking-orb" aria-hidden="true"><i /><i /><i /></div>
        </article>
        <article
          v-else-if="pending"
          class="assistant-message thinking"
          role="status"
        >
          <i />
          <p>等我想想…</p>
        </article>
      </section>
    </div>

    <footer class="composer-dock">
      <div
        v-if="mode === 'kuku' && pending"
        class="processing-card"
        role="status"
      >
        <span class="processing-orb" aria-hidden="true" />
        <span><strong>正在理解您的问题</strong><small>任务处理中…</small></span>
        <button type="button" aria-label="停止回答" @click="stop">
          <Square :size="17" />
        </button>
      </div>
      <template v-else>
        <p v-if="statusLoading" class="service-note">正在连接智能对话…</p>
        <p v-else-if="statusError" class="service-note" role="alert">
          {{ statusError }} <button @click="loadStatus">重新连接</button>
        </p>
        <p v-else-if="status && !status.configured" class="service-note">
          智能对话尚未配置，请联系管理员。
          <button @click="loadStatus">刷新状态</button>
        </p>
        <p v-if="error" class="service-note error" role="alert">{{ error }}</p>
        <div v-if="mode === 'kuku'" class="quick-tools" aria-label="快捷工具">
          <button
            v-for="tool in quickTools"
            :key="tool.label"
            @click="showUnavailable"
          >
            <component :is="tool.icon" />{{ tool.label }}
          </button>
        </div>
        <button
          v-else
          class="network-toggle"
          :class="{ active: onlineSearch }"
          :aria-pressed="onlineSearch"
          @click="onlineSearch = !onlineSearch"
        >
          <Check v-if="onlineSearch" :size="16" /><Globe
            v-else
            :size="20"
          />联网搜索
        </button>
        <form class="composer" @submit.prevent="send">
          <textarea
            ref="input"
            v-model="draft"
            aria-label="输入你的要求"
            placeholder="输入你的要求"
            rows="2"
            maxlength="8000"
            @keydown="onKeydown"
            @compositionstart="composing = true"
            @compositionend="composing = false"
          />
          <div class="composer-bar">
            <button
              type="button"
              aria-label="语音输入"
              @click="showUnavailable"
            >
              <AudioLines :size="27" />
            </button>
            <span
              ><button
                type="button"
                aria-label="上传文件"
                @click="showUnavailable"
              >
                <FileUp :size="26" /></button
              ><button type="button" aria-label="拍照" @click="showUnavailable">
                <Camera :size="27" /></button
              ><button
                type="submit"
                class="submit"
                aria-label="发送问题"
                :disabled="!canSend"
              >
                <ArrowUp :size="22" /></button
            ></span>
          </div>
        </form>
        <small>内容由AI生成</small>
      </template>
    </footer>
    <UnavailableFeatureDialog
      :open="unavailableOpen"
      message="该功能尚未开放"
      @close="unavailableOpen = false"
    />
  </main>
</template>

<style scoped>
.kuku-page {
  --navy: #17183f;
  --muted: #999ba8;
  --blue: #129bf1;
  position: relative;
  width: 100%;
  max-width: 100vw;
  height: 100dvh;
  min-height: 0;
  overflow: hidden;
  overscroll-behavior: none;
  display: flex;
  flex-direction: column;
  color: var(--navy);
  background: #f1f2f8;
  font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
}
.kuku-page button,
.kuku-page textarea {
  font: inherit;
}
.kuku-page button {
  cursor: pointer;
}
.kuku-page button:focus-visible,
.kuku-page textarea:focus-visible {
  outline: 3px solid #4cbcf6;
  outline-offset: 3px;
}
.kuku-header {
  height: 92px;
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 12px clamp(18px, 4vw, 40px) 0;
}
.plain-icon,
.mode-label {
  border: 0;
  background: transparent;
  color: var(--navy);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.plain-icon {
  width: 44px;
  height: 44px;
  border-radius: 50%;
}
.plain-icon > svg {
  width: 28px;
  height: 28px;
  stroke-width: 2;
}
.mode-label {
  gap: 6px;
  font-size: 22px;
  letter-spacing: 0.02em;
}
.header-actions {
  justify-self: end;
  display: flex;
  gap: 12px;
}
.header-actions .reference-icon {
  width: 32px;
  height: 32px;
}
.kuku-scroll {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  padding: 24px clamp(18px, 4vw, 40px) 40px;
  scrollbar-width: thin;
}
.tool-grid {
  width: min(840px, 100%);
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.tool-card {
  min-height: 150px;
  border: 0;
  border-radius: 24px;
  background: #fff;
  padding: 26px 28px;
  text-align: left;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  color: var(--navy);
  box-shadow: 0 1px 0 #fff;
}
.tool-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 28px #292d5510;
}
.tool-icon {
  width: 34px;
  height: 34px;
  color: var(--navy);
  margin-bottom: 18px;
}
.tool-card strong {
  font-size: 19px;
  line-height: 1.25;
  margin-bottom: 8px;
}
.tool-card span {
  color: #9d9eaa;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  width: 100%;
}
.explore-more {
  grid-column: 1/-1;
  justify-self: end;
  border: 0;
  background: transparent;
  color: #4c4d6c;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 17px;
}
.explore-more .reference-icon {
  width: 24px;
  height: 24px;
}
.explore-more span {
  font-size: 29px;
  line-height: 0.5;
}
.conversation {
  width: min(840px, 100%);
  margin: 0 auto;
  padding: 4px 0 20px;
}
.user-message {
  margin: 0 0 28px auto;
  width: fit-content;
  max-width: 82%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.user-message > div {
  background: #129ef2;
  color: #fff;
  padding: 15px 22px;
  border-radius: 20px 20px 3px 20px;
  font-size: 17px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.user-actions,
.answer-actions > span {
  margin-top: 9px;
  background: #fff;
  border-radius: 14px;
  display: flex;
  align-items: center;
  padding: 5px 8px;
}
.user-actions button,
.answer-actions button,
.composer-bar button {
  border: 0;
  background: transparent;
  color: #4b4c72;
  display: grid;
  place-items: center;
}
.user-actions button {
  padding: 4px 8px;
}
.user-actions i,
.answer-actions i {
  width: 1px;
  height: 20px;
  background: #d9dae1;
}
.assistant-message {
  background: #fff;
  border-radius: 20px;
  padding: 24px 26px;
  color: #202044;
  font-size: 16px;
  line-height: 1.9;
  margin-bottom: 22px;
  box-shadow: 0 1px 0 #fff;
}
.answer-actions {
  margin: 22px -4px -8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.answer-actions > button {
  display: flex;
  gap: 7px;
  padding: 7px;
}
.answer-actions > span button {
  padding: 5px 8px;
}
.thinking {
  min-height: 90px;
  color: #439fe8;
}
.thinking > i {
  display: block;
  width: 4px;
  height: 32px;
  background: #16a7f5;
}
.thinking p {
  margin: 6px 0 0;
}
.kuku-conversation {
  position: relative;
  min-height: 100%;
  padding: 28px 0 50px;
  color: #101522;
}
.kuku-conversation .user-message {
  margin-bottom: 48px;
}
.kuku-conversation .user-message > div {
  min-width: 74px;
  background: #f5f6fa;
  color: #111827;
  border-radius: 20px;
  padding: 18px 24px;
  text-align: center;
}
.kuku-conversation .user-actions {
  display: none;
}
.kuku-conversation .assistant-message {
  padding: 0;
  background: transparent;
  border-radius: 0;
  box-shadow: none;
  color: #111827;
  font-size: 20px;
  line-height: 1.8;
}
.kuku-answer-actions {
  margin: 26px 0 0;
}
.kuku-answer-actions > button,
.kuku-answer-actions > span > button {
  min-width: 50px;
  height: 50px;
  padding: 0 14px;
  border: 1px solid #dce2e9;
  border-radius: 12px;
  background: #fff;
  color: #101522;
  display: flex;
  align-items: center;
  justify-content: center;
}
.kuku-answer-actions > span {
  margin: 0;
  padding: 0;
  gap: 14px;
  background: transparent;
}
.kuku-answer-actions > span > button:last-child {
  gap: 8px;
}
.kuku-answer-actions b {
  font-size: 15px;
  font-weight: 500;
}
.kuku-thinking {
  position: relative;
  min-height: clamp(360px, 58vh, 680px);
  display: flex;
  align-items: center;
  justify-content: center;
}
.thinking-label {
  position: absolute;
  z-index: 2;
  top: 22%;
  left: 50%;
  transform: translateX(-50%);
  padding: 12px 28px;
  border-radius: 24px;
  background: #149ef2;
  color: #fff;
  font-size: 18px;
  white-space: nowrap;
}
.thinking-label:after {
  content: "";
  position: absolute;
  left: 50%;
  bottom: -10px;
  transform: translateX(-50%);
  border: 11px solid transparent;
  border-top-color: #149ef2;
  border-bottom: 0;
}
.thinking-orb {
  position: relative;
  width: clamp(220px, 50vw, 410px);
  aspect-ratio: 1;
  border-radius: 48% 52% 55% 45%;
  background:
    radial-gradient(
      circle at 67% 43%,
      rgba(255, 255, 255, 0.98) 0 18%,
      rgba(224, 255, 255, 0.9) 31%,
      rgba(89, 229, 251, 0.65) 62%,
      rgba(40, 199, 255, 0.38) 75%,
      rgba(255, 255, 255, 0) 77%
    ),
    radial-gradient(circle at 30% 65%, #75f4ef, #55cfff 68%);
  filter: blur(4px) saturate(1.05);
  box-shadow: 0 0 38px rgba(67, 218, 248, 0.18);
  animation: thinking-breathe 2.8s ease-in-out infinite;
}
.thinking-orb:before {
  content: "";
  position: absolute;
  inset: 8% 18% 13% 8%;
  border: 12px solid rgba(210, 255, 251, 0.38);
  border-radius: 43% 57% 62% 38%;
  transform: rotate(-24deg);
}
.thinking-orb i {
  position: absolute;
  border-radius: 50%;
  filter: blur(11px);
}
.thinking-orb i:nth-child(1) {
  width: 34%;
  height: 34%;
  left: 9%;
  bottom: 12%;
  background: rgba(44, 246, 224, 0.28);
}
.thinking-orb i:nth-child(2) {
  width: 38%;
  height: 38%;
  right: 5%;
  top: 8%;
  background: rgba(102, 220, 255, 0.25);
}
.thinking-orb i:nth-child(3) {
  width: 8%;
  height: 3%;
  left: 29%;
  top: 44%;
  background: rgba(255, 255, 255, 0.9);
}
.processing-card {
  min-height: 92px;
  border: 1px solid #edf0f3;
  border-radius: 23px;
  background: #fff;
  box-shadow: 0 12px 25px rgba(15, 25, 38, 0.18);
  display: flex;
  align-items: center;
  gap: 17px;
  padding: 14px 20px;
}
.processing-card > .processing-orb {
  flex: 0 0 46px;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background:
    radial-gradient(
      circle at 65% 38%,
      #fff 0 18%,
      #bffcf7 38%,
      #59ddfa 72%,
      #fff0 74%
    ),
    #70e9f2;
  filter: blur(0.7px);
  animation: thinking-breathe 2.8s ease-in-out infinite;
}
.processing-card > span:nth-child(2) {
  flex: 1;
  min-width: 0;
}
.processing-card strong,
.processing-card small {
  display: block;
}
.processing-card strong {
  font-size: 18px;
  color: #111827;
}
.processing-card small {
  margin-top: 4px;
  color: #818895;
  font-size: 15px;
}
.processing-card button {
  flex: 0 0 48px;
  width: 48px;
  height: 48px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #0b1526;
  display: grid;
  place-items: center;
}
.processing-card button:before {
  content: "";
  position: absolute;
  width: 38px;
  height: 38px;
  border: 3px solid currentColor;
  border-radius: 50%;
}
.processing-card button svg {
  fill: currentColor;
}
@keyframes thinking-breathe {
  0%,
  100% {
    opacity: 0.82;
    filter: blur(4px) saturate(1);
  }
  50% {
    opacity: 1;
    filter: blur(3px) saturate(1.2);
  }
}
.processing-card button {
  position: relative;
}
.composer-dock {
  flex: 0 0 auto;
  background: #fff;
  padding: 12px clamp(18px, 4vw, 40px) max(12px, env(safe-area-inset-bottom));
}
.composer-dock > * {
  width: min(840px, 100%);
  margin-left: auto;
  margin-right: auto;
}
.network-toggle {
  width: auto;
  margin-left: max(calc((100% - 840px) / 2), 0px);
  border: 1px solid #dedfe6;
  border-radius: 14px;
  background: #fff;
  color: #20213e;
  padding: 10px 15px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
}
.network-toggle.active svg {
  border-radius: 50%;
  background: var(--navy);
  color: #fff;
  padding: 2px;
}
.composer {
  margin-top: 10px;
  border: 2px solid #171926;
  border-radius: 24px;
  padding: 13px 17px 11px;
  background: #fff;
}
.composer textarea {
  border: 0;
  outline: 0;
  resize: none;
  width: 100%;
  min-height: 45px;
  max-height: 120px;
  color: #20213e;
  font-size: 17px;
  line-height: 1.5;
}
.composer textarea::placeholder {
  color: #aaaab5;
}
.composer-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.composer-bar > button {
  padding: 6px 0;
}
.composer-bar > span {
  display: flex;
  align-items: center;
  gap: 7px;
}
.composer-bar button {
  width: 38px;
  height: 38px;
  border-radius: 10px;
}
.composer-bar .reference-icon {
  width: 29px;
  height: 29px;
}
.composer-bar .submit {
  border: 2px solid #171926;
}
.composer-bar .submit:disabled {
  opacity: 0.28;
  cursor: not-allowed;
}
.composer-dock > small {
  display: block;
  color: #d2d2d7;
  text-align: center;
  margin-top: 7px;
  font-size: 11px;
}
.service-note {
  color: #777b8a;
  font-size: 12px;
  margin-top: 0;
  margin-bottom: 8px;
}
.service-note.error {
  color: #be4141;
}
.service-note button {
  border: 0;
  background: none;
  color: #167fc5;
  text-decoration: underline;
}
@media (max-width: 767px) {
  .kuku-header {
    height: 84px;
    padding-inline: 16px;
  }
  .mode-label {
    font-size: 20px;
  }
  .header-actions {
    gap: 5px;
  }
  .kuku-scroll {
    padding: 18px 16px 28px;
  }
  .tool-grid {
    gap: 10px;
  }
  .tool-card {
    min-height: 126px;
    border-radius: 18px;
    padding: 20px 17px;
  }
  .tool-icon {
    margin-bottom: 14px;
  }
  .tool-card strong {
    font-size: 16px;
  }
  .tool-card span {
    font-size: 13px;
  }
  .assistant-message {
    padding: 20px 18px;
    font-size: 15px;
    border-radius: 18px;
  }
  .composer-dock {
    padding-inline: 14px;
  }
  .composer {
    border-radius: 22px;
  }
  .network-toggle {
    margin-left: 0;
  }
}
@media (max-width: 390px) {
  .tool-card {
    min-height: 116px;
    padding: 17px 14px;
  }
  .tool-card strong {
    font-size: 15px;
  }
  .tool-card span {
    font-size: 12px;
  }
  .plain-icon svg {
    width: 25px;
    height: 25px;
  }
}
@media (prefers-reduced-motion: no-preference) {
  .tool-card {
    transition:
      transform 0.18s ease,
      box-shadow 0.18s ease;
  }
}
.network-toggle {
  height: 44px;
  padding: 7px 15px;
}
.network-toggle .reference-icon {
  width: 21px;
  height: 21px;
}
.welcome-mode {
  background: #fff;
  color: #0d1422;
}
.welcome-mode .kuku-header {
  background: #fff;
}
.mode-switch,
.more-wrap {
  position: relative;
}
.mode-popover {
  position: absolute;
  z-index: 20;
  top: calc(100% + 10px);
  left: 50%;
  transform: translateX(-50%);
  width: 240px;
  padding: 7px;
  border: 1px solid #e8ebf0;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 16px 46px rgba(20, 30, 48, 0.14);
}
.mode-popover > button {
  width: 100%;
  border: 0;
  border-radius: 11px;
  background: transparent;
  padding: 11px;
  display: flex;
  align-items: center;
  gap: 11px;
  color: #172039;
  text-align: left;
}
.mode-popover > button.selected,
.mode-popover > button:hover {
  background: #f2f9ff;
}
.mode-popover span {
  flex: 1;
}
.mode-popover b,
.mode-popover small {
  display: block;
}
.mode-popover b {
  font-size: 14px;
}
.mode-popover small {
  margin-top: 4px;
  color: #9198a5;
  font-size: 11px;
}
.more-menu {
  position: absolute;
  z-index: 20;
  right: 0;
  top: calc(100% + 12px);
  width: 190px;
  padding: 12px;
  background: #fff;
  border-radius: 0 0 13px 13px;
  box-shadow: 0 16px 45px rgba(20, 30, 48, 0.12);
}
.more-menu button {
  width: 100%;
  height: 48px;
  border: 0;
  background: #fff;
  color: #171d2c;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 12px;
  font-size: 15px;
}
.more-menu button:hover {
  background: #f6f8fb;
}
.more-menu svg {
  width: 21px;
}
.kuku-welcome {
  position: relative;
  width: min(840px, 100%);
  height: 100%;
  min-height: 620px;
  margin: 0 auto;
  padding: 270px 10px 40px;
}
.hello-copy {
  position: relative;
  z-index: 2;
}
.hello-copy h1 {
  margin: 0;
  font-size: clamp(72px, 12vw, 112px);
  font-weight: 500;
  line-height: 0.95;
  letter-spacing: -5px;
  color: #050505;
}
.hello-copy h2 {
  margin: 24px 0 45px;
  font-size: clamp(28px, 4vw, 40px);
  font-weight: 650;
  letter-spacing: -1.5px;
  color: #050505;
}
.orb {
  position: absolute;
  z-index: 1;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
  background:
    radial-gradient(
      circle at 78% 27%,
      rgba(255, 255, 255, 0.98) 0 5%,
      rgba(213, 255, 252, 0.9) 9%,
      rgba(68, 238, 232, 0.85) 18%,
      rgba(44, 202, 255, 0.82) 25%,
      rgba(255, 255, 255, 0) 26%
    ),
    radial-gradient(
      circle at 70% 24%,
      rgba(110, 255, 216, 0.72),
      rgba(33, 203, 255, 0.55) 22%,
      rgba(255, 255, 255, 0) 29%
    );
  filter: blur(5px) saturate(1.1);
  pointer-events: none;
  animation: orb-float 5.5s ease-in-out infinite;
}
.orb:before {
  content: "";
  position: absolute;
  right: 5%;
  top: 7%;
  width: min(34vw, 290px);
  aspect-ratio: 1;
  border-radius: 48% 52% 60% 40%;
  border: 12px solid rgba(201, 255, 249, 0.36);
  transform: rotate(-25deg);
}
.orb i {
  position: absolute;
  border-radius: 50%;
  filter: blur(10px);
}
.orb i:nth-child(1) {
  width: 95px;
  height: 95px;
  right: 20%;
  top: 19%;
  background: rgba(51, 255, 211, 0.32);
}
.orb i:nth-child(2) {
  width: 115px;
  height: 115px;
  right: 5%;
  top: 7%;
  background: rgba(102, 221, 255, 0.35);
}
.orb i:nth-child(3) {
  width: 20px;
  height: 7px;
  right: 24%;
  top: 23%;
  background: #fff;
}
.suggestion-list {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 18px;
}
.suggestion-list button {
  min-height: 58px;
  max-width: 100%;
  padding: 0 27px;
  border: 1px solid #e5e8ec;
  border-radius: 16px;
  background: #fff;
  color: #111827;
  font-size: 18px;
  text-align: left;
}
.suggestion-list button:first-child {
  font-weight: 600;
}
.quick-tools {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  scrollbar-width: none;
  padding: 2px 0 4px;
  scroll-snap-type: x proximity;
}
.quick-tools::-webkit-scrollbar {
  display: none;
}
.quick-tools button {
  flex: 0 0 auto;
  height: 47px;
  padding: 0 18px;
  border: 1px solid #e2e5e9;
  border-radius: 14px;
  background: #fff;
  color: #161c29;
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 16px;
  scroll-snap-align: start;
}
.quick-tools svg {
  width: 20px;
  height: 20px;
}
@keyframes orb-float {
  0%,
  100% {
    transform: translate3d(0, 0, 0) rotate(-2deg);
  }
  50% {
    transform: translate3d(-10px, -13px, 0) rotate(3deg);
  }
}
@media (max-width: 767px) {
  .header-actions {
    gap: 0;
  }
  .header-actions .plain-icon {
    width: 38px;
  }
  .kuku-welcome {
    min-height: 570px;
    padding: 215px 10px 30px;
  }
  .hello-copy h1 {
    font-size: 72px;
  }
  .hello-copy h2 {
    margin: 18px 0 34px;
    font-size: 27px;
  }
  .suggestion-list {
    gap: 13px;
  }
  .suggestion-list button {
    min-height: 53px;
    padding: 0 20px;
    border-radius: 14px;
    font-size: 16px;
  }
  .quick-tools {
    margin-inline: -2px;
  }
  .quick-tools button {
    height: 43px;
    padding: 0 15px;
    font-size: 14px;
  }
  .more-menu {
    right: -2px;
  }
  .mode-popover {
    width: 218px;
  }
}
@media (max-height: 760px) {
  .kuku-welcome {
    padding-top: 175px;
    min-height: 500px;
  }
  .hello-copy h1 {
    font-size: 64px;
  }
  .hello-copy h2 {
    font-size: 24px;
    margin-bottom: 26px;
  }
  .suggestion-list {
    gap: 10px;
  }
  .suggestion-list button {
    min-height: 45px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .orb,
  .thinking-orb,
  .processing-orb {
    animation: none;
  }
}
@media (768px <= width < 1024px) {
  .kuku-header {
    padding-inline: 28px;
  }
  .kuku-scroll {
    padding-inline: 28px;
  }
  .kuku-welcome {
    width: min(760px, 100%);
    padding-top: 245px;
  }
  .tool-grid {
    width: min(760px, 100%);
    gap: 14px;
  }
  .tool-card {
    min-height: 142px;
    padding: 23px;
  }
  .conversation {
    width: min(720px, 100%);
  }
  .composer-dock {
    padding-inline: 28px;
  }
  .quick-tools button {
    height: 45px;
    font-size: 15px;
  }
}
@media (width >= 1024px) {
  .kuku-header {
    width: min(1180px, 100%);
    margin-inline: auto;
  }
  .kuku-scroll {
    padding-top: 32px;
  }
  .kuku-welcome {
    width: min(1080px, 100%);
    min-height: 600px;
    padding: 245px 40px 50px;
  }
  .hello-copy h1 {
    font-size: 104px;
  }
  .hello-copy h2 {
    font-size: 38px;
  }
  .suggestion-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 340px));
    gap: 15px;
  }
  .suggestion-list button {
    width: 100%;
  }
  .tool-grid {
    width: min(1120px, 100%);
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px;
  }
  .tool-card {
    min-height: 178px;
    padding: 28px 24px;
  }
  .explore-more {
    grid-column: 1/-1;
  }
  .conversation {
    width: min(840px, 100%);
  }
  .quick-tools {
    width: min(980px, 100%);
  }
  .composer-dock > *:not(.quick-tools) {
    max-width: 840px;
  }
}
@media (width < 430px) {
  .kuku-header {
    padding-inline: 10px;
  }
  .mode-label {
    font-size: 18px;
    padding-inline: 5px;
  }
  .header-actions .plain-icon {
    width: 34px;
  }
  .header-actions .plain-icon > svg {
    width: 23px;
    height: 23px;
  }
  .kuku-scroll {
    padding-inline: 12px;
  }
  .kuku-welcome {
    padding-inline: 4px;
  }
  .hello-copy h1 {
    font-size: 64px;
  }
  .hello-copy h2 {
    font-size: 24px;
    letter-spacing: -1px;
  }
  .suggestion-list button {
    font-size: 15px;
  }
  .composer-dock {
    padding-inline: 10px;
  }
  .composer {
    padding-inline: 14px;
  }
  .tool-card {
    padding-inline: 13px;
  }
  .tool-card strong {
    font-size: 14px;
  }
  .tool-card span {
    font-size: 11px;
  }
}
@media (orientation: landscape) and (height < 620px) {
  .kuku-header {
    height: 68px;
    padding-top: 4px;
  }
  .kuku-scroll {
    padding-top: 8px;
  }
  .kuku-welcome {
    min-height: 390px;
    padding: 105px 12px 20px;
  }
  .hello-copy h1 {
    font-size: 54px;
  }
  .hello-copy h2 {
    margin: 10px 0 18px;
    font-size: 21px;
  }
  .suggestion-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 290px));
    gap: 8px;
  }
  .suggestion-list button {
    min-height: 40px;
    font-size: 14px;
  }
  .composer-dock {
    padding-top: 7px;
  }
  .quick-tools button {
    height: 38px;
  }
  .composer {
    margin-top: 6px;
    padding-top: 8px;
    padding-bottom: 7px;
  }
  .composer textarea {
    min-height: 32px;
  }
}
</style>
