<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import {
  Check,
  FilePlus2,
  FileText,
  FolderPlus,
  Image,
  LoaderCircle,
  Music2,
  RotateCcw,
  Trash2,
  UploadCloud,
  Video,
  X,
} from "lucide-vue-next";
import {
  isUploadCancelled,
  uploadErrorMessage,
  uploadFile,
} from "../api/files";
import {
  shouldUseChunkedUpload,
  uploadFileInChunks,
} from "../api/chunkedUpload";
import type { DriveItem } from "../stores/drive";

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
const emit = defineEmits<{ close: []; uploaded: [item: DriveItem] }>();
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
}

const fileInput = ref<HTMLInputElement>();
const accept = ref("*/*");
const uploadTasks = ref<UploadTask[]>([]);
const selectedType = ref<UploadType | null>(null);
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
  selectedType.value = type;
  accept.value = typeAccept;
  await nextTick();
  fileInput.value?.click();
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
  <div
    v-if="open"
    class="upload-overlay"
    role="presentation"
    @click.self="emit('close')"
  >
    <section
      class="upload-panel"
      role="dialog"
      aria-modal="true"
      aria-labelledby="upload-title"
    >
      <header class="upload-header">
        <div>
          <span class="upload-eyebrow">CendoDrive · 文件中心</span>
          <h2 id="upload-title">上传文件</h2>
        </div>
        <button
          class="close-button"
          type="button"
          aria-label="关闭上传面板"
          @click="emit('close')"
        >
          <X :size="20" />
        </button>
      </header>

      <div class="desktop-dropzone">
        <span class="dropzone-icon"><UploadCloud :size="28" /></span
        ><strong>把文件拖到这里</strong>
        <p>也可以选择下方的文件类型开始上传</p>
      </div>

      <div class="upload-section-heading">
        <span>选择上传内容</span><small>可多选文件</small>
      </div>
      <div class="upload-options">
        <button
          class="upload-option option-image"
          type="button"
          @click="chooseType('image', '.jpg,.jpeg,.png,.gif,.webp,.bmp,.svg')"
        >
          <span><Image :size="21" /></span><b>照片</b><small>JPG、PNG</small>
        </button>
        <button
          class="upload-option option-video"
          type="button"
          @click="chooseType('video', '.mp4,.mov,.mkv,.avi,.webm')"
        >
          <span><Video :size="21" /></span><b>视频</b><small>MP4、MOV</small>
        </button>
        <button
          class="upload-option option-document"
          type="button"
          @click="
            chooseType(
              'document',
              '.pdf,.doc,.docx,.txt,.md,.xls,.xlsx,.ppt,.pptx',
            )
          "
        >
          <span><FileText :size="21" /></span><b>文档</b
          ><small>PDF、Word</small>
        </button>
        <button
          class="upload-option option-audio"
          type="button"
          @click="chooseType('audio', '.mp3,.wav,.flac,.aac,.m4a')"
        >
          <span><Music2 :size="21" /></span><b>音频</b><small>MP3、WAV</small>
        </button>
        <button
          class="upload-option option-other"
          type="button"
          @click="chooseType('other', '*/*')"
        >
          <span><UploadCloud :size="21" /></span><b>其他文件</b
          ><small>全部类型</small>
        </button>
        <div class="upload-option option-folder">
          <span><FolderPlus :size="21" /></span><b>新建文件夹</b
          ><small>整理文件</small>
        </div>
        <div class="upload-option option-note">
          <span><FilePlus2 :size="21" /></span><b>新建文档</b
          ><small>稍后开放</small>
        </div>
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
      <footer class="upload-footer">
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
          <button v-else type="button" @click="emit('close')">完成</button>
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
</template>

<style scoped>
.upload-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(17, 29, 52, 0.42);
  backdrop-filter: blur(5px);
}
.upload-panel {
  width: min(560px, 100%);
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.8);
  border-radius: 24px;
  background: #fff;
  box-shadow: 0 26px 80px rgba(22, 44, 83, 0.24);
  color: #19243a;
}
.upload-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 28px 30px 22px;
  border-bottom: 1px solid #edf1f7;
}
.upload-eyebrow {
  display: block;
  margin-bottom: 8px;
  color: #6e7b91;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
.upload-header h2 {
  margin: 0;
  font-size: 25px;
  letter-spacing: -0.03em;
}
.close-button {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 11px;
  background: #f3f6fb;
  color: #64728a;
}
.close-button:hover {
  background: #e8eef9;
  color: #2868ed;
}
.desktop-dropzone {
  margin: 24px 30px 22px;
  padding: 25px 20px;
  border: 1px dashed #b8caec;
  border-radius: 17px;
  background: linear-gradient(135deg, #f7faff, #eef5ff);
  text-align: center;
}
.dropzone-icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin: 0 auto 12px;
  border-radius: 16px;
  background: #dce9ff;
  color: #2868ed;
}
.desktop-dropzone strong {
  display: block;
  font-size: 15px;
}
.desktop-dropzone p {
  margin: 7px 0 0;
  color: #8895a9;
  font-size: 12px;
}
.upload-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 30px 12px;
  color: #26344d;
  font-size: 13px;
  font-weight: 700;
}
.upload-section-heading small {
  color: #a0a9b8;
  font-size: 11px;
  font-weight: 500;
}
.upload-options {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  padding: 0 30px 24px;
}
.upload-option {
  min-height: 91px;
  padding: 14px 12px;
  border: 1px solid #edf0f5;
  border-radius: 15px;
  background: #fff;
  color: #19243a;
  text-align: left;
}
.upload-option button {
  cursor: pointer;
}
.upload-option:hover {
  border-color: #9db9ef;
  background: #f7faff;
}
.upload-option > span {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  margin-bottom: 8px;
  border-radius: 10px;
}
.upload-option b,
.upload-option small {
  display: block;
}
.upload-option b {
  font-size: 12px;
}
.upload-option small {
  margin-top: 3px;
  color: #9aa5b5;
  font-size: 10px;
}
.option-image > span {
  background: #e7f1ff;
  color: #4285ed;
}
.option-video > span {
  background: #e9f8f0;
  color: #21a96a;
}
.option-document > span {
  background: #fff0e7;
  color: #ef8238;
}
.option-audio > span {
  background: #f0ebff;
  color: #8469df;
}
.option-other > span {
  background: #e8f7ff;
  color: #2793cf;
}
.option-folder > span {
  background: #fff7de;
  color: #d69921;
}
.option-note > span {
  background: #f1f3f6;
  color: #8e98a8;
}
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
  border-color: #8bc9f6 !important;
  background: #8ed1f7 !important;
  color: #fff !important;
}
.start-upload-button:hover {
  border-color: #50afe8 !important;
  background: #68c0f1 !important;
}
@media (max-width: 700px) {
  .upload-overlay {
    align-items: end;
    padding: 0;
    background: rgba(17, 29, 52, 0.48);
  }
  .upload-panel {
    width: 100%;
    border-radius: 25px 25px 0 0;
    box-shadow: 0 -18px 55px rgba(22, 44, 83, 0.18);
    animation: upload-sheet-in 0.22s ease-out;
  }
  .upload-header {
    padding: 22px 22px 17px;
  }
  .upload-header h2 {
    font-size: 22px;
  }
  .desktop-dropzone {
    display: none;
  }
  .upload-section-heading {
    padding: 20px 22px 12px;
  }
  .upload-options {
    grid-template-columns: repeat(3, 1fr);
    gap: 9px;
    padding: 0 22px 20px;
  }
  .upload-option {
    min-height: 84px;
    padding: 12px 9px;
  }
  .selected-files {
    margin: 0 22px 20px;
  }
  .folder-picker {
    margin: 0 22px 16px;
  }
  .upload-footer {
    padding: 14px 22px max(14px, env(safe-area-inset-bottom));
    margin: 0;
  }
  .upload-footer span {
    max-width: 200px;
    line-height: 1.5;
  }
  .destination-button {
    padding-left: 0;
    padding-right: 0;
  }
  .start-upload-button {
    flex: 0 0 auto;
    padding-inline: 17px !important;
  }
}
@keyframes upload-sheet-in {
  from {
    transform: translateY(22px);
    opacity: 0.6;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}
@keyframes upload-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .upload-panel {
    animation: none;
  }
}
</style>
