<script setup lang="ts">
import { nextTick, ref, watch } from "vue";
import { CircleAlert, X } from "lucide-vue-next";
import "../styles/unavailable-dialog.css";

const props = defineProps<{
  open: boolean;
  message: string;
}>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLElement | null>(null);

watch(() => props.open, async (open) => {
  if (open) {
    await nextTick();
    dialog.value?.focus();
  }
});
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="unavailable-overlay" @click.self="emit('close')">
      <section
        ref="dialog"
        class="unavailable-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="unavailable-title"
        aria-describedby="unavailable-message"
        tabindex="-1"
        @keydown.esc="emit('close')"
      >
        <button class="unavailable-close" aria-label="关闭提示" @click="emit('close')"><X :size="18" /></button>
        <span class="unavailable-icon"><CircleAlert :size="25" /></span>
        <h2 id="unavailable-title">功能暂未开放</h2>
        <p id="unavailable-message">{{ message }}</p>
        <button class="unavailable-confirm" @click="emit('close')">我知道了</button>
      </section>
    </div>
  </Teleport>
</template>
