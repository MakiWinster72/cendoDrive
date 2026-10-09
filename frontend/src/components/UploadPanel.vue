<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import {
  Check,
  FileText,
  LoaderCircle,
  RotateCcw,
  Trash2,
  X,
} from "@lucide/vue";
import {
  isUploadCancelled,
  uploadErrorMessage,
  uploadFile,
} from "../api/files";
import {
  shouldUseChunkedUpload,
  uploadFileInChunks,
} from "../api/chunkedUpload";
import UploadActionIcon from "./UploadActionIcon.vue";
import { ChevronRight, ShieldCheck } from "@lucide/vue";
import type { DriveItem } from "../stores/drive";
import { useTransfers } from "../stores/transfers";

interface FolderOption {
  id: string | null;
  name: string;
}
const props = withDefaults(
  defineProps<{
    open: boolean;
    folderOptions?: FolderOption[];
    initialFolderId?: string | null;
  }>(),
  {
    folderOptions: () => [{ id: null, name: "Ula" }],
    initialFolderId: null,
  },
);
const emit = defineEmits<{ close: []; createFolder: []; uploaded: [item: DriveItem] }>();
type UploadType = "image" | "video" | "document" | "audio" | "other";
type UploadStatus =
  "waiting" | "preparing" | "uploading" | "success" | "failed" | "cancelled";
type UploadMode = "normal" | "chunked";
interface UploadTask {
  id: string;
  file: File;
  status: UploadStatus;
  progress: number;
  mode?: UploadMode;
  parentId?: string | null;
  error?: string;
  fileId?: string;
}

const panel = ref<HTMLElement>();
const fileInput = ref<HTMLInputElement>();
let previousFocus: HTMLElement | null = null;
watch(() => props.open, async (open) => {
  if (open) {
    previousFocus = document.activeElement as HTMLElement | null;
    await nextTick();
    panel.value?.focus();
  } else {
    previousFocus?.focus();
  }
}, { immediate: true });

function handleDialogKey(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    emit("close");
  }
  if (event.key !== "Tab") return;
  const buttons = [...(panel.value?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") || [])];
  const first = buttons[0];
  const last = buttons.at(-1);
  if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.value)) {
    event.preventDefault();
    first?.focus();
  }
}
const accept = ref("*/*");
const uploadTasks = ref<UploadTask[]>([]);
const transfers = useTransfers();
watch(uploadTasks, (tasks) => {
  for (const task of tasks) transfers.syncUpload({
    id: task.id, name: task.file.name, size: task.file.size,
    status: task.status, progress: task.progress, error: task.error, fileId: task.fileId,
  });
}, { deep: true, flush: "sync" });
const selectedType = ref<UploadType | null>(null);
const unavailableMessage = ref("");
const selectedFolderId = ref<string | null>(props.initialFolderId);
const folderPickerOpen = ref(false);
const controllers = new Map<string, AbortController>();
watch(
  () => props.initialFolderId,
  (folderId) => {
    if (!props.open || uploadTasks.value.length === 0)
      selectedFolderId.value = folderId;
  },
);
const selectedFiles = computed(() =>
  uploadTasks.value.filter((task) =>
    ["waiting", "failed", "cancelled"].includes(task.status),
  ),
);
const activeCount = computed(
  () =>
    uploadTasks.value.filter(
      (task) => task.status === "preparing" || task.status === "uploading",
    ).length,
);
const hasPending = computed(() =>
  uploadTasks.value.some((task) =>
    ["waiting", "failed", "cancelled"].includes(task.status),
  ),
);
const waitingCount = computed(
  () => uploadTasks.value.filter((task) => task.status === "waiting").length,
);
const retryCount = computed(
  () =>
    uploadTasks.value.filter(
      (task) => task.status === "failed" || task.status === "cancelled",
    ).length,
);
const uploadButtonText = computed(() => {
  if (waitingCount.value && retryCount.value)
    return `上传 ${waitingCount.value} 个 · 重试 ${retryCount.value} 个`;
  if (waitingCount.value) return `上传 ${waitingCount.value} 个`;
  if (retryCount.value) return `重试失败项（${retryCount.value}）`;
  return "正在上传…";
});
const selectedTypeName = computed(
  () =>
    ({
      image: "照片",
      video: "视频",
      document: "文档",
      audio: "音频",
      other: "其他文件",
    })[selectedType.value || "other"],
);
const selectedFolderName = computed(
  () =>
    props.folderOptions.find((folder) => folder.id === selectedFolderId.value)
      ?.name ||
    props.folderOptions[0]?.name ||
    "Ula",
);

async function chooseType(type: UploadType, typeAccept: string) {
  unavailableMessage.value = "";
  selectedType.value = type;
  accept.value = typeAccept;
  await nextTick();
  fileInput.value?.click();
}

function showUnavailable(name: string) {
  unavailableMessage.value = `${name}功能暂未开放`;
}

function handleFileSelection(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = [...(input.files || [])];
  uploadTasks.value.push(
    ...files.map((file, index) => ({
      id: `${file.name}-${file.lastModified}-${Date.now()}-${index}`,
      file,
      status: "waiting" as const,
      progress: 0,
    })),
  );
  input.value = "";
}

function removeFile(fileId: string) {
  const task = uploadTasks.value.find(({ id }) => id === fileId);
  if (!task || task.status === "uploading" || task.status === "success") return;
  uploadTasks.value = uploadTasks.value.filter(({ id }) => id !== fileId);
  transfers.removeUpload(fileId);
}

function formatSize(size: number) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function fileType(file: File) {
  if (file.type) return file.type.split("/").pop()?.toUpperCase() || file.type;
  return file.name.split(".").pop()?.toUpperCase() || "文件";
}

function selectFolder(folderId: string | null) {
  selectedFolderId.value = folderId;
  folderPickerOpen.value = false;
}

async function runUpload(task: UploadTask) {
  const controller = new AbortController();
  const useChunks = shouldUseChunkedUpload(task.file.size);
  controllers.set(task.id, controller);
  task.mode = useChunks ? "chunked" : "normal";
  task.status = useChunks ? "preparing" : "uploading";
  task.progress = 0;
  task.error = undefined;
  try {
    const item = useChunks
      ? await uploadFileInChunks({
          file: task.file,
          parentId: task.parentId ?? null,
          signal: controller.signal,
          onPhase: (phase) => {
            task.status = phase === "hashing" ? "preparing" : "uploading";
          },
          onProgress: (progress) => {
            task.progress = progress;
          },
        })
      : await uploadFile({
          file: task.file,
          parentId: task.parentId ?? null,
          signal: controller.signal,
          onProgress: (progress) => {
            task.progress = progress;
          },
        });
    task.progress = 100;
    task.fileId = item.id;
    task.status = "success";
    emit("uploaded", item);
  } catch (error) {
    if (isUploadCancelled(error, controller.signal)) {
      task.status = "cancelled";
    } else {
      task.status = "failed";
      task.error = uploadErrorMessage(error);
    }
  } finally {
    controllers.delete(task.id);
  }
}

function startUpload() {
  uploadTasks.value
    .filter((task) => task.status === "waiting")
    .forEach((task) => {
      task.parentId = selectedFolderId.value;
      void runUpload(task);
    });
  uploadTasks.value
    .filter((task) => task.status === "failed" || task.status === "cancelled")
    .forEach((task) => {
      task.parentId = selectedFolderId.value;
      void runUpload(task);
    });
}

function retryUpload(task: UploadTask) {
  if (task.status === "failed" || task.status === "cancelled") {
    task.parentId = selectedFolderId.value;
    void runUpload(task);
  }
}

function cancelUpload(task: UploadTask) {
  controllers.get(task.id)?.abort();
}

function statusText(task: UploadTask) {
  if (task.status === "preparing") return "正在计算文件校验值";
  if (task.status === "uploading" && task.mode === "chunked")
    return "分片上传中";
  return {
    waiting: "等待上传",
    preparing: "准备中",
    uploading: "上传中",
    success: "上传成功",
    failed: "上传失败",
    cancelled: "已取消",
  }[task.status];
}
</script>

<template>
  <Transition name="upload-sheet" appear>
  <div
    v-if="open"
    class="upload-overlay"
    role="presentation"
    @click.self="emit('close')"
  >
    <section
      ref="panel"
      class="upload-panel"
      tabindex="-1"
      @keydown="handleDialogKey"
      role="dialog"
      aria-modal="true"
      aria-labelledby="upload-title"
    >
      <div class="backup-banner">
        <span class="backup-icon"><UploadActionIcon kind="backup" /></span>
        <span>开启相册备份，节省手机空间</span>
        <button type="button" @click="chooseType('image', 'image/*')">立即上传</button>
      </div>
      <div class="upload-content">
        <div class="quick-actions" aria-label="快捷功能">
          <button type="button" @click="showUnavailable('扫一扫')"><UploadActionIcon kind="scan" /><span>扫一扫</span></button>
          <button type="button" @click="showUnavailable('链接任务')"><UploadActionIcon kind="link" /><span>链接任务</span></button>
          <button type="button" @click="showUnavailable('BT任务')"><UploadActionIcon kind="bt" /><span>BT任务</span></button>
        </div>
        <p v-if="unavailableMessage" class="unavailable-message" role="status">{{ unavailableMessage }}</p>
        <h2 id="upload-title">上传文件</h2>
        <div class="upload-options">
          <button class="upload-option" type="button" @click="chooseType('image', 'image/*')">
            <span class="action-art"><span class="action-badge live-badge">Live 原图</span><UploadActionIcon kind="photo" /></span><span>照片</span>
          </button>
          <button class="upload-option" type="button" @click="chooseType('video', 'video/*')">
            <span class="action-art"><span class="action-badge vip-badge">SVIP</span><UploadActionIcon kind="video" /></span><span>视频</span>
          </button>
          <button class="upload-option" type="button" @click="chooseType('document', '.pdf,.doc,.docx,.txt,.md,.ppt,.pptx,.xls,.xlsx')">
            <span class="action-art"><UploadActionIcon kind="document" /></span><span>文档</span>
          </button>
          <button class="upload-option" type="button" @click="chooseType('audio', 'audio/*')">
            <span class="action-art"><UploadActionIcon kind="audio" /></span><span>音频</span>
          </button>
          <button class="upload-option" type="button" @click="showUnavailable('微信文件')">
            <span class="action-art"><UploadActionIcon kind="wechat" /></span><span>微信文件</span>
          </button>
          <button class="upload-option" type="button" @click="chooseType('other', '*/*')">
            <span class="action-art"><UploadActionIcon kind="file" /></span><span>其他文件</span>
          </button>
          <button class="upload-option" type="button" @click="emit('createFolder')">
            <span class="action-art"><UploadActionIcon kind="folder" /></span><span>新建文件夹</span>
          </button>
          <button class="upload-option" type="button" @click="showUnavailable('新建文档')">
            <span class="action-art"><UploadActionIcon kind="note" /></span><span>新建文档</span>
          </button>
        </div>
        <h2 class="ai-heading">智能生成</h2>
        <div class="ai-options">
          <button v-for="action in [
            { kind: 'camera', label: 'AI相机' },
            { kind: 'mic', label: 'AI录音速记' },
            { kind: 'ai-video', label: 'AI视频笔记' },
            { kind: 'story', label: 'AI照片故事' },
          ]" :key="action.kind" class="upload-option" type="button" :aria-label="`${action.label}（暂未开放）`" @click="showUnavailable(action.label)">
            <span class="action-art"><UploadActionIcon :kind="action.kind" /></span><span>{{ action.label }}</span>
          </button>
        </div>
        <div class="security-caption"><ShieldCheck :size="17" fill="currentColor" stroke="white" /><span>千度网盘保障你的数据安全</span><ChevronRight :size="17" /></div>
      </div>

      <div v-if="uploadTasks.length" class="selected-files">
        <div class="selected-files-heading">
          <div>
            <strong>上传任务</strong
            ><small>{{ selectedTypeName }} · {{ uploadTasks.length }} 个</small>
          </div>
          <span v-if="activeCount">{{ activeCount }} 个处理中</span
          ><span v-else>逐个显示上传结果</span>
        </div>
        <ul>
          <li
            v-for="task in uploadTasks"
            :key="task.id"
            class="upload-task-row"
          >
            <span class="file-badge"
              ><Check
                v-if="task.status === 'success'"
                :size="17" /><LoaderCircle
                v-else-if="
                  task.status === 'preparing' || task.status === 'uploading'
                "
                class="spin"
                :size="17" /><FileText v-else :size="17"
            /></span>
            <div class="file-meta">
              <b :title="task.file.name">{{ task.file.name }}</b
              ><small
                >{{ formatSize(task.file.size) }} · {{ fileType(task.file) }} ·
                {{ statusText(task) }}</small
              >
              <div
                v-if="
                  task.status === 'preparing' ||
                  task.status === 'uploading' ||
                  task.status === 'success'
                "
                class="progress-line"
              >
                <div class="progress-track">
                  <span
                    :class="task.status"
                    :style="{ width: `${task.progress}%` }"
                  ></span>
                </div>
                <small>{{
                  task.status === "preparing"
                    ? `校验 ${task.progress}%`
                    : `${task.progress}%`
                }}</small>
              </div>
              <small v-if="task.error" class="task-error">{{
                task.error
              }}</small>
            </div>
            <button
              v-if="task.status === 'preparing' || task.status === 'uploading'"
              type="button"
              aria-label="取消上传"
              @click="cancelUpload(task)"
            >
              <X :size="16" />
            </button>
            <button
              v-else-if="
                task.status === 'failed' || task.status === 'cancelled'
              "
              type="button"
              aria-label="重试上传"
              @click="retryUpload(task)"
            >
              <RotateCcw :size="16" />
            </button>
            <button
              v-else-if="task.status !== 'success'"
              type="button"
              aria-label="移除文件"
              @click="removeFile(task.id)"
            >
              <Trash2 :size="16" />
            </button>
          </li>
        </ul>
      </div>

      <div
        v-if="selectedFiles.length && folderPickerOpen"
        class="folder-picker"
      >
        <div class="folder-picker-heading">
          <strong>选择上传位置</strong
          ><button
            type="button"
            aria-label="关闭文件夹选择"
            @click="folderPickerOpen = false"
          >
            <X :size="16" />
          </button>
        </div>
        <button
          v-for="folder in props.folderOptions"
          :key="folder.id || 'root'"
          type="button"
          :class="{ selected: folder.id === selectedFolderId }"
          @click="selectFolder(folder.id)"
        >
          {{ folder.name
          }}<span v-if="folder.id === selectedFolderId">已选择</span>
        </button>
      </div>

      <input
        ref="fileInput"
        class="file-input"
        type="file"
        :accept="accept"
        multiple
        @change="handleFileSelection"
      />
      <footer v-if="uploadTasks.length" class="upload-footer">
        <template v-if="uploadTasks.length">
          <button
            class="destination-button"
            type="button"
            @click="folderPickerOpen = !folderPickerOpen"
          >
            上传到：<b>{{ selectedFolderName }}</b
            ><span>›</span>
          </button>
          <button
            v-if="hasPending || activeCount"
            class="start-upload-button"
            type="button"
            :disabled="waitingCount + retryCount === 0"
            @click="startUpload"
          >
            {{ uploadButtonText }}
          </button>
          <button v-else class="upload-complete-button" type="button" @click="emit('close')">上传完成</button>
        </template>
        <template v-else
          ><span>选择文件后，可在这里查看上传进度</span
          ><button type="button" @click="emit('close')">
            暂时取消
          </button></template
        >
      </footer>
    </section>
  </div>
  </Transition>
</template>

<style scoped>
.upload-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(0 0 0 / 70%);
}
.upload-panel {
  width: min(560px, 100%);
  max-height: calc(100dvh - 48px);
  overflow: auto;
  border-radius: 20px;
  background: radial-gradient(ellipse at 100% 65%, #f0f9ff, transparent 45%), linear-gradient(115deg, #f5fbff, #fbfbfc 65%);
  color: #080f1e;
  outline: none;
}
.upload-panel button { font: inherit; cursor: pointer; }
.upload-panel button:focus-visible { outline: 2px solid #438aff; outline-offset: 4px; }
.backup-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 22px;
  border-bottom: 1px solid #eaf0f5;
  font-size: 16px;
  font-weight: 600;
  color: #50596b;
}
.backup-icon { display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; border-radius: 50%; background: white; }
.backup-icon svg { width: 21px; height: 21px; }
.backup-banner button { margin-left: auto; padding: 10px 12px; flex-shrink: 0; border: 0; border-radius: 9px; background: #e4eeff; color: #4185ff; font-weight: 600; }
.upload-content { padding: 17px 17px 40px; }
.quick-actions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.quick-actions button { display: flex; align-items: center; justify-content: center; gap: 8px; min-width: 0; height: 54px; padding: 0 8px; border: 0; border-radius: 15px; background: rgb(255 255 255 / 82%); color: #27344a; font-size: 14px; font-weight: 600; }
.quick-actions svg { flex: 0 0 auto; width: 30px; height: 30px; }
.quick-actions button:active, .upload-option:active { transform: scale(.97); }
.unavailable-message { margin: 10px 4px -5px; color: #526987; font-size: 12px; text-align: center; }
.upload-quick-actions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.upload-quick-actions button { display: flex; align-items: center; justify-content: center; gap: 8px; min-width: 0; min-height: 58px; padding: 8px; border: 0; border-radius: 14px; background: #fff; color: #33435c; }
.upload-quick-actions button:disabled { cursor: default; }
.upload-quick-actions svg { flex: none; width: 24px; height: 24px; color: #4696f1; }
.upload-quick-actions span { min-width: 0; font-size: 14px; white-space: nowrap; }
.upload-quick-actions small { display: block; color: #8491a4; font-size: 10px; }
.upload-content h2 { margin: 26px 9px 21px; font-size: 21px; line-height: 1.3; font-weight: 700; }
.upload-options, .ai-options { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); column-gap: 0; row-gap: 19px; }
.upload-option { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 10px 0 0; border: 0; background: transparent; color: inherit; font-size: 17px !important; white-space: nowrap; }
.action-art { position: relative; display: block; width: 51px; height: 51px; }
.action-art > svg { width: 100%; height: 100%; }
.action-badge { position: absolute; z-index: 1; top: -19px; left: 36px; padding: 2px 8px; border-radius: 20px; font-size: 14px; line-height: 1.25; white-space: nowrap; }
.live-badge { background: #4287ff; color: white; }
.vip-badge { background: #35251f; color: #fce1bb; font-style: italic; font-weight: 700; }
.upload-content .ai-heading { margin-top: 37px; margin-bottom: 22px; }
.ai-options .action-art { width: 48px; height: 48px; }
.ai-options .upload-option { gap: 17px; }
.security-caption { display: flex; align-items: center; justify-content: center; gap: 6px; margin-top: 23px; color: #4185ff; font-size: 16px; font-weight: 500; }

.selected-files {
  margin: 0 30px 24px;
  padding: 16px;
  border: 1px solid #e7edf6;
  border-radius: 15px;
  background: #fbfcff;
}
.selected-files-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.selected-files-heading strong,
.selected-files-heading small {
  display: block;
}
.selected-files-heading strong {
  font-size: 13px;
}
.selected-files-heading small {
  margin-top: 4px;
  color: #8d9aae;
  font-size: 11px;
}
.selected-files-heading > span {
  color: #7c8da7;
  font-size: 10px;
}
.selected-files ul {
  max-height: 170px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}
.selected-files li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-top: 1px solid #edf1f6;
}
.file-badge {
  display: grid;
  place-items: center;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: #eeeaff;
  color: #7564e9;
}
.file-meta {
  min-width: 0;
  flex: 1;
}
.file-meta b,
.file-meta small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-meta b {
  font-size: 12px;
}
.file-meta small {
  margin-top: 3px;
  color: #8f9aac;
  font-size: 10px;
}
.selected-files li > button {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #a0aaba;
}
.selected-files li > button:hover {
  background: #fff0f0;
  color: #e15d68;
}
.file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
.progress-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 7px;
}
.progress-line > small {
  flex: 0 0 32px;
  margin: 0;
  text-align: right;
}
.progress-track {
  height: 4px;
  flex: 1;
  overflow: hidden;
  border-radius: 999px;
  background: #e8edf5;
}
.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #54a9eb;
  transition: width 0.18s ease;
}
.progress-track span.success {
  background: #2fac78;
}
.task-error {
  color: #d74d58 !important;
  white-space: normal !important;
}
.spin {
  animation: upload-spin 1s linear infinite;
}
.start-upload-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.folder-picker {
  margin: 0 30px 18px;
  padding: 13px;
  border: 1px solid #e5ebf5;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 10px 26px rgba(35, 62, 104, 0.08);
}
.folder-picker-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 5px;
  color: #26344d;
  font-size: 12px;
}
.folder-picker-heading button {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 7px;
  background: #f3f6fb;
  color: #718098;
}
.folder-picker > button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 34px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #4a5870;
  text-align: left;
  font-size: 12px;
}
.folder-picker > button:hover,
.folder-picker > button.selected {
  background: #eef4ff;
  color: #2868ed;
}
.folder-picker > button span {
  font-size: 10px;
}
.upload-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 30px;
  background: #fafbfd;
  color: #8995a8;
  font-size: 11px;
}
.upload-footer button {
  height: 34px;
  padding: 0 15px;
  border: 1px solid #dce4f1;
  border-radius: 9px;
  background: #fff;
  color: #3d6ec9;
  font-size: 12px;
  font-weight: 600;
}
.upload-footer button:hover {
  border-color: #9db9ef;
  background: #f5f8ff;
}
.destination-button {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  flex: 1;
  text-align: left;
}
.destination-button b {
  overflow: hidden;
  color: #2868ed;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.destination-button span {
  margin-left: auto;
  color: #8d9bb0;
  font-size: 20px;
  line-height: 1;
}
.start-upload-button {
  border-color: #2877e5 !important;
  background: #2877e5 !important;
  color: #fff !important;
}
.start-upload-button:hover:not(:disabled) {
  border-color: #1766d2 !important;
  background: #1766d2 !important;
}
.upload-complete-button {
  border-color: #b9ddf4 !important;
  background: #b9ddf4 !important;
  color: #fff !important;
}
.upload-complete-button:hover {
  border-color: #a8d3ef !important;
  background: #a8d3ef !important;
}

@media (width < 768px) {
  .upload-overlay { align-items: end; padding: 0; }
  .upload-panel { width: 100%; max-height: 100dvh; border-radius: 0; }
  .backup-banner { padding: 9px 18px; gap: 7px; font-size: 12px; min-height: 49px; box-sizing: border-box; }
  .backup-icon { width: 24px; height: 24px; }
  .backup-icon svg { width: 16px; height: 16px; }
  .backup-banner button { padding: 7px 8px; border-radius: 7px; }
  .upload-content { padding: 12px 12px max(32px, env(safe-area-inset-bottom)); }
  .quick-actions { gap: 8px; }
  .quick-actions button { height: 48px; gap: 6px; padding: 0 3px; border-radius: 13px; font-size: 12px; }
  .quick-actions button > svg { width: 28px; height: 28px; }
  .unavailable-message { margin-top: 8px; font-size: 11px; }
  .upload-quick-actions { gap: 6px; }
  .upload-quick-actions button { min-height: 48px; padding: 5px 3px; gap: 4px; border-radius: 12px; }
  .upload-quick-actions svg { width: 19px; height: 19px; }
  .upload-quick-actions span { font-size: 11px; }
  .upload-quick-actions small { font-size: 9px; }
  .upload-content h2 { margin: 18px 6px 14px; font-size: 14px; }
  .upload-options { row-gap: 0; }
  .upload-option { gap: 9px; padding-top: 0; font-size: 12px !important; line-height: 1.25; }
  .action-art { width: 36px; height: 36px; }
  .action-badge { top: -13px; left: 25px; padding: 1px 6px; font-size: 10px; }
  .upload-content .ai-heading { margin-top: 25px; margin-bottom: 13px; }
  .ai-options .action-art { width: 34px; height: 34px; }
  .ai-options .upload-option { gap: 12px; }
  .security-caption { gap: 4px; margin-top: 16px; font-size: 12px; }
  .security-caption svg { width: 14px; height: 14px; }
  .selected-files { margin: 0 18px 16px; }
  .folder-picker { margin: 0 18px 16px; }
  .upload-footer { padding: 14px 18px max(14px, env(safe-area-inset-bottom)); }
}
.upload-sheet-enter-active,
.upload-sheet-leave-active {
  transition: opacity 280ms ease;
}
.upload-sheet-enter-active .upload-panel,
.upload-sheet-leave-active .upload-panel {
  transition: transform 280ms cubic-bezier(0.22, 1, 0.36, 1);
}
.upload-sheet-leave-active {
  pointer-events: none;
  transition-duration: 220ms;
}
.upload-sheet-leave-active .upload-panel {
  transition-duration: 220ms;
  transition-timing-function: cubic-bezier(0.4, 0, 1, 1);
}
.upload-sheet-enter-from,
.upload-sheet-leave-to {
  opacity: 0;
}
.upload-sheet-enter-from .upload-panel,
.upload-sheet-leave-to .upload-panel {
  transform: translateY(100%);
}
@keyframes upload-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
  .progress-track span,
  .upload-sheet-enter-active,
  .upload-sheet-leave-active,
  .upload-sheet-enter-active .upload-panel,
  .upload-sheet-leave-active .upload-panel { transition: none; }
  .upload-sheet-enter-from .upload-panel,
  .upload-sheet-leave-to .upload-panel { transform: none; }
}

</style>
