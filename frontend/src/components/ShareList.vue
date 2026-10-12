<script setup lang="ts">
import { computed } from "vue";
import { Copy, Share2, X } from "@lucide/vue";
import { iconForFile } from "./fileIcon";
import { shareExpiryLabel, type ShareRecord } from "../api/shares";

const props = withDefaults(
  defineProps<{ shares: ShareRecord[]; loading?: boolean }>(),
  { loading: false },
);
const emit = defineEmits<{
  copy: [share: ShareRecord];
  cancel: [share: ShareRecord];
}>();
const sortedShares = computed(() =>
  [...props.shares].sort(
    (a, b) => +new Date(b.createdAt) - +new Date(a.createdAt),
  ),
);
function active(share: ShareRecord) {
  return share.status === "ACTIVE" && new Date(share.expiresAt) > new Date();
}
function statusLabel(share: ShareRecord) {
  if (share.status === "CANCELLED") return "已取消";
  return active(share) ? "有效" : "已过期";
}
function shareLink(share: ShareRecord) {
  return `${window.location.origin}/share/${encodeURIComponent(share.token)}`;
}
</script>

<template>
  <section class="share-list" aria-label="我的分享">
    <div v-if="loading" class="share-list-state">正在加载分享…</div>
    <div v-else-if="!sortedShares.length" class="share-list-state">
      <Share2 :size="25" /><strong>还没有分享</strong
      ><span>选中文件后使用“分享”创建链接</span>
    </div>
    <template v-else>
      <article
        v-for="share in sortedShares"
        :key="share.id"
        class="share-list-item"
      >
        <div class="share-list-heading">
          <div class="share-list-file">
            <span
              ><component
                :is="iconForFile({ name: share.fileName, kind: share.kind })"
                :size="24"
                aria-hidden="true"
            /></span>
            <div>
              <strong>{{ share.fileName }}</strong
              ><small
                >{{ share.size.toLocaleString() }} B · 创建于
                {{
                  new Date(share.createdAt).toLocaleString("zh-CN", {
                    hour12: false,
                  })
                }}</small
              >
            </div>
          </div>
          <span class="share-status" :class="{ inactive: !active(share) }">{{
            statusLabel(share)
          }}</span>
        </div>
        <div class="share-list-link">
          <input
            :value="shareLink(share)"
            readonly
            @focus="($event.target as HTMLInputElement).select()"
          /><button
            type="button"
            :disabled="!active(share)"
            @click="emit('copy', share)"
          >
            <Copy :size="15" />复制
          </button>
        </div>
        <div class="share-list-footer">
          <small>{{ shareExpiryLabel(share.expiresAt) }}</small
          ><button
            v-if="active(share)"
            type="button"
            :disabled="loading"
            @click="emit('cancel', share)"
          >
            <X :size="15" />取消分享
          </button>
        </div>
      </article>
    </template>
  </section>
</template>

<style scoped>
.share-list-link button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.share-list {
  display: grid;
  gap: 12px;
}
.share-list-item {
  min-width: 0;
  padding: 16px;
  border: 1px solid #e6ebf3;
  border-radius: 13px;
  background: #fff;
}
.share-list-heading,
.share-list-file,
.share-list-link,
.share-list-footer {
  display: flex;
  align-items: center;
}
.share-list-heading {
  justify-content: space-between;
  gap: 10px;
}
.share-list-file {
  min-width: 0;
  gap: 10px;
}
.share-list-file > span {
  display: grid;
  place-items: center;
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: #edf3ff;
  color: #386ff1;
}
.share-list-file > div {
  min-width: 0;
}
.share-list-file strong,
.share-list-file small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.share-list-file strong {
  font-size: 13px;
}
.share-list-file small,
.share-list-footer small {
  margin-top: 4px;
  color: #8995a8;
  font-size: 10px;
}
.share-status {
  flex: none;
  padding: 4px 8px;
  border-radius: 999px;
  background: #e9f8f0;
  color: #22945f;
  font-size: 10px;
}
.share-status.inactive {
  background: #f1f3f6;
  color: #7b8492;
}
.share-list-link {
  gap: 7px;
  margin-top: 13px;
}
.share-list-link input {
  min-width: 0;
  flex: 1;
  height: 34px;
  padding: 0 9px;
  border: 1px solid #e2e7ef;
  border-radius: 7px;
  background: #f9fbff;
  color: #56647a;
  font-size: 11px;
}
.share-list-link button,
.share-list-footer button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 32px;
  padding: 0 9px;
  border: 1px solid #dce4f1;
  border-radius: 7px;
  background: #fff;
  color: #376bd5;
  font-size: 11px;
  white-space: nowrap;
}
.share-list-footer {
  justify-content: space-between;
  gap: 8px;
  margin-top: 9px;
}
.share-list-footer small {
  margin: 0;
}
.share-list-footer button:disabled {
  opacity: 0.5;
}
.share-list-state {
  min-height: 170px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: #93a0b3;
  font-size: 12px;
}
.share-list-state strong {
  color: #33415b;
  font-size: 14px;
}
</style>
