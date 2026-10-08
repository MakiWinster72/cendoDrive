<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuth } from "../stores/auth";
import {
  Download,
  CloudDownload,
  LoaderCircle,
  LockKeyhole,
} from "@lucide/vue";
import { iconForFile } from "../components/fileIcon";
import BrandLogo from "../components/BrandLogo.vue";
import { formatSize } from "../stores/drive";
import {
  shareExpiryLabel,
  downloadPublicShare,
  getPublicShare,
  saveSharedFile,
  shareErrorMessage,
  requiresShareCode,
  isShareUnavailable,
  type ShareAccessResponse,
} from "../api/shares";

const route = useRoute();
const router = useRouter();
const auth = useAuth();
const token = computed(() => String(route.params.token || ""));
const access = ref<ShareAccessResponse | null>(null);
const loading = ref(false);
const downloading = ref(false);
const saving = ref(false);
const saved = ref(false);
let loadVersion = 0;
let loadController: AbortController | undefined;
const error = ref("");
const needsCode = ref(false), code = ref("");
const codeInput = ref<HTMLInputElement | null>(null);
const file = computed(() => access.value?.file ?? null);

async function loadShare() {
  const version = ++loadVersion;
  loadController?.abort();
  loadController = new AbortController();
  loading.value = true;
  error.value = "";
  access.value = null;
  saved.value = false;
  try {
    const result = await getPublicShare(token.value, loadController.signal, code.value || undefined);
    if (version === loadVersion) access.value = result;
  } catch (reason) {
    if (version === loadVersion) {
      const required = requiresShareCode(reason);
      needsCode.value = required || (needsCode.value && !isShareUnavailable(reason));
      error.value = required && !code.value ? "" : shareErrorMessage(reason, "分享链接不存在、已过期或已取消");
    }
  } finally {
    if (version === loadVersion) loading.value = false;
  }
}

async function download() {
  if (!file.value || file.value.kind === "folder" || downloading.value) return;
  const requestedToken = token.value;
  downloading.value = true;
  error.value = "";
  try {
    await downloadPublicShare(requestedToken, file.value.name, code.value || undefined);
  } catch (reason) {
    if (requestedToken === token.value)
      error.value = shareErrorMessage(reason, "下载失败，请稍后重试");
  } finally {
    downloading.value = false;
  }
}

async function save() {
  if (!file.value || file.value.kind === "folder" || saving.value || saved.value) return;
  const requestedToken = token.value;
  saving.value = true;
  error.value = "";
  try {
    if (!(await auth.ensureSession())) {
      if (auth.verificationError.value) {
        error.value = "暂时无法确认登录状态，请稍后重试";
        return;
      }
      await router.push({ name: "login", query: { redirect: route.fullPath } });
      return;
    }
    if (requestedToken !== token.value) return;
    await saveSharedFile(requestedToken, null, code.value || undefined);
    if (requestedToken === token.value) saved.value = true;
  } catch (reason) {
    if (requestedToken === token.value)
      error.value = shareErrorMessage(reason, "转存失败，请稍后重试");
  } finally {
    saving.value = false;
  }
}

watch(token, () => {
  const suppliedCode = new URLSearchParams((route.hash ?? "").slice(1)).get("code") ?? "";
  code.value = /^[A-Za-z0-9]{4,16}$/.test(suppliedCode) ? suppliedCode : "";
  needsCode.value = false;
  void loadShare();
}, { immediate: true });
watch([loading, needsCode], async () => {
  if (!loading.value && needsCode.value && !access.value) { await nextTick(); codeInput.value?.focus(); }
});
onUnmounted(() => {
  loadVersion++;
  loadController?.abort();
  code.value = "";
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
      <template v-else-if="needsCode && !access">
        <LockKeyhole :size="48" /><h1>请输入提取码</h1>
        <p>此分享已设置提取码，区分大小写。</p>
        <form class="share-code-form" @submit.prevent="loadShare">
          <label for="public-share-code">提取码</label>
          <input id="public-share-code" ref="codeInput" v-model="code" minlength="4" maxlength="16" pattern="[A-Za-z0-9]{4,16}" autocomplete="off" spellcheck="false" required>
          <button class="download" type="submit">验证提取码</button>
        </form>
        <small v-if="error" class="share-error" role="alert">{{ error }}</small>
      </template>
      <template v-else-if="!access"
        ><div class="status-icon">!</div>
        <h1>分享不可用</h1>
        <p>{{ error || "分享链接不存在、已过期或已取消。" }}</p>
        <RouterLink to="/login">返回登录</RouterLink></template
      >
      <template v-else-if="file"
        ><component :is="iconForFile(file)" :size="48" aria-hidden="true" />
        <h1>分享文件</h1>
        <strong class="shared-file-name">{{ file.name }}</strong>
        <p>
          {{ formatSize(file.size) }} · {{ shareExpiryLabel(access.expiresAt) }}
        </p>
        <div class="share-actions">
        <button
          type="button"
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
          }}</button>
        <button
          type="button"
          class="save-share"
          :disabled="file.kind === 'folder' || saving || saved"
          @click="save"
        >
          <CloudDownload :size="18" />{{
            saved ? "已转存" : saving ? "正在转存…" : auth.loggedIn.value ? "转存到我的网盘" : "登录后转存"
          }}
        </button>
        </div>
        <small v-if="saved" class="share-success" role="status">已保存到我的网盘根目录</small>
        <RouterLink v-if="saved" class="open-drive" to="/">查看我的网盘</RouterLink>
        <small v-if="error" class="share-error" role="alert">{{
          error
        }}</small></template
      >
    </section>
  </main>
</template>

<style scoped>
.share-code-form { width: 100%; display: grid; gap: 12px; text-align: left; }
.share-code-form label { font-size: 14px; color: #33415b; }
.share-code-form input { width: 100%; min-width: 0; box-sizing: border-box; min-height: 44px; padding: 0 12px; border: 1px solid #dce4f1; border-radius: 9px; font: inherit; }
.share-code-form .download { width: 100%; margin: 4px 0 0; }
.share-code-form :focus-visible { outline: 2px solid #316cff; outline-offset: 3px; }
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
.share-card button:disabled {
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
.share-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  max-width: 300px;
}
.share-card .save-share {
  color: #316cff;
  background: #eef4ff;
  border: 1px solid #d8e5ff;
}
.share-card .share-success {
  color: #16845b;
}
.share-card .open-drive {
  margin-top: 12px;
}
@media (width < 768px) {
  .share-card { padding: 34px 24px; margin-top: 5vh; }
}
.loading-icon {
  margin: 20px 0 0;
  animation: share-spin 1s linear infinite;
}
@media (prefers-reduced-motion: reduce) {
  .loading-icon { animation: none; }
}
@keyframes share-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
