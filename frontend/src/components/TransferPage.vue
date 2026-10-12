<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useTransfers, isActive, type TransferTask } from "../stores/transfers";
import { formatBytes, formatSize, useDrive } from "../stores/drive";
import {
  driveErrorMessage,
  getFileDetails,
  searchFiles,
  type DriveItemResponse,
} from "../api/drive";
import { previewFormat } from "../preview/formats";
import FilePreview from "./FilePreview.vue";
import { iconForFile } from "./fileIcon";
import {
  ArrowLeft,
  CheckSquare,
  Hexagon,
  ChevronRight,
  ShieldCheck,
  X,
  ChevronDown,
} from "@lucide/vue";
const emit = defineEmits<{ back: []; manageStorage: [] }>();
const tab = ref<TransferTask["direction"]>("download");
const promo = ref(true),
  coupon = ref(true);
const transfers = useTransfers();
const previewFile = ref<DriveItemResponse | null>(null);
const openError = ref("");
const opening = ref<string | null>(null);
const limitMenuOpen = ref(false);
const limitMenu = ref<HTMLElement | null>(null);
const downloadLimits = [1, 2, 3, 5];
function closeLimitMenu(event: PointerEvent) {
  if (!limitMenu.value?.contains(event.target as Node))
    limitMenuOpen.value = false;
}
function selectDownloadLimit(value: number) {
  transfers.setDownloadLimit(value);
  limitMenuOpen.value = false;
}
onMounted(() => {
  void transfers.refresh();
});
onMounted(() => document.addEventListener("pointerdown", closeLimitMenu));
onUnmounted(() => document.removeEventListener("pointerdown", closeLimitMenu));
async function openTask(task: TransferTask) {
  openError.value = "";
  if (task.status !== "success") {
    openError.value = `${task.name}：${status(task)}`;
    return;
  }
  opening.value = task.id;
  try {
    let fileId = task.fileId;
    if (!fileId && task.direction !== "download") {
      const matches: DriveItemResponse[] = [];
      for (let page = 0; page < 10; page++) {
        const result = await searchFiles({
          q: task.name.slice(0, 100),
          scope: "all",
          type: "all",
          sort: "name",
          page,
          size: 100,
        });
        matches.push(
          ...result.items
            .map((hit) => hit.file)
            .filter(
              (file) =>
                file.kind === "file" &&
                file.name === task.name &&
                file.size === task.size,
            ),
        );
        if (matches.length > 1) {
          openError.value =
            "网盘中有多个同名同大小文件，无法确定这条历史记录对应哪一个。";
          return;
        }
        if ((page + 1) * 100 >= result.total) break;
        if (page === 9) {
          openError.value = "匹配结果过多，请在网盘中查找该文件。";
          return;
        }
      }
      fileId = matches[0]?.id;
    }
    if (!fileId) {
      openError.value = "这条历史记录未找到对应的网盘文件，无法打开预览。";
      return;
    }
    const file = (await getFileDetails(fileId)).file;
    if (!previewFormat(file.name)) {
      openError.value = "此文件格式暂不支持在线预览，请从网盘下载查看。";
      return;
    }
    previewFile.value = file;
    if (!task.fileId) transfers.linkFile(task.id, file.id);
  } catch (error) {
    openError.value = driveErrorMessage(
      error,
      "文件可能已删除或无权访问，无法打开预览。",
    );
  } finally {
    opening.value = null;
  }
}
const drive = useDrive();
const storageUsage = computed(() => drive.state.usage);
const storagePercent = computed(() => {
  const usage = storageUsage.value;
  if (!usage?.limitBytes) return 0;
  return Math.min(100, Math.max(0, (usage.usedBytes / usage.limitBytes) * 100));
});
onMounted(() => {
  if (!drive.state.usage) void drive.loadUsage();
});
type Filter = "all" | "success" | "active" | "failed";
const filter = ref<Filter>("all");
const filters: { key: Filter; label: string }[] = [
  { key: "all", label: "全部任务" },
  { key: "success", label: "已完成" },
  { key: "active", label: "进行中" },
  { key: "failed", label: "任务失败" },
];
function matches(task: TransferTask, key: Filter) {
  return (
    key === "all" ||
    (key === "active"
      ? isActive(task)
      : key === "failed"
        ? ["failed", "cancelled"].includes(task.status)
        : task.status === "success")
  );
}
const directionTasks = computed(() =>
  transfers.tasks.filter((task) => task.direction === tab.value),
);
const groups = computed(() => {
  const result = new Map<string, TransferTask[]>();
  for (const task of [...directionTasks.value]
    .reverse()
    .filter((task) => matches(task, filter.value))) {
    const date = new Date(task.createdAt);
    const label = `${String(date.getMonth() + 1).padStart(2, "0")}月${String(date.getDate()).padStart(2, "0")}日`;
    result.set(label, [...(result.get(label) || []), task]);
  }
  return [...result.entries()];
});
function status(task: TransferTask) {
  if (task.status === "success")
    return task.direction === "download"
      ? "已下载至：浏览器下载目录"
      : task.direction === "transfer"
        ? "已转存至：千度网盘"
        : "已上传至：千度网盘";
  return (
    task.error ||
    (
      {
        waiting: "等待传输",
        preparing: "正在计算文件校验值",
        uploading: "上传中",
        downloading: "下载中",
        failed: "传输失败",
        cancelled: "已取消",
      } as Record<string, string>
    )[task.status]
  );
}
</script>

<template>
  <section class="transfer-page" aria-label="传输列表">
    <header class="transfer-header">
      <button aria-label="返回" @click="emit('back')">
        <ArrowLeft />
      </button>
      <h1>传输列表</h1>
      <button class="svip">SVIP 加速</button>
      <button aria-label="选择任务（暂未开放）">
        <CheckSquare />
      </button>
      <button aria-label="设置（暂未开放）">
        <Hexagon />
      </button>
    </header>
    <aside v-if="promo" class="transfer-promo">
      <div class="album-logo">▶<small>⇄</small></div>
      <div>
        <strong> <b>100张</b>图待免费极速传输</strong>
        <p>还送无限空间，告别内存不足</p>
      </div>
      <button class="claim">点击领取</button>
      <button class="dismiss" aria-label="关闭推广" @click="promo = false">
        <X />
      </button>
    </aside>
    <nav class="transfer-tabs" aria-label="传输类型">
      <button :class="{ active: tab === 'download' }" @click="tab = 'download'">
        下载
      </button>
      <button :class="{ active: tab === 'upload' }" @click="tab = 'upload'">
        上传
      </button>
      <button :class="{ active: tab === 'transfer' }" @click="tab = 'transfer'">
        转存
      </button>
      <button aria-label="云添加（暂未开放）">云添加</button>
    </nav>
    <main class="transfer-content">
      <div class="transfer-security">
        <ShieldCheck />千度网盘保障你的传输安全<ChevronRight />
      </div>
      <div class="transfer-filters">
        <button
          v-for="item in filters"
          :key="item.key"
          :class="{ active: filter === item.key }"
          @click="filter = item.key"
        >
          {{ item.label
          }}{{
            item.key === "all"
              ? ""
              : `(${directionTasks.filter((task) => matches(task, item.key)).length})`
          }}
        </button>
      </div>
      <div class="transfer-toolbar">
        <span>全部文件</span>
        <span>
          <template v-if="tab === 'download'"
            >同时下载数:
            <span ref="limitMenu" class="download-limit">
              <button
                type="button"
                aria-label="同时下载数"
                aria-haspopup="true"
                :aria-expanded="limitMenuOpen"
                @click="limitMenuOpen = !limitMenuOpen"
                @keydown.esc="limitMenuOpen = false"
              >
                {{ transfers.settings.downloadLimit }} <ChevronDown />
              </button>
              <span
                v-if="limitMenuOpen"
                class="download-limit-options"
                aria-label="选择同时下载数"
                @keydown.esc="limitMenuOpen = false"
              >
                <button
                  v-for="n in downloadLimits"
                  :key="n"
                  type="button"
                  :aria-label="`同时下载 ${n} 个`"
                  :aria-pressed="transfers.settings.downloadLimit === n"
                  @click="selectDownloadLimit(n)"
                >
                  {{ n }}
                </button>
              </span>
            </span>
          </template>
          <i> </i>
          <button @click="transfers.clearFinished(tab)">全部清除</button>
        </span>
      </div>
      <p v-if="openError" class="transfer-open-error" role="alert">
        {{ openError
        }}<button type="button" aria-label="关闭提示" @click="openError = ''">
          <X :size="16" />
        </button>
      </p>
      <p v-if="!groups.length" class="transfer-empty">
        暂无{{
          filter === "all"
            ? { download: "下载", upload: "上传", transfer: "转存" }[tab]
            : filters.find((item) => item.key === filter)?.label
        }}任务
      </p>
      <section
        v-for="[date, tasks] in groups"
        :key="date"
        class="transfer-group"
      >
        <h2>{{ date }}</h2>
        <article v-for="task in tasks" :key="task.id" class="transfer-task">
          <button
            type="button"
            class="task-open"
            :aria-label="`查看 ${task.name} 的文件内容`"
            :disabled="opening === task.id"
            @click="openTask(task)"
          ></button>
          <component
            :is="iconForFile({ name: task.name, kind: 'file' })"
            class="task-icon"
            aria-hidden="true"
          />
          <div class="task-detail">
            <h3 :title="task.name">{{ task.name }}</h3>
            <p>
              {{ formatSize(task.size) }} &nbsp; {{ status(task) }}
              <ChevronRight v-if="task.status === 'success'" />
            </p>
            <div
              v-if="isActive(task)"
              class="task-progress"
              role="progressbar"
              :aria-label="task.name"
              :aria-valuenow="Math.round(task.progress)"
              aria-valuemin="0"
              aria-valuemax="100"
            >
              <span :style="{ width: `${task.progress}%` }"> </span>
            </div>
          </div>
          <span v-if="isActive(task)" class="task-percent"
            >{{ Math.round(task.progress) }}%</span
          >
          <span v-else class="task-circle" aria-hidden="true"> </span>
        </article>
      </section>
    </main>
    <aside v-if="coupon" class="transfer-coupon">
      <span class="coupon-gem">S</span>
      <p>你有<span>1</span>张极速下载券待使用 <em>（12-31 23:59…</em></p>
      <button>立即使用</button>
      <button class="dismiss" aria-label="关闭下载券" @click="coupon = false">
        <X />
      </button>
    </aside>
    <FilePreview
      v-if="previewFile"
      :file="previewFile"
      @close="previewFile = null"
    />
    <footer class="transfer-storage" aria-label="网盘剩余空间">
      <div class="storage-meter" aria-hidden="true">
        <span :style="{ width: `${storagePercent}%` }"></span>
      </div>
      <span v-if="storageUsage"
        >剩余空间：{{ formatBytes(storageUsage.availableBytes) }} /
        {{ formatBytes(storageUsage.limitBytes) }}</span
      >
      <span v-else>{{
        drive.state.usageError ? "空间信息暂不可用" : "正在获取空间信息…"
      }}</span>
      <button type="button" @click="emit('manageStorage')">
        点击管理<ChevronRight />
      </button>
    </footer>
  </section>
</template>

<style scoped>
.download-limit {
  position: relative;
  display: inline-flex;
}
.transfer-toolbar .download-limit > button {
  color: #4288ff;
  padding: 2px;
  font: inherit;
  cursor: pointer;
}
.download-limit > button svg {
  vertical-align: middle;
}
.download-limit-options {
  position: absolute;
  top: 100%;
  right: 0;
  z-index: 2;
  display: grid;
  min-width: 48px;
  padding: 4px;
  border: 1px solid #e2e7ef;
  border-radius: 6px;
  background: white;
  box-shadow: 0 4px 12px #0002;
}
.transfer-toolbar .download-limit-options button {
  display: block;
  width: 100%;
  padding: 6px 10px;
  text-align: center;
  color: #4288ff;
  cursor: pointer;
}
.download-limit-options button:hover,
.download-limit-options button[aria-pressed="true"] {
  background: #eef4ff;
}
.transfer-group h2 {
  font-size: 15px;
  margin: 20px 0 18px;
  font-weight: 750;
}
.transfer-task {
  position: relative;
  display: flex;
  align-items: center;
  gap: 20px;
  margin: 0 7px 24px;
  min-width: 0;
}
.transfer-page .task-open {
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
}
.transfer-page .task-open:focus-visible {
  outline-offset: 2px;
}
.transfer-open-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff2f2;
  color: #b43232;
}
.transfer-open-error button {
  flex: none;
  display: grid;
  place-items: center;
}
.task-detail {
  flex: 1;
  min-width: 0;
}
.task-detail h3 {
  font-size: 16px;
  margin: 0 0 7px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.task-detail p {
  color: #a2a9bc;
  font-size: 13px;
  margin: 0;
  display: flex;
  align-items: center;
}
.task-detail p svg {
  width: 15px;
  height: 15px;
  color: #dadde5;
  flex-shrink: 0;
}
.task-icon,
.archive-icon {
  width: 27px;
  height: 27px;
  flex-shrink: 0;
}
.task-icon {
  color: #4f99ef;
}
.archive-icon {
  position: relative;
  border-radius: 4px;
  background: linear-gradient(#509df8 0 33%, #ee6450 33% 66%, #77d943 66%);
}
.archive-icon:before {
  content: "";
  position: absolute;
  width: 7px;
  inset: 0 auto 0 10px;
  background: #e99130;
}
.archive-icon span {
  position: absolute;
  width: 12px;
  height: 8px;
  border: 2px solid white;
  left: 8px;
  top: 9px;
  border-radius: 1px;
}
.task-circle {
  width: 13px;
  height: 13px;
  border: 2px solid #dadde6;
  border-radius: 50%;
  flex-shrink: 0;
}
.task-percent {
  font-size: 11px;
  color: #4288ff;
}
.task-progress {
  height: 3px;
  background: #edf3ff;
  margin-top: 8px;
  border-radius: 3px;
  overflow: hidden;
}
.task-progress span {
  display: block;
  height: 100%;
  background: #4288ff;
}
.transfer-content {
  min-height: 0;
}
.transfer-page {
  position: fixed;
  inset: 0;
  z-index: 80;
  overflow: auto;
  background: #f7f7f7;
  color: #080f1d;
  font-family: Arial, "PingFang SC", "Microsoft YaHei", sans-serif;
  display: flex;
  flex-direction: column;
  font-size: 14px;
}
.transfer-page button {
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
  padding: 0;
}
.transfer-page button:focus-visible {
  outline: 2px solid #4086ff;
  outline-offset: 4px;
}
.transfer-header {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 20px 18px 15px;
}
.transfer-header h1 {
  font-size: 20px;
  margin: 0;
  flex: 1;
  font-weight: 750;
  white-space: nowrap;
}
.transfer-header svg {
  width: 24px;
  height: 24px;
  stroke-width: 2.5;
}
.transfer-header .svip {
  background: linear-gradient(110deg, #fce4af, #f7ce99);
  color: #64310e;
  font-weight: bold;
  border-radius: 24px;
  padding: 10px 13px;
  white-space: nowrap;
  font-size: 14px;
}
.transfer-promo {
  margin: 0 18px 12px;
  padding: 16px;
  display: flex;
  gap: 16px;
  align-items: center;
  background: white;
  border-radius: 10px;
}
.album-logo {
  width: 34px;
  height: 34px;
  border: 1px solid #eee;
  border-radius: 9px;
  position: relative;
  color: #6359f8;
  font-size: 26px;
  text-align: center;
  flex-shrink: 0;
}
.album-logo small {
  position: absolute;
  bottom: -4px;
  right: -5px;
  background: #54cd79;
  color: white;
  border-radius: 50%;
  font-size: 14px;
  width: 17px;
  height: 17px;
}
.transfer-promo strong {
  font-size: 15px;
  color: #333;
  white-space: nowrap;
}
.transfer-promo b {
  color: #495cff;
}
.transfer-promo p {
  margin: 7px 0 0;
  color: #999;
  font-size: 12px;
  white-space: nowrap;
}
.transfer-promo .claim {
  margin-left: auto;
  background: #333;
  color: white;
  border-radius: 30px;
  padding: 9px 15px;
  font-size: 12px;
  font-weight: bold;
  white-space: nowrap;
}
.transfer-page .dismiss {
  color: #b3b3b3;
  flex-shrink: 0;
}
.dismiss svg {
  width: 17px;
  height: 17px;
}
.transfer-tabs {
  display: flex;
  justify-content: space-between;
  padding: 10px 24px 20px;
  font-size: 18px;
  font-weight: bold;
  color: #858998;
}
.transfer-tabs button {
  position: relative;
}
.transfer-tabs .active {
  color: #080f1d;
}
.transfer-tabs .active:after {
  content: "";
  position: absolute;
  width: 10px;
  height: 3px;
  border-radius: 3px;
  bottom: -7px;
  left: calc(50% - 5px);
  background: #080f1d;
}
.transfer-content {
  background: white;
  border-radius: 16px 16px 0 0;
  flex: 1;
  padding: 10px 18px 100px;
}
.transfer-security {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #4288ff;
  font-size: 11px;
  font-weight: 600;
}
.transfer-security svg {
  width: 13px;
  height: 13px;
}
.transfer-filters {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  margin: 18px 0 20px;
}
.transfer-filters button {
  border-radius: 6px;
  background: #f0f1f6;
  padding: 6px 9px;
  font-weight: bold;
  color: #a2a9bc;
  white-space: nowrap;
}
.transfer-filters .active {
  background: #060d1b;
  color: white;
}
.transfer-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #858b9c;
  font-size: 12px;
  font-weight: 600;
}
.transfer-toolbar > span:last-child {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #a2a9bc;
}
.transfer-toolbar button {
  color: #4288ff;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.transfer-toolbar svg {
  width: 13px;
  height: 13px;
}
.transfer-toolbar i {
  height: 10px;
  border-left: 1px solid #eee;
  margin: 0 9px;
}
.transfer-empty {
  text-align: center;
  color: #a2a9bc;
  margin-top: 70px;
  font-size: 13px;
}
.transfer-coupon {
  position: fixed;
  bottom: calc(55px + env(safe-area-inset-bottom, 0px));
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 18px;
  background: #fdf5e7;
  color: #67310b;
  font-size: 12px;
  font-weight: 600;
}
.transfer-coupon p {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin: 0;
}
.transfer-coupon span:not(.coupon-gem),
.transfer-coupon em {
  color: #bd7b39;
  font-style: normal;
}
.coupon-gem {
  background: #633822;
  color: #efd1a6;
  clip-path: polygon(25% 0, 75% 0, 100% 40%, 50% 100%, 0 40%);
  width: 14px;
  height: 14px;
  text-align: center;
  font-size: 9px;
}
.transfer-coupon > button:not(.dismiss) {
  background: linear-gradient(110deg, #fcebb5, #f7d6b5);
  border-radius: 20px;
  padding: 8px 12px;
  color: #673a30;
  white-space: nowrap;
  font-weight: bold;
}
.transfer-coupon .dismiss {
  color: #baab89;
  margin-left: 5px;
}
.transfer-storage {
  position: fixed;
  z-index: 2;
  inset: auto 0 0;
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 48px;
  padding: 8px 18px max(8px, env(safe-area-inset-bottom));
  box-sizing: border-box;
  border-top: 1px solid #f0f2f6;
  background: rgb(255 255 255 / 96%);
  color: #a2a9bc;
  font-size: 12px;
  white-space: nowrap;
}
.storage-meter {
  position: absolute;
  inset: 0 0 auto;
  height: 2px;
  overflow: hidden;
  background: #edf3ff;
}
.storage-meter span {
  display: block;
  height: 100%;
  background: #83b9ff;
  transition: width 0.25s ease;
}
.transfer-storage > span {
  overflow: hidden;
  text-overflow: ellipsis;
}
.transfer-storage button {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
  color: #4288ff;
  flex-shrink: 0;
}
.transfer-storage button svg {
  width: 15px;
  height: 15px;
}
@media (width >= 768px) {
  .transfer-header,
  .transfer-tabs {
    padding-left: 40px;
    padding-right: 40px;
  }
  .transfer-promo {
    margin: 10px 40px 20px;
  }
  .transfer-content {
    padding: 20px 40px 110px;
  }
  .transfer-filters {
    justify-content: flex-start;
  }
  .transfer-coupon {
    padding-left: 40px;
    padding-right: 40px;
  }
  .transfer-storage {
    padding-right: 40px;
    padding-left: 40px;
  }
  .transfer-promo strong {
    font-size: 18px;
  }
}
@media (max-width: 380px) {
  .transfer-header {
    gap: 12px;
  }
  .transfer-promo {
    gap: 9px;
    padding: 13px 10px;
  }
  .transfer-promo strong {
    font-size: 13px;
  }
  .transfer-promo p {
    font-size: 10px;
  }
  .transfer-promo .claim {
    padding: 9px 10px;
  }
  .transfer-filters {
    gap: 6px;
    font-size: 12px;
  }
}
</style>
