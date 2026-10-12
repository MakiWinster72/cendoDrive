<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { X } from "@lucide/vue";
import { useDrive } from "../stores/drive";
import { driveErrorMessage } from "../api/drive";
import "../styles/file-tools.css";
import "../styles/trash-confirm.css";
const props = defineProps<{ ids: string[]; name?: string }>();
const emit = defineEmits<{ close: []; trashed: [] }>();
const dialog = ref<HTMLDialogElement>();
const busy = ref(false),
  error = ref("");
const drive = useDrive();
let alive = true,
  previousOverflow = "";
function close() {
  if (!busy.value) emit("close");
}
async function submit() {
  if (busy.value || !props.ids.length) return;
  busy.value = true;
  error.value = "";
  try {
    await drive.trash([...props.ids]);
    if (alive) emit("trashed");
  } catch (cause) {
    if (alive) error.value = driveErrorMessage(cause, "移入失败，请重试");
  } finally {
    if (alive) busy.value = false;
  }
}
onMounted(() => {
  previousOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  dialog.value?.showModal();
});
onBeforeUnmount(() => {
  alive = false;
  dialog.value?.close();
  document.body.style.overflow = previousOverflow;
});
</script>
<template>
  <dialog
    ref="dialog"
    class="file-tools trash-confirm"
    aria-labelledby="trash-confirm-title"
    aria-describedby="trash-confirm-target trash-confirm-hint"
    :aria-busy="busy"
    @cancel.prevent="close"
    @click.self="close"
  >
    <header>
      <h2 id="trash-confirm-title">移入回收站？</h2>
      <button
        type="button"
        aria-label="关闭回收站确认"
        :disabled="busy"
        @click="close"
      >
        <X :size="22" />
      </button>
    </header>
    <p id="trash-confirm-target" class="trash-confirm-target" :title="name">
      {{ ids.length === 1 && name ? name : `已选 ${ids.length} 项` }}
    </p>
    <p id="trash-confirm-hint">移入后可在回收站恢复。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <footer>
      <button type="button" :disabled="busy" autofocus @click="close">
        取消
      </button>
      <button
        type="button"
        class="trash-confirm-submit"
        :disabled="busy || !ids.length"
        @click="submit"
      >
        {{ busy ? "处理中…" : "移入回收站" }}
      </button>
    </footer>
  </dialog>
</template>
