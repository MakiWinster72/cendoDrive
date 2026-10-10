<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import {
  Search,
  ChevronRight,
  ArrowUpDown,
  FolderOpen,
  RefreshCw,
  Check,
} from "@lucide/vue";
import {
  listFiles,
  driveErrorMessage,
  type DriveItemResponse,
} from "../api/drive";
import { formatSize } from "../stores/drive";
import ChatFileGlyph from "./ChatFileGlyph.vue";
const props = defineProps<{ foldersOnly?: boolean; disabled?: boolean }>();
const emit = defineEmits<{
  select: [file: DriveItemResponse | null];
  directory: [id: string | null, name: string];
}>();
const items = ref<DriveItemResponse[]>([]),
  trail = ref<{ id: string; name: string }[]>([]);
const selected = ref<string>(),
  query = ref(""),
  sort = ref("name"),
  loading = ref(false),
  error = ref("");
let version = 0;
const visible = computed(() =>
  items.value
    .filter(
      (item) =>
        (!props.foldersOnly || item.kind === "folder") &&
        item.name.toLowerCase().includes(query.value.trim().toLowerCase()),
    )
    .sort((a, b) => {
      if (a.kind !== b.kind) return a.kind === "folder" ? -1 : 1;
      return sort.value === "recent"
        ? b.updatedAt.localeCompare(a.updatedAt)
        : a.name.localeCompare(b.name, "zh-CN", { numeric: true });
    }),
);
async function load(next = trail.value) {
  const request = ++version;
  loading.value = true;
  error.value = "";
  selected.value = undefined;
  emit("select", null);
  try {
    const result = await listFiles(next.at(-1)?.id ?? null);
    if (request !== version) return;
    items.value = result.filter((item) => !item.hidden && !item.deletedAt);
    trail.value = next;
    query.value = "";
    emit("directory", next.at(-1)?.id ?? null, next.at(-1)?.name ?? "我的云盘");
  } catch (cause) {
    if (request === version)
      error.value = driveErrorMessage(cause, "文件列表加载失败，请重试");
  } finally {
    if (request === version) loading.value = false;
  }
}
function choose(item: DriveItemResponse) {
  if (props.disabled || loading.value) return;
  if (item.kind === "folder")
    void load([...trail.value, { id: item.id, name: item.name }]);
  else {
    selected.value = selected.value === item.id ? undefined : item.id;
    emit("select", selected.value ? item : null);
  }
}
function date(value: string) {
  return value ? new Date(value).toLocaleDateString("zh-CN") : "";
}
onMounted(() => void load());
onBeforeUnmount(() => {
  version++;
});
</script>
<template>
  <section class="cloud-browser" :aria-busy="loading" aria-label="我的云盘目录">
    <nav class="cloud-breadcrumbs" aria-label="目录路径">
      <button :disabled="disabled || loading" @click="load([])">我的云盘</button
      ><template v-for="(folder, index) in trail" :key="folder.id"
        ><ChevronRight :size="14" /><button
          :disabled="disabled || loading"
          :aria-current="index === trail.length - 1 ? 'location' : undefined"
          @click="load(trail.slice(0, index + 1))"
        >
          {{ folder.name }}
        </button></template
      >
    </nav>
    <div class="cloud-search">
      <Search :size="18" /><input
        v-model="query"
        aria-label="搜索当前目录"
        placeholder="搜索当前目录"
        :disabled="disabled || loading"
      />
    </div>
    <div class="cloud-list-heading">
      <span
        >{{ foldersOnly ? "文件夹" : "全部文件" }}
        <small>{{ visible.length }} 项</small></span
      ><label
        ><ArrowUpDown :size="14" /><select v-model="sort" aria-label="文件排序">
          <option value="name">名称排序</option>
          <option value="recent">最近修改</option>
        </select></label
      >
    </div>
    <div v-if="loading" class="cloud-state" role="status">
      <span class="cloud-spinner"></span>
      <p>正在加载文件…</p>
    </div>
    <div v-else-if="error" class="cloud-state" role="alert">
      <FolderOpen :size="40" />
      <p>{{ error }}</p>
      <button class="cloud-retry" @click="load()">
        <RefreshCw :size="16" />重新加载
      </button>
    </div>
    <template v-else
      ><div class="cloud-list">
        <button
          v-for="item in visible"
          :key="item.id"
          class="cloud-file-row"
          :class="{ 'is-selected': selected === item.id }"
          :disabled="disabled"
          :aria-pressed="
            item.kind === 'file' ? selected === item.id : undefined
          "
          @click="choose(item)"
        >
          <ChatFileGlyph
            :name="item.name"
            :folder="item.kind === 'folder'"
          /><span class="cloud-file-label"
            ><strong>{{ item.name }}</strong
            ><small
              >{{
                item.kind === "file"
                  ? formatSize(item.size) + " · "
                  : "文件夹 · "
              }}{{ date(item.updatedAt) }}</small
            ></span
          ><ChevronRight v-if="item.kind === 'folder'" :size="18" /><span
            v-else
            class="cloud-radio"
            :class="{ 'is-checked': selected === item.id }"
            ><Check v-if="selected === item.id" :size="14"
          /></span>
        </button>
      </div>
      <div v-if="!visible.length" class="cloud-state">
        <FolderOpen :size="44" />
        <p>
          {{
            query
              ? "没有找到匹配的项目"
              : foldersOnly
                ? "此目录没有子文件夹"
                : "此目录还没有文件"
          }}
        </p>
        <small>{{
          query
            ? "试试其他关键词"
            : foldersOnly
              ? "可以直接转存到当前目录"
              : "返回上级目录选择文件"
        }}</small>
      </div></template
    >
  </section>
</template>
