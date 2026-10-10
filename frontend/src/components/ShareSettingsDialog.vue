<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { RefreshCw, X } from "@lucide/vue";
import { shareErrorMessage, type ShareRecord } from "../api/shares";
import { useDrive } from "../stores/drive";
import "../styles/share-settings.css";

const props = defineProps<{ file: { id: string; name: string } }>();
const emit = defineEmits<{ close: []; created: [share: ShareRecord] }>();
const drive = useDrive();
const dialog = ref<HTMLDialogElement | null>(null);
const duration = ref("7"),
  customDays = ref(7),
  protectedShare = ref(false);
const code = ref(generateCode()),
  busy = ref(false),
  error = ref("");
const days = computed(() =>
  duration.value === "custom" ? customDays.value : Number(duration.value),
);
const previousOverflow = document.body.style.overflow;
let alive = true;
function generateCode() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  return Array.from(
    crypto.getRandomValues(new Uint8Array(6)),
    (value) => alphabet[value & 31],
  ).join("");
}
function close() {
  if (!busy.value) emit("close");
}
async function create() {
  if (busy.value) return;
  if (!Number.isInteger(days.value) || days.value < 1 || days.value > 30) {
    error.value = "有效期请选择 1–30 天";
    return;
  }
  if (protectedShare.value && !/^[A-Za-z0-9]{4,16}$/.test(code.value)) {
    error.value = "提取码需要 4–16 位字母或数字，区分大小写";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const result = await drive.share(
      props.file.id,
      days.value * 86400,
      protectedShare.value ? code.value : undefined,
    );
    if (alive) emit("created", result);
  } catch (reason) {
    if (alive) error.value = shareErrorMessage(reason, "分享创建失败，请重试");
  } finally {
    if (alive) busy.value = false;
  }
}
onMounted(() => {
  document.body.style.overflow = "hidden";
  dialog.value?.showModal();
});
onUnmounted(() => {
  alive = false;
  document.body.style.overflow = previousOverflow;
});
</script>

<template>
  <dialog
    ref="dialog"
    class="share-settings"
    aria-labelledby="share-settings-title"
    @cancel.prevent="close"
    @click.self="close"
  >
    <header>
      <h2 id="share-settings-title">分享设置</h2>
      <button
        type="button"
        aria-label="关闭分享设置"
        :disabled="busy"
        @click="close"
      >
        <X :size="20" />
      </button>
    </header>
    <p class="share-settings-file">{{ file.name }}</p>
    <form @submit.prevent="create">
      <label for="share-duration">有效期</label>
      <select id="share-duration" v-model="duration" :disabled="busy" autofocus>
        <option value="1">1 天</option>
        <option value="7">7 天</option>
        <option value="30">30 天</option>
        <option value="custom">自定义天数</option>
      </select>
      <template v-if="duration === 'custom'"
        ><label for="share-custom-days">有效天数（1–30）</label
        ><input
          id="share-custom-days"
          v-model.number="customDays"
          type="number"
          min="1"
          max="30"
          step="1"
          required
          :disabled="busy"
      /></template>
      <label class="share-code-toggle"
        ><input
          v-model="protectedShare"
          type="checkbox"
          :disabled="busy"
        />使用提取码</label
      >
      <template v-if="protectedShare">
        <label for="share-extraction-code">提取码</label>
        <div class="share-code-input">
          <input
            id="share-extraction-code"
            v-model="code"
            maxlength="16"
            minlength="4"
            pattern="[A-Za-z0-9]{4,16}"
            autocomplete="off"
            spellcheck="false"
            required
            :disabled="busy"
          /><button
            type="button"
            aria-label="生成新提取码"
            :disabled="busy"
            @click="code = generateCode()"
          >
            <RefreshCw :size="18" />
          </button>
        </div>
        <p class="share-settings-hint">
          4–16
          位字母或数字，区分大小写。提取码仅在当前页面内保留，请在创建后妥善保存。
        </p>
      </template>
      <p v-else class="share-settings-hint">
        无需提取码，获得链接的任何人均可访问。
      </p>
      <p class="share-settings-hint">
        分享仅供查看、下载或登录后转存；可随时取消。已下载或转存的副本无法收回。
      </p>
      <p v-if="error" class="share-settings-error" role="alert">{{ error }}</p>
      <footer>
        <button type="button" :disabled="busy" @click="close">取消</button
        ><button class="share-settings-primary" :disabled="busy">
          {{ busy ? "正在创建…" : "创建分享" }}
        </button>
      </footer>
    </form>
  </dialog>
</template>
