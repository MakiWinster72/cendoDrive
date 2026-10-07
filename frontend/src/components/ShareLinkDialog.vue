<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Check, Copy, Link2, X } from "lucide-vue-next";
import type { ShareRecord } from "../api/shares";

const props = defineProps<{ share: ShareRecord | null }>();
const emit = defineEmits<{ close: [] }>();
const copied = ref(false);
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
  },
);

async function copyLink() {
  if (!active.value) return;
  try {
    await navigator.clipboard.writeText(link.value);
    copied.value = true;
  } catch {
    copied.value = false;
  }
}
</script>

<template>
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
      <p class="share-dialog-expiry">
        有效期至
        {{
          new Date(share.expiresAt).toLocaleString("zh-CN", { hour12: false })
        }}
      </p>
      <footer>
        <button type="button" @click="emit('close')">完成</button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.share-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(17, 29, 52, 0.42);
  backdrop-filter: blur(4px);
}
.share-dialog {
  width: min(480px, 100%);
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
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 9px;
  background: #f3f6fb;
  color: #64728a;
}
.share-dialog-file {
  margin: 19px 0 6px;
  font-weight: 700;
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
  height: 42px;
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
  height: 42px;
  padding: 0 13px;
  border: 0;
  border-radius: 8px;
  background: #316cff;
  color: #fff;
  font-weight: 650;
  white-space: nowrap;
}
.share-dialog-expiry {
  margin: 12px 0 0;
}
.share-dialog footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 21px;
}
.share-link-value button:disabled {
  background: #c5cedb;
  cursor: not-allowed;
}
</style>
