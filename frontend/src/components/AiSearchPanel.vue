<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import { ArrowLeft, FileText, Search, Sparkles } from "lucide-vue-next";
import type { AiSearchHit } from "../api/aiSearchTypes";

const props = defineProps<{
  search: (query: string, signal: AbortSignal) => Promise<AiSearchHit[]>;
}>();
const emit = defineEmits<{
  back: [];
  open: [hit: AiSearchHit];
}>();

const query = ref("");
const submittedQuery = ref("");
const results = ref<AiSearchHit[]>([]);
const loading = ref(false);
const error = ref("");
let controller: AbortController | undefined;

async function runSearch(input: string) {
  const value = input.trim();
  if (!value) return;
  controller?.abort();
  const request = new AbortController();
  controller = request;
  submittedQuery.value = value;
  results.value = [];
  error.value = "";
  loading.value = true;
  try {
    const matches = await props.search(value, request.signal);
    if (!request.signal.aborted) results.value = matches;
  } catch {
    if (!request.signal.aborted) error.value = "搜索失败，请重试";
  } finally {
    if (controller === request) loading.value = false;
  }
}

function submit() { return runSearch(query.value); }
function refresh() { return runSearch(submittedQuery.value); }

onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <main class="ai-search-page">
    <div class="ai-search-shell">
      <header class="ai-search-header">
        <button type="button" class="ai-search-back" aria-label="返回网盘" @click="emit('back')">
          <ArrowLeft :size="20" />返回网盘
        </button>
        <span class="ai-search-brand"><Sparkles :size="20" />AI 搜索</span>
      </header>

      <section class="ai-search-intro">
        <h1>搜索文件中的内容</h1>
        <p>用一句话描述你要找的内容，查看匹配文件及相关片段。</p>
        <form class="ai-search-form" role="search" @submit.prevent="submit">
          <label class="ai-search-input">
            <Search :size="21" aria-hidden="true" />
            <span class="sr-only">搜索内容</span>
            <input v-model="query" type="search" placeholder="例如：哪份文档提到了缓存穿透？" autocomplete="off" />
          </label>
          <button type="submit" :disabled="!query.trim()">{{ loading ? "搜索中…" : "搜索" }}</button>
        </form>
      </section>

      <section v-if="submittedQuery" class="ai-search-results" aria-live="polite" :aria-busy="loading">
        <div class="ai-search-results-heading">
          <h2>“{{ submittedQuery }}”的搜索结果</h2>
          <button type="button" :disabled="loading" @click="refresh">刷新结果</button>
        </div>
        <p v-if="loading" role="status" class="ai-search-state">正在搜索文件内容…</p>
        <div v-else-if="error" role="alert" class="ai-search-state">
          <p>{{ error }}</p><button type="button" @click="refresh">重新搜索</button>
        </div>
        <p v-else-if="!results.length" class="ai-search-state">没有找到匹配文件，试试换一种说法。</p>
        <ul v-else class="ai-search-list">
          <li v-for="hit in results" :key="hit.fileId" class="ai-search-result">
            <div class="ai-search-file"><FileText :size="22" aria-hidden="true" /><h3>{{ hit.fileName }}</h3></div>
            <p v-for="(snippet, index) in hit.snippets" :key="index" class="ai-search-snippet">{{ snippet }}</p>
            <button type="button" @click="emit('open', hit)">打开文件</button>
          </li>
        </ul>
      </section>
    </div>
  </main>
</template>

<style scoped>
.ai-search-page { min-height: 100vh; background: #f7f9fc; color: #17243b; }
.ai-search-shell { width: min(900px, 100%); margin: 0 auto; padding: 24px 24px 64px; }
.ai-search-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.ai-search-back, .ai-search-brand { display: inline-flex; align-items: center; gap: 8px; }
.ai-search-back { border: 0; background: none; color: #3f526e; padding: 9px 0; }
.ai-search-brand { color: #2669d9; font-weight: 700; }
.ai-search-intro { margin: 72px 0 36px; }
.ai-search-intro h1 { margin: 0 0 12px; font-size: clamp(27px, 4vw, 38px); }
.ai-search-intro p { margin: 0 0 26px; color: #65748c; }
.ai-search-form { display: flex; gap: 10px; }
.ai-search-input { flex: 1; min-width: 0; display: flex; align-items: center; gap: 10px; padding: 0 16px; background: #fff; border: 1px solid #cdd9ea; border-radius: 12px; color: #7185a0; }
.ai-search-input:focus-within { border-color: #3986eb; box-shadow: 0 0 0 3px #3986eb21; }
.ai-search-input input { width: 100%; min-width: 0; height: 52px; border: 0; outline: 0; background: none; color: #17243b; }
.ai-search-form > button, .ai-search-result button, .ai-search-state button { border: 0; border-radius: 10px; background: #2877e5; color: #fff; padding: 0 24px; font-weight: 600; }
.ai-search-form > button:disabled { opacity: .55; cursor: not-allowed; }
.ai-search-results-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.ai-search-results h2 { font-size: 18px; margin: 0; overflow-wrap: anywhere; }
.ai-search-results-heading button { flex: none; border: 0; background: none; color: #2877e5; font-weight: 600; }
.ai-search-results-heading button:disabled { opacity: .55; cursor: not-allowed; }
.ai-search-state { margin: 0; padding: 32px; text-align: center; background: #fff; border-radius: 14px; color: #66758b; }
.ai-search-state button { min-height: 38px; margin-top: 10px; }
.ai-search-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; }
.ai-search-result { background: #fff; border: 1px solid #e6ebf3; border-radius: 14px; padding: 20px; }
.ai-search-file { display: flex; align-items: center; gap: 10px; color: #2877e5; }
.ai-search-file h3 { min-width: 0; overflow-wrap: anywhere; margin: 0; font-size: 16px; color: #17243b; }
.ai-search-snippet { margin: 12px 0 0 32px; color: #52627a; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.ai-search-result button { min-height: 36px; margin: 18px 0 0 32px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 640px) {
  .ai-search-shell { padding: 16px 16px 40px; }
  .ai-search-intro { margin: 52px 0 30px; }
  .ai-search-form { flex-direction: column; }
  .ai-search-form > button { min-height: 46px; }
  .ai-search-result { padding: 16px; }
  .ai-search-snippet, .ai-search-result button { margin-left: 0; }
}
</style>
