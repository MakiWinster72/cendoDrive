<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { SplashAdContent } from "../api/content";
import "../styles/splash-ad.css";

const props = defineProps<{ ad: SplashAdContent | null; demo?: boolean }>();
const emit = defineEmits<{ close: []; open: [targetUrl: string] }>();
const remaining = ref(0);
const elapsed = ref(0);
const imageFailed = ref(false);
const canSkip = computed(() => !props.ad || elapsed.value >= props.ad.skipAfterSeconds);
let timer: number | undefined;

watch(() => props.ad, (ad) => {
  if (timer) window.clearInterval(timer);
  imageFailed.value = false;
  elapsed.value = 0;
  remaining.value = ad?.displaySeconds ?? 0;
  if (!ad) return;
  if (ad.displaySeconds <= 0) {
    emit("close");
    return;
  }
  timer = window.setInterval(() => {
    elapsed.value += 1;
    remaining.value = Math.max(0, ad.displaySeconds - elapsed.value);
    if (remaining.value === 0) emit("close");
  }, 1000);
}, { immediate: true });

function openAd() {
  if (props.ad?.targetUrl) emit("open", props.ad.targetUrl);
  emit("close");
}

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
});
</script>

<template>
  <Teleport to="body">
    <section v-if="ad" class="splash-ad-overlay" role="dialog" aria-modal="true" aria-label="开屏广告">
      <button class="splash-ad-content" @click="openAd">
        <img v-if="!imageFailed" :src="ad.imageUrl" :alt="ad.title" @error="imageFailed = true" />
        <span v-else class="splash-ad-image-fallback">CendoDrive</span>
        <span class="splash-ad-title">{{ ad.title }}</span>
      </button>
      <button class="splash-ad-skip" :disabled="!canSkip" @click="emit('close')">
        {{ canSkip ? "跳过" : `${remaining} 秒后可跳过` }}
      </button>
      <span v-if="demo" class="splash-ad-demo-badge">演示广告</span>
    </section>
  </Teleport>
</template>
