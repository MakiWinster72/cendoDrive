<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Check, Copy, Link2, X } from "@lucide/vue";
import { shareClipboardText, shareExpiryLabel, type ShareRecord } from "../api/shares";

const props = defineProps<{ share: ShareRecord | null }>();
const emit = defineEmits<{ close: [] }>();
const copied = ref(false);
const codeCopied = ref(false);
const copyError = ref("");
const link = computed(() =>
  props.share
    ? `${window.location.origin}/share/${encodeURIComponent(props.share.token)}`
    : "",
);
const active = computed(
  () =>
    props.share?.status === "ACTIVE" &&
    new Date(props.share.expiresAt) > new Date(),
);

watch(
  () => props.share?.id,
  () => {
    copied.value = false;
    codeCopied.value = false;
    copyError.value = "";
  },
);

async function copyCode() {
  if (!active.value || !props.share?.extractionCode) return;
  copyError.value = "";
  try {
    await navigator.clipboard.writeText(props.share.extractionCode);
    codeCopied.value = true;
  } catch { codeCopied.value = false; copyError.value = "复制失败，请选中提取码手动复制"; }
}
async function copyLink() {
  if (!props.share || !active.value) return;
  copyError.value = "";
  try {
    await navigator.clipboard.writeText(shareClipboardText(props.share, window.location.origin));
    copied.value = true;
  } catch {
    copied.value = false;
    copyError.value = "复制失败，请选中链接手动复制";
  }
}
</script>

<template>
  <Teleport to="body">
  <div v-if="share" class="share-dialog-backdrop" @click.self="emit('close')">
    <section
      class="share-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="share-dialog-title"
    >
      <header>
        <div>
          <span class="share-dialog-icon"><Link2 :size="19" /></span>
          <h2 id="share-dialog-title">
            {{
              active
                ? "分享链接"
                : share.status === "CANCELLED"
                  ? "分享已取消"
                  : "分享已过期"
            }}
          </h2>
        </div>
        <button
          class="share-dialog-close"
          type="button"
          aria-label="关闭"
          @click="emit('close')"
        >
          <X :size="19" />
        </button>
      </header>
      <p class="share-dialog-file">{{ share.fileName }}</p>
      <label for="share-link-value">{{
        active ? "访客可以通过此链接访问" : "此链接已经失效"
      }}</label>
      <div class="share-link-value">
        <input
          id="share-link-value"
          :value="link"
          readonly
          @focus="($event.target as HTMLInputElement).select()"
        /><button type="button" :disabled="!active" @click="copyLink">
          <Check v-if="copied" :size="17" /><Copy v-else :size="17" />{{
            copied ? "已复制" : "复制链接"
          }}
        </button>
      </div>
      <div v-if="share.extractionCode" class="share-dialog-code">
        <label for="share-code-value">提取码</label>
        <div class="share-link-value">
          <input id="share-code-value" :value="share.extractionCode" readonly @focus="($event.target as HTMLInputElement).select()" />
          <button type="button" :disabled="!active" @click="copyCode"><Check v-if="codeCopied" :size="17" /><Copy v-else :size="17" />{{ codeCopied ? '已复制提取码' : '复制提取码' }}</button>
        </div>
      </div>
      <p v-else-if="share.hasExtractionCode" class="share-dialog-code">此历史分享未保存可回显的提取码。请使用原提取码，或重新创建分享以显示并复制提取码。</p>
      <p v-if="copyError" class="share-dialog-error" role="alert">{{ copyError }}</p>
      <p class="share-dialog-expiry">
        {{ shareExpiryLabel(share.expiresAt) }}
      </p>
      <footer>
        <button type="button" @click="emit('close')">完成</button>
      </footer>
    </section>
  </div>
  </Teleport>
</template>

<style scoped>
.share-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: grid;
  place-items: center;
  box-sizing: border-box;
  padding: max(12px, env(safe-area-inset-top)) max(12px, env(safe-area-inset-right)) max(12px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left));
  overflow-y: auto;
  background: rgba(17, 29, 52, 0.42);
  backdrop-filter: blur(4px);
}
.share-dialog {
  box-sizing: border-box;
  min-width: 0;
  width: min(480px, 100%);
  max-height: calc(100dvh - 24px);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 25px;
  border: 1px solid #e7edf6;
  border-radius: 18px;
  background: #fff;
  color: #19243a;
  box-shadow: 0 24px 70px rgba(22, 44, 83, 0.22);
}
.share-dialog header,
.share-dialog header > div {
  display: flex;
  align-items: center;
}
.share-dialog header {
  justify-content: space-between;
}
.share-dialog header > div {
  gap: 11px;
}
.share-dialog h2 {
  margin: 0;
  font-size: 19px;
}
.share-dialog-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: #eaf2ff;
  color: #316cff;
}
.share-dialog-close {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: 0;
  border-radius: 9px;
  background: #f3f6fb;
  color: #64728a;
}
.share-dialog-file {
  margin: 19px 0 6px;
  font-weight: 700;
  overflow-wrap: anywhere;
}
.share-dialog label,
.share-dialog-expiry {
  color: #8290a5;
  font-size: 12px;
}
.share-link-value {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.share-link-value input {
  min-width: 0;
  flex: 1;
  height: 44px;
  padding: 0 11px;
  border: 1px solid #dce4f1;
  border-radius: 8px;
  background: #f8faff;
  color: #33415b;
}
.share-link-value button,
.share-dialog footer button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 44px;
  padding: 0 13px;
  border: 0;
  border-radius: 8px;
  background: #316cff;
  color: #fff;
  font-weight: 650;
  white-space: nowrap;
}
.share-dialog-code { color: #33415b; font-size: 13px; overflow-wrap: anywhere; }
.share-dialog-expiry {
  margin: 12px 0 0;
}
.share-dialog footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 21px;
}
@media (max-width: 400px) {
  .share-dialog { padding: 18px; }
  .share-link-value { flex-direction: column; }
  .share-link-value input { flex: none; box-sizing: border-box; width: 100%; }
  .share-link-value button { width: 100%; }
}
.share-dialog-error { color: #b42318; font-size: 12px; }
.share-link-value button:disabled {
  background: #c5cedb;
  cursor: not-allowed;
}
</style>
