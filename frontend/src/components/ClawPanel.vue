<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import AiMessage from "./AiMessage.vue";
import { askClaw, getClawStatus, reindexClaw, type ClawAnswer, type ClawStatus } from "../api/claw";
const status = ref<ClawStatus>();
const question = ref("");
const pending = ref(false);
const indexing = ref(false);
const error = ref("");
const turns = ref<{ question: string; answer: ClawAnswer }[]>([]);
const controller = new AbortController();
let active: AbortController | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
const canSend = computed(() => status.value?.configured && !!question.value.trim() && question.value.length <= 2000 && !pending.value);
const labels: Record<string, string> = { READY: "已索引", PENDING: "等待索引", PROCESSING: "正在索引", RETRY: "等待重试", FAILED: "索引失败", UNSUPPORTED: "无法提取", NEEDS_REINDEX: "需要重建" };
async function refresh() {
  clearTimeout(timer);
  try { status.value = await getClawStatus(controller.signal); }
  catch { if (!controller.signal.aborted) error.value = "无法获取索引状态，请刷新重试。"; }
  finally { if (!controller.signal.aborted) timer = setTimeout(() => void refresh(), 10000); }
}
async function send() {
  if (!canSend.value) return;
  const text = question.value.trim();
  const request = new AbortController(); active = request;
  pending.value = true; error.value = "";
  try {
    const previous = turns.value.at(-1)?.question.slice(0, 300);
    const answer = await askClaw(text, previous, request.signal);
    if (!request.signal.aborted) { turns.value.push({ question: text, answer }); question.value = ""; }
  } catch { if (!request.signal.aborted) error.value = "文档问答失败，请检查索引状态后重试。问题已保留。"; }
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
onMounted(refresh);
onBeforeUnmount(() => { controller.abort(); stop(); clearTimeout(timer); });
</script>

<template>
  <section class="claw-panel" aria-label="扣扣Claw文件问答">
    <h1>扣扣Claw</h1>
    <p>根据网盘文件内容搜索文件</p>
    <p class="hint">上传文档后自动索引，提问即可查找正文并获得带来源的回答。支持 TXT、Markdown、CSV、PDF、DOCX，单文件最多 10 MB；扫描件暂不支持。</p>
    <div v-if="status" class="index-status">
      <p v-if="!status.configured" role="status">扣扣Claw尚未配置，请联系管理员。</p>
      <p v-else>已索引 {{ status.ready }} · 处理中 {{ status.pending }} · 失败 {{ status.failed }} · 无法提取 {{ status.unsupported }} · 需要重建 {{ status.needsReindex }}</p>
      <button type="button" @click="refresh">刷新状态</button>
      <details>
        <summary>文档索引与重试</summary>
        <p class="hint">重建会将文档内容发送到已配置的向量模型。点击下方按钮确认重建。</p>
        <button :disabled="!status.configured || indexing" @click="reindex()">确认重建已有文档索引</button>
        <ul><li v-for="file in status.files" :key="file.fileId"><span>{{ file.path }} · {{ labels[file.status] ?? file.status }}</span><button v-if="['FAILED', 'NEEDS_REINDEX'].includes(file.status)" :disabled="indexing || !status.configured" @click="reindex(file.fileId)">确认重试</button></li></ul>
      </details>
    </div>
    <p v-else role="status">正在获取文档索引状态…</p>
    <div class="claw-turns" aria-live="polite" :aria-busy="pending">
      <article v-for="(turn, i) in turns" :key="i">
        <h2>{{ turn.question }}</h2>
        <AiMessage :content="turn.answer.content" />
        <details v-for="source in turn.answer.sources" :key="source.citation" class="source">
          <summary>[{{ source.citation }}] {{ source.name }} · {{ source.path }}</summary>
          <p>{{ source.excerpt }}</p>
        </details>
      </article>
      <p v-if="pending" role="status">正在搜索文档并生成回答…</p>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <form @submit.prevent="send">
      <textarea v-model="question" aria-label="搜索网盘文档内容" placeholder="例如：哪些文件提到了项目预算？" maxlength="2000" rows="3" :readonly="pending" @keydown.enter="event => { if (!event.shiftKey && !event.isComposing) { event.preventDefault(); send(); } }" />
      <button v-if="pending" type="button" @click="stop">停止回答</button><button v-else type="submit" :disabled="!canSend">搜索并回答</button>
    </form>
  </section>
</template>

<style scoped>
.claw-panel { max-width: 740px; margin: 32px auto; color: #20282f; }
h1 { font-size: 30px; } h2 { font-size: 16px; white-space: pre-wrap; overflow-wrap: anywhere; }
.hint { font-size: 12px; color: #64717c; line-height: 1.9; }
button { background: #eaf7f9; color: #087e92; border: 1px solid #d4e4e8; border-radius: 10px; padding: 9px 14px; cursor: pointer; } button:disabled { opacity: .45; cursor: default; }
.index-status { background: #f5f9fa; border-radius: 14px; padding: 16px; font-size: 13px; }
details { margin-top: 14px; } summary { cursor: pointer; overflow-wrap: anywhere; } ul { padding-left: 18px; } li { margin: 12px 0; overflow-wrap: anywhere; } li button { margin-left: 8px; }
.claw-turns article { padding: 20px 0; border-bottom: 1px solid #e4ecef; } .source { font-size: 12px; color: #64717c; } .source p { white-space: pre-wrap; line-height: 1.7; overflow-wrap: anywhere; }
form { display: flex; flex-direction: column; align-items: flex-end; gap: 10px; margin-top: 24px; } textarea { width: 100%; box-sizing: border-box; border: 1px solid #d4e4e8; border-radius: 14px; padding: 16px; font: inherit; resize: vertical; } [role=alert] { color: #b13d3d; }
</style>
