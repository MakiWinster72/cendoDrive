<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import {
  Download,
  File,
  Folder,
  LoaderCircle,
  ShieldCheck,
} from "lucide-vue-next";
import BrandLogo from "../components/BrandLogo.vue";
import { formatSize } from "../stores/drive";
import {
  downloadPublicShare,
  getPublicShare,
  shareErrorMessage,
  type ShareAccessResponse,
} from "../api/shares";

const route = useRoute();
const token = computed(() => String(route.params.token || ""));
const access = ref<ShareAccessResponse | null>(null);
const loading = ref(false);
const downloading = ref(false);
const error = ref("");
const file = computed(() => access.value?.file ?? null);

async function loadShare() {
  if (!token.value) return;
  loading.value = true;
  error.value = "";
  access.value = null;
  try {
    access.value = await getPublicShare(token.value);
  } catch (reason) {
    error.value = shareErrorMessage(reason, "分享链接不存在、已过期或已取消");
  } finally {
    loading.value = false;
  }
}

async function download() {
  if (!file.value || file.value.kind === "folder" || downloading.value) return;
  downloading.value = true;
  error.value = "";
  try {
    await downloadPublicShare(token.value, file.value.name);
  } catch (reason) {
    error.value = shareErrorMessage(reason, "下载失败，请稍后重试");
  } finally {
    downloading.value = false;
  }
}

onMounted(() => {
  void loadShare();
});
watch(token, () => {
  void loadShare();
});
</script>

<template>
  <main class="share-page">
    <header><BrandLogo /></header>
    <section class="share-card">
      <template v-if="loading"
        ><LoaderCircle class="loading-icon" :size="42" />
        <h1>正在验证分享链接</h1>
        <p>请稍候…</p></template
      >
      <template v-else-if="!access"
        ><div class="status-icon">!</div>
        <h1>分享不可用</h1>
        <p>{{ error || "分享链接不存在、已过期或已取消。" }}</p>
        <RouterLink to="/login">返回登录</RouterLink></template
      >
      <template v-else-if="file"
        ><ShieldCheck :size="48" />
        <h1>分享文件</h1>
        <strong class="shared-file-name">{{ file.name }}</strong>
        <p>
          {{ formatSize(file.size) }} · 有效期至
          {{
            new Date(access.expiresAt).toLocaleString("zh-CN", {
              hour12: false,
            })
          }}
        </p>
        <button
          class="download"
          :disabled="file.kind === 'folder' || downloading"
          @click="download"
        >
          <Download :size="18" />{{
            file.kind === "folder"
              ? "文件夹暂不支持下载"
              : downloading
                ? "正在下载…"
                : "下载文件"
          }}</button
        ><small v-if="error" class="share-error" role="alert">{{
          error
        }}</small></template
      >
    </section>
  </main>
</template>

<style scoped>
.share-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #eef4ff, #f9fbff);
  padding: 0 24px;
}
.share-page header {
  height: 72px;
  display: flex;
  align-items: center;
  max-width: 1100px;
  margin: auto;
}
.share-card {
  width: min(460px, 100%);
  min-height: 340px;
  margin: 10vh auto 0;
  padding: 46px 38px;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 18px 60px rgba(42, 74, 130, 0.14);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  color: #316cff;
}
.share-card h1 {
  margin: 18px 0 8px;
  color: #263247;
  font-size: 23px;
}
.share-card p {
  margin: 8px 0 26px;
  color: #8993a2;
  font-size: 13px;
}
.share-card button,
.share-card a {
  height: 44px;
  border: 0;
  border-radius: 8px;
  background: #316cff;
  color: #fff;
  padding: 0 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
}
.share-card small {
  color: #ef4444;
  margin-top: 13px;
}
.status-icon {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #fff0f0;
  color: #f05252;
  font-size: 28px;
  font-weight: 700;
}
.download:disabled {
  opacity: 0.55;
}
.shared-file-name {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: #33415b;
}
.share-error {
  color: #de4f59;
}
.loading-icon {
  margin: 20px 0 0;
  animation: share-spin 1s linear infinite;
}
@keyframes share-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
