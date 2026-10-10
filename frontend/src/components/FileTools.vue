<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  X,
  FolderInput,
  Copy,
  Star,
  EyeOff,
  Info,
  Sparkles,
  Pencil,
  Trash2,
} from "@lucide/vue";
import {
  getFileDetails,
  listFolders,
  driveErrorMessage,
  type FileDetails,
} from "../api/drive";
import { formatBytes, useDrive, type DriveItem } from "../stores/drive";
import { folderChoices, type ToolAction } from "./fileTools";
import "../styles/file-tools.css";
import InlineNameEditor from "./InlineNameEditor.vue";
import { useNameEdit } from "./useNameEdit";
const props = defineProps<{ items: DriveItem[]; initialAction: ToolAction }>();
const emit = defineEmits<{
  close: [];
  changed: [message: string];
  rename: [];
  trash: [ids: string[]];
}>();
const drive = useDrive(),
  dialog = ref<HTMLDialogElement>();
const action = ref<ToolAction>(props.initialAction),
  folders = ref<DriveItem[]>([]);
const target = ref<string | null>(null),
  details = ref<FileDetails | null>(null);
const {
  edit: targetEdit,
  saving: targetSaving,
  error: targetError,
  beginCreate: beginTarget,
  submit: saveTarget,
  cancel: cancelTarget,
} = useNameEdit(targetCreated);
function createTarget() {
  const choice = choices.value.find(
    (entry) => entry.id === target.value && !entry.disabled,
  );
  if (choice)
    beginTarget(
      choice.id,
      folders.value
        .filter((item) => item.parentId === choice.id)
        .map((item) => item.name),
    );
}
async function targetCreated(item: DriveItem) {
  target.value = item.id;
  await choose(action.value);
}
const busy = ref(false),
  loading = ref(false),
  error = ref("");
const ids = computed(() => props.items.map((item) => item.id));
const choices = computed(() => folderChoices(folders.value, ids.value));
const targetCreationLabel = computed(() => {
  const draft = targetEdit.value;
  return draft?.kind === "create"
    ? choices.value.find((choice) => choice.id === draft.parentId)?.label
    : "";
});
const favoriteValue = computed(
  () => !props.items.every((item) => item.favorite),
);
const hiddenValue = computed(() => !props.items.every((item) => item.hidden));
const titles = computed(() => ({
  menu: "文件操作",
  move: "移动",
  copy: "复制",
  favorite: favoriteValue.value ? "收藏" : "取消收藏",
  hide: hiddenValue.value ? "移入隐藏空间" : "移出隐藏空间",
  organize: "按类型整理",
  details: "文件详情",
}));
const menu = [
  { key: "move", icon: FolderInput },
  { key: "copy", icon: Copy },
  { key: "favorite", icon: Star },
  { key: "hide", icon: EyeOff },
  { key: "organize", icon: Sparkles },
  { key: "details", icon: Info },
] as const;
let alive = true,
  previousOverflow = "";
function close() {
  if (busy.value || targetSaving.value) return;
  if (targetEdit.value) cancelTarget();
  else emit("close");
}
async function choose(next: ToolAction) {
  cancelTarget();
  action.value = next;
  error.value = "";
  loading.value = false;
  if (next !== "move" && next !== "copy" && next !== "details") return;
  loading.value = true;
  try {
    if (next === "details") {
      if (props.items.length !== 1) throw new Error("请选择一个项目查看详情");
      const result = await getFileDetails(ids.value[0]!);
      if (alive) details.value = result;
    } else {
      const result = await listFolders();
      if (alive) folders.value = result;
    }
  } catch (cause) {
    if (alive) error.value = driveErrorMessage(cause, "加载失败，请重试");
  } finally {
    if (alive) loading.value = false;
  }
}
async function submit() {
  if (
    busy.value ||
    targetSaving.value ||
    targetEdit.value ||
    loading.value ||
    error.value
  )
    return;
  if (
    (action.value === "move" || action.value === "copy") &&
    !choices.value.some(
      (choice) => choice.id === target.value && !choice.disabled,
    )
  )
    return;
  busy.value = true;
  error.value = "";
  try {
    if (action.value === "move") await drive.moveMany(ids.value, target.value);
    else if (action.value === "copy") await drive.copy(ids.value, target.value);
    else if (action.value === "favorite")
      await drive.favorite(ids.value, favoriteValue.value);
    else if (action.value === "hide")
      await drive.hide(ids.value, hiddenValue.value);
    else if (action.value === "organize") await drive.organize(ids.value);
    else return;
    if (alive) {
      emit("changed", "操作完成");
      emit("close");
    }
  } catch (cause) {
    if (alive) error.value = driveErrorMessage(cause, "文件操作失败，请重试");
  } finally {
    if (alive) busy.value = false;
  }
}
function rename() {
  emit("rename");
  emit("close");
}
function trash() {
  emit("trash", ids.value);
  emit("close");
}
onMounted(() => {
  previousOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  dialog.value?.showModal();
  void choose(action.value);
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
    class="file-tools"
    aria-labelledby="file-tools-title"
    @cancel.prevent="close"
    @click.self="close"
  >
    <header>
      <h2 id="file-tools-title">{{ titles[action] }}</h2>
      <button
        type="button"
        aria-label="关闭文件操作"
        :disabled="busy || targetSaving"
        autofocus
        @click="close"
      >
        <X :size="22" />
      </button>
    </header>
    <p class="tools-selection">
      已选择 {{ items.length }} 个项目<span v-if="items.length === 1">
        · {{ items[0]?.name }}</span
      >
    </p>
    <div v-if="action === 'menu'" class="tools-menu">
      <button
        v-for="entry in menu"
        :key="entry.key"
        :disabled="entry.key === 'details' && items.length !== 1"
        @click="choose(entry.key)"
      >
        <component :is="entry.icon" :size="21" />{{ titles[entry.key] }}
      </button>
      <button :disabled="items.length !== 1" @click="rename">
        <Pencil :size="21" />重命名</button
      ><button @click="trash"><Trash2 :size="21" />移入回收站</button>
    </div>
    <p v-if="loading" role="status">正在加载…</p>
    <div v-if="error" role="alert">
      <p>{{ error }}</p>
      <button :disabled="busy || targetSaving" @click="choose(action)">
        重新加载 / 重试
      </button>
    </div>
    <template v-if="!loading && !error">
      <div v-if="action === 'move' || action === 'copy'" class="tools-target">
        <label
          >目标文件夹<select v-model="target" :disabled="busy || targetSaving">
            <option
              v-for="choice in choices"
              :key="choice.id ?? 'root'"
              :value="choice.id"
              :disabled="choice.disabled"
            >
              {{ choice.label }}
            </option>
          </select></label
        ><button
          :disabled="
            busy ||
            targetSaving ||
            loading ||
            !choices.some((choice) => choice.id === target && !choice.disabled)
          "
          @click="createTarget"
        >
          新建文件夹
        </button>
        <div v-if="targetEdit?.kind === 'create'" class="target-draft-row">
          <FolderInput :size="22" />
          <div>
            <small>创建位置：{{ targetCreationLabel }}</small
            ><InlineNameEditor
              v-model="targetEdit.name"
              :saving="targetSaving"
              :error="targetError"
              creating
              @save="saveTarget"
              @cancel="cancelTarget"
            />
          </div>
        </div>
        <small
          >在所选位置创建目录后自动选中，可直接确认移动或复制。不能选择自身或子目录，复制会占用额外容量。</small
        >
      </div>
      <p v-if="action === 'favorite'">
        {{
          favoriteValue
            ? "添加到我的收藏，不移动原文件。"
            : "取消所选项目的收藏。"
        }}
      </p>
      <p v-if="action === 'hide'">
        {{
          hiddenValue
            ? "从普通列表隐藏，可在隐藏空间管理。此功能不是加密或密码保护。"
            : "恢复在普通文件列表中显示。"
        }}
      </p>
      <p v-if="action === 'organize'">
        按扩展名归入图片、视频、音频、文档或其他文件夹。仅整理文件，不修改内容，不覆盖同名项目；文件夹保持原样。
      </p>
      <dl v-if="action === 'details' && details">
        <dt>路径</dt>
        <dd>{{ details.path }}</dd>
        <dt>内容大小</dt>
        <dd>{{ formatBytes(details.contentSize) }}</dd>
        <dt>文件 / 文件夹</dt>
        <dd>{{ details.fileCount }} / {{ details.folderCount }}</dd>
        <dt>创建时间</dt>
        <dd>{{ details.createdAt }}</dd>
        <dt>修改时间</dt>
        <dd>{{ details.file.updatedAt }}</dd>
        <dt>收藏 / 隐藏</dt>
        <dd>
          {{ details.file.favorite ? "是" : "否" }} /
          {{ details.file.hidden ? "是" : "否" }}
        </dd>
      </dl>
    </template>
    <footer>
      <button
        v-if="action !== 'menu'"
        :disabled="busy || targetSaving"
        @click="choose('menu')"
      >
        返回操作</button
      ><button :disabled="busy || targetSaving" @click="close">关闭</button
      ><button
        v-if="action !== 'menu' && action !== 'details'"
        class="tools-primary"
        :disabled="busy || targetSaving || !!targetEdit || loading || !!error"
        @click="submit"
      >
        {{ busy ? "处理中…" : "确认" + titles[action] }}
      </button>
    </footer>
  </dialog>
</template>
