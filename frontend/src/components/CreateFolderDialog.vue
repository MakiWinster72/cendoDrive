<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { X } from "lucide-vue-next";
import { driveErrorMessage } from "../api/drive";
import { useDrive, type DriveItem } from "../stores/drive";
import "../styles/file-tools.css";
const props = defineProps<{ parentId: string | null; parentLabel: string }>();
const emit = defineEmits<{ close: []; created: [item: DriveItem] }>();
const drive = useDrive(), dialog = ref<HTMLDialogElement>();
const name = ref(""), error = ref(""), busy = ref(false);
let alive = true, previousOverflow = "", ownsScrollLock = false;
function close() { if (!busy.value) emit("close"); }
async function submit() {
  if (busy.value) return;
  const value = name.value.trim();
  if (!value || value === "." || value === ".." || value.includes("/") || value.includes("\\") || value.length > 255) {
    error.value = "请输入 1–255 个字符，名称不能是 .、.. 或包含 /、\\。";
    return;
  }
  busy.value = true; error.value = "";
  try {
    const item = await drive.createFolder(value, props.parentId);
    if (alive) { emit("created", item); emit("close"); }
  } catch (cause) {
    if (alive) error.value = driveErrorMessage(cause, "文件夹创建失败，请重试");
  } finally { if (alive) busy.value = false; }
}
onMounted(() => {
  previousOverflow = document.body.style.overflow;
  ownsScrollLock = !document.querySelector("dialog[open]");
  if (ownsScrollLock) document.body.style.overflow = "hidden";
  dialog.value?.showModal();
});
onBeforeUnmount(() => {
  alive = false;
  dialog.value?.close();
  if (ownsScrollLock) document.body.style.overflow = previousOverflow;
});
</script>
<template>
  <dialog ref="dialog" class="file-tools create-folder-dialog" aria-labelledby="create-folder-title" @cancel.prevent.stop="close" @click.self="close">
    <header><h2 id="create-folder-title">新建文件夹</h2><button type="button" aria-label="关闭新建文件夹" :disabled="busy" @click="close"><X :size="22" /></button></header>
    <p class="tools-selection">创建位置：{{ parentLabel }}</p>
    <form class="create-folder-form" @submit.prevent="submit">
      <label>文件夹名称<input v-model="name" name="folderName" autofocus autocomplete="off" maxlength="255" :disabled="busy" aria-describedby="create-folder-help" :aria-invalid="!!error" /></label>
      <small id="create-folder-help" class="tools-selection">名称不能包含路径分隔符，同名目录不会被覆盖。</small>
      <p v-if="error" role="alert">{{ error }}</p>
      <footer><button type="button" :disabled="busy" @click="close">取消</button><button type="submit" class="tools-primary" :disabled="busy">{{ busy ? "创建中…" : "创建文件夹" }}</button></footer>
    </form>
  </dialog>
</template>
