<script setup lang="ts">
import { computed } from "vue";
import { marked } from "marked";
import DOMPurify from "dompurify";
const props = defineProps<{ content: string }>();
const html = computed(() => DOMPurify.sanitize(marked.parse(props.content, { async: false, breaks: true }), {
  USE_PROFILES: { html: true }, FORBID_TAGS: ["img", "iframe", "form", "input", "button", "style"],
}));
</script>

<template><div class="ai-markdown" v-html="html" /></template>

<style scoped>
.ai-markdown { line-height: 1.8; overflow-wrap: anywhere; }
.ai-markdown :deep(p) { margin: 0 0 .75em; }
.ai-markdown :deep(p:last-child) { margin-bottom: 0; }
.ai-markdown :deep(pre) { padding: 14px; background: #f0f4f6; border-radius: 10px; overflow: auto; white-space: pre; }
.ai-markdown :deep(code) { font-size: .88em; background: #f0f4f6; border-radius: 4px; padding: 2px 4px; }
.ai-markdown :deep(pre code) { padding: 0; }
.ai-markdown :deep(a) { color: #007e92; text-decoration: underline; }
.ai-markdown :deep(table) { display: block; max-width: 100%; overflow: auto; border-collapse: collapse; }
.ai-markdown :deep(th), .ai-markdown :deep(td) { border: 1px solid #dce4e7; padding: 6px 10px; }
.ai-markdown :deep(ul), .ai-markdown :deep(ol) { padding-left: 24px; }
.ai-markdown :deep(blockquote) { margin: 10px 0; border-left: 3px solid #43c8de; padding-left: 14px; color: #596571; }
</style>
