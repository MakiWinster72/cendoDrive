<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  Check,
  ChevronLeft,
  Link2,
  Share2,
  XCircle,
} from "@lucide/vue";
import { iconForFile } from "./fileIcon";
import type { ShareRecord } from "../api/shares";

type Filter = "all" | "active" | "expired" | "cancelled";
const props = defineProps<{
  shares: ShareRecord[];
  loading: boolean;
  error: string;
}>();
const emit = defineEmits<{
  back: [];
  refresh: [];
  open: [share: ShareRecord];
  copy: [shares: ShareRecord[]];
  cancel: [shares: ShareRecord[]];
}>();
const tabs: { id: Filter; label: string }[] = [
  { id: "all", label: "全部" },
  { id: "active", label: "分享有效" },
  { id: "expired", label: "分享过期" },
  { id: "cancelled", label: "已取消" },
];
const filter = ref<Filter>("all");
const selecting = ref(false);
const selectedIds = ref<string[]>([]);

function state(share: ShareRecord): Exclude<Filter, "all"> {
  if (share.status === "CANCELLED") return "cancelled";
  return new Date(share.expiresAt).getTime() > Date.now()
    ? "active"
    : "expired";
}
const visibleShares = computed(() =>
  props.shares
    .filter((share) => filter.value === "all" || state(share) === filter.value)
    .sort(
      (a, b) =>
        new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    ),
);
const selectedShares = computed(() =>
  visibleShares.value.filter((share) => selectedIds.value.includes(share.id)),
);
const cancellableShares = computed(() =>
  selectedShares.value.filter((share) => state(share) === "active"),
);
const copyableShares = computed(() => cancellableShares.value);

watch(
  () => props.shares,
  (shares) => {
    selectedIds.value = selectedIds.value.filter((id) =>
      shares.some((share) => share.id === id && state(share) === "active"),
    );
  },
  { deep: true },
);

function toggleSelection() {
  selecting.value = !selecting.value;
  selectedIds.value = [];
}
function changeFilter(next: Filter) {
  filter.value = next;
  selectedIds.value = [];
}
function toggle(share: ShareRecord) {
  if (state(share) !== "active") return;
  selectedIds.value = selectedIds.value.includes(share.id)
    ? selectedIds.value.filter((id) => id !== share.id)
    : [...selectedIds.value, share.id];
}
function openOrSelect(share: ShareRecord) {
  if (selecting.value) toggle(share);
  else emit("open", share);
}
function copySelected() {
  if (copyableShares.value.length) emit("copy", copyableShares.value);
}
function cancelSelected() {
  if (cancellableShares.value.length) emit("cancel", cancellableShares.value);
}
</script>

<template>
  <main class="my-shares-mobile">
    <header class="my-shares-head">
      <button
        type="button"
        class="my-shares-back"
        :aria-label="selecting ? '退出多选' : '返回我的'"
        @click="selecting ? toggleSelection() : emit('back')"
      >
        <ChevronLeft :size="24" />
      </button>
      <h1>
        {{ selecting ? `已选中${selectedIds.length}个文件` : "我的分享" }}
      </h1>
      <button type="button" class="my-shares-select" @click="toggleSelection">
        {{ selecting ? "取消" : "多选" }}
      </button>
    </header>
    <nav class="my-shares-tabs" aria-label="分享状态">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        :class="{ active: filter === tab.id }"
        :aria-current="filter === tab.id ? 'page' : undefined"
        @click="changeFilter(tab.id)"
      >
        {{ tab.label }}
      </button>
    </nav>
    <div class="my-shares-content">
      <div v-if="loading" class="my-shares-empty">正在加载分享…</div>
      <div v-else-if="error" class="my-shares-empty" role="alert">
        <p>{{ error }}</p>
        <button type="button" @click="emit('refresh')">重试加载</button>
      </div>
      <div v-else-if="!visibleShares.length" class="my-shares-empty">
        <Share2 :size="36" />
        <p>
          {{
            filter === "all"
              ? "还没有分享，去文件页选择文件后点击“分享”"
              : "该分类暂无分享"
          }}
        </p>
      </div>
      <ul v-else class="my-shares-items">
        <li v-for="share in visibleShares" :key="share.id">
          <button
            type="button"
            class="my-shares-row"
            :class="{ selected: selectedIds.includes(share.id) }"
            @click="openOrSelect(share)"
          >
            <span class="my-shares-file-icon"
              ><component
                :is="iconForFile({ name: share.fileName, kind: share.kind })"
                :size="29"
                aria-hidden="true"
            /></span>
            <span class="my-shares-file-info"
              ><strong>{{ share.fileName }}</strong
              ><small>{{
                new Date(share.createdAt).toLocaleString("zh-CN", {
                  month: "2-digit",
                  day: "2-digit",
                  hour: "2-digit",
                  minute: "2-digit",
                  hour12: false,
                })
              }}</small></span
            >
            <span class="my-shares-status" :class="state(share)">{{
              state(share) === "active"
                ? `有效至 ${new Date(share.expiresAt).toLocaleDateString("zh-CN")}`
                : state(share) === "expired"
                  ? "已过期"
                  : "已取消"
            }}</span>
            <span
              v-if="selecting"
              class="my-shares-check"
              :class="{
                checked: selectedIds.includes(share.id),
                unavailable: state(share) !== 'active',
              }"
              aria-hidden="true"
              ><Check v-if="selectedIds.includes(share.id)" :size="13"
            /></span>
          </button>
        </li>
      </ul>
    </div>
    <footer v-if="selecting && selectedIds.length" class="my-shares-actions">
      <button
        type="button"
        :disabled="!cancellableShares.length || loading"
        @click="cancelSelected"
      >
        <XCircle :size="20" /><span>取消分享</span>
      </button>
      <button
        type="button"
        :disabled="!copyableShares.length || loading"
        @click="copySelected"
      >
        <Link2 :size="20" /><span>复制链接</span>
      </button>
    </footer>
  </main>
</template>
