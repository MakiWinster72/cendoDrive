<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import type { MembershipPlanContent } from "../api/content";
import { simulateMembershipPayment } from "../api/membership";
import { useAuth } from "../stores/auth";
import "../styles/file-tools.css";
import "../styles/trash-confirm.css";
const props = defineProps<{ plan: MembershipPlanContent }>();
const emit = defineEmits<{ close: []; paid: [] }>();
const auth = useAuth();
const dialog = ref<HTMLDialogElement>();
const busy = ref(false);
const error = ref("");
let alive = true;
let previousOverflow = "";
function close() {
  if (!busy.value) emit("close");
}
async function confirm() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const result = await simulateMembershipPayment(props.plan.id);
    auth.updateUser(result.user);
    if (alive) emit("paid");
  } catch {
    if (alive) error.value = "模拟支付失败，请检查登录状态或稍后重试";
  } finally {
    if (alive) busy.value = false;
  }
}
onMounted(() => {
  previousOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  dialog.value?.showModal();
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
    class="file-tools trash-confirm simulated-payment"
    aria-labelledby="simulated-payment-title"
    aria-describedby="simulated-payment-hint"
    :aria-busy="busy"
    @cancel.prevent="close"
    @click.self="close"
  >
    <header>
      <h2 id="simulated-payment-title">微信模拟支付</h2>
      <button :disabled="busy" aria-label="关闭模拟支付" @click="close">
        ×
      </button>
    </header>
    <p class="trash-confirm-target">{{ plan.name }} · ¥{{ plan.price }}</p>
    <p id="simulated-payment-hint">
      仅模拟对应金额，不扣款、不自动续费。确认后开通 SVIP
      会员，不设到期时间，上传仍限制为每文件 128 KB/s。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <footer>
      <button :disabled="busy" autofocus @click="close">取消</button
      ><button class="trash-confirm-submit" :disabled="busy" @click="confirm">
        {{ busy ? "处理中…" : "确认模拟支付" }}
      </button>
    </footer>
  </dialog>
</template>
